package com.meowo.chat

import com.meowo.chat.domain.ChatMessage
import com.meowo.chat.domain.Outcome
import com.meowo.platform.ChatLine
import com.meowo.platform.PlatformHost
import com.meowo.shared.lifecycle.Context
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * Chat parsing, filtering, moderation, and the plugin's own speakers as addressable participants.
 *
 * ## Where the 风纪委员 AI plugs in
 *
 * Three seams, all in `api/`:
 *
 * - `ChatModerationAPI.register` — judge messages on the chat path (must be fast; the docs say so)
 * - `ChatModerationAPI.setRisk` — push an out-of-band score the gate reads in O(1)
 * - `ChatModerationAPI.claimSpeaker` — answer `/msg <cat> …`, i.e. come online
 *
 * ## Threading
 *
 * `AsyncChatEvent` fires off the main thread, so the handler does nothing but hand an immutable
 * [ChatMessage] to the pure pipeline. Every Bukkit touch happens either before that (on the main
 * thread, while building the message) or inside the renderer. See docs/minimax/03.
 *
 * ## Testability caveat
 *
 * MockBukkit ships **no chat events at all**, so [onChat] cannot be covered by the integration
 * suite. All the logic lives in the pure `domain/` package and is tested there; this bridge stays a
 * thin adapter on purpose. See docs/minimax/15.
 *
 * The one thing tests cannot reach is the last mile: that installing a renderer really does move
 * Paper from `minecraft:chat` to `paper:raw` is a fact about the server, not about this code. The
 * disassembly that establishes it is quoted above, and the shape it receives is pinned by
 * `ChatLine.publicLine` — but the brackets really being gone on screen needs a real client.
 */
class ChatContext(
    private val host: PlatformHost,
    private val settings: ChatSettings = ChatSettings(),
) : Context, Listener {
    override val id = "chat"
    private val active = AtomicBoolean(false)
    private lateinit var service: ChatService
    private val resources = mutableListOf<AutoCloseable>()

    override fun start() {
        service = ChatService(
            settings = settings,
            host = host,
            onLog = { message, error -> host.plugin.logger.log(Level.WARNING, message, error) },
        )
        active.set(true)
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }

        // Services go up before the routes so a moderator can never observe a half-built context,
        // and come down after them, in reverse.
        service.startPersistence(host.plugin)
        service.journal().start(host.plugin)
        // A /reload never fires PlayerJoinEvent for the people already on the server, so without this
        // their retention clock would only be set the next time they log in — and a quiet regular
        // could be pruned by a rule meant to protect them.
        Bukkit.getOnlinePlayers().forEach { service.journal().noteLogin(it.uniqueId) }
        resources += service.journal().install()
        resources += service.installSpeakerCards()
        resources += service.registerServices()
        resources += service.installHelpdesk(host, settings.helpdesk)
        resources += service.registerCommands(host)
        resources += service.registerProfileEvents()
        host.plugin.logger.info("Chat ready; ${service.describe()}")
    }

    /**
     * The only place a chat message enters the plugin.
     *
     * Everything after building the message is pure domain code, so the async thread never touches
     * Bukkit. The player is captured for its id and released immediately.
     *
     * ## The renderer is not decoration, it is the whole trick
     *
     * Paper renders player chat through `ChatRenderer.defaultRenderer()`, which returns
     * `Component.translatable("chat.type.text", displayName, message)` — and the client's
     * `chat.type.text` is `"<%s> %s"`. That is where `<>` on **public** chat comes from, and no
     * amount of rewriting the event's message touches it.
     *
     * Installing our own renderer is what removes it. Paper decides the outgoing chat type in
     * `ChatProcessor.complete()`:
     *
     * ```java
     * key = (renderer instanceof ChatRenderer.Default) ? ChatType.CHAT : PAPER_RAW;
     * ```
     *
     * `ChatType.CHAT` carries the client's decoration; `PAPER_RAW` (`paper:raw`) is built with none,
     * so whatever the renderer returns is shown verbatim. Any renderer a plugin supplies is not a
     * `ChatRenderer.Default`, which is exactly the switch we need — see docs/minimax/09 §2.
     */
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!active.get()) return
        // The whole handler is wrapped, because a chat event that throws is not "one bad message" —
        // it is the plugin failing to answer the server's question, and the server's answer to an
        // event handler that throws is to log it and carry on. Which means the player's message would
        // be **delivered anyway**: the pipeline said nothing, so nothing cancelled the event, and the
        // raw text went to the chat with none of the filters applied.
        //
        // That is the worst possible outcome for a moderation feature — a broken rule silently
        // disables every rule — and it is silent: the player sees their message, and the operator
        // sees a stack trace in a log nobody reads. So a failure here cancels the message and
        // reports it, exactly like a block. Failing closed on the *whole* handler is the same
        // argument as the gate's fail-closed default: a gate that opens itself when something breaks
        // is not a gate.
        try {
            handleChat(event)
        } catch (error: Throwable) {
            // **Throwable**, not Exception: a catastrophic regex throws `StackOverflowError`, which is
            // an `Error`. Letting that out would take the whole context down with it — one bad
            // filter in `config.yml` and the chat is dead for everybody.
            host.plugin.logger.log(Level.WARNING, "chat pipeline failed; the message was dropped", error)
            event.isCancelled = true
            val player = event.player.uniqueId
            runCatching {
                service.reportBlocked(
                    ChatMessage(player, CHANNEL, "", System.currentTimeMillis()),
                    "pipeline-failed",
                )
            }
            service.notifyBlocked(player, muted = false)
        }
    }

    /**
     * The body of [onChat], split out so the catch above has exactly one thing to wrap.
     *
     * Everything after building the message is pure domain code, so the async thread never touches
     * Bukkit. The player is captured for its id and released immediately.
     */
    private fun handleChat(event: AsyncChatEvent) {
        val message = ChatMessage(
            player = event.player.uniqueId,
            channel = CHANNEL,
            // Neutralised **here**, at the one place every downstream path reads: the live line, the
            // moderation gate, the filters and the archive all derive from this string, and the
            // archive is legacy-deserialised again on `/meo replay`. Fixing only the live path would
            // leave the colour in the recording.
            rawText = withoutSections(PLAIN.serialize(event.message())),
            atEpochMillis = System.currentTimeMillis(),
        )
        when (val outcome = service.process(message)) {
            is Outcome.Drop -> {
                event.isCancelled = true
                service.reportBlocked(message, outcome.reason)
                // The player is told. Which of the two messages is decided by the reason, and the
                // reason itself never reaches them.
                service.notifyBlocked(event.player.uniqueId, muted = outcome.reason == MUTE_REASON)
            }
            is Outcome.Send -> {
                if (outcome.text != message.rawText) {
                    // The pipeline's own markup, interpreted by the one function allowed to.
                    // This used to be `LINE.deserialize(...)`, a legacy `§` reader, and the section
                    // sign was the only escape it honoured that a player could type — measured, both
                    // readers reject a bare `&#ff0000` and need the `§` in front of it, so this
                    // reader's `.hexCharacter('#')` added no second way in.
                    event.message(ChatLine.body(outcome.text))
                }
                // Whatever the client sent, **the client chose it**. Vanilla chat is plain text, but
                // the component reaching this handler came from the network, and a modified client can
                // put a `run_command` on one word of it. The click runs on whoever clicks, with their
                // permissions — so this has to be undone here, at the boundary, rather than trusted to
                // each renderer and each plugin downstream. Measured, not assumed: see
                // [com.meowo.platform.ChatLine.withoutEvents].
                else event.message(com.meowo.platform.ChatLine.withoutEvents(event.message()))
                // The record is written **before** the renderer runs, so the code the reader sees is
                // the code that was stored. A renderer that minted one would do it again per viewer.
                val record = service.recordPublished(message, event.player.name)
                // **Viewer-aware**: the profile card is language, and language is per viewer. A
                // viewer-unaware renderer would hand an English player the server default bundle,
                // which is how a Chinese card used to follow them around the whole server.
                event.renderer(
                    ChatRenderer { source, displayName, body, viewer ->
                        val player = viewer as? Player
                        // Whispering yourself is a shortcut that must not exist: on the speaker's own
                        // line the name is theirs, so both halves go quiet.
                        val mine = player?.name.equals(source.name, ignoreCase = true)
                        ChatLine.publicLine(
                            displayName,
                            service.profileCardFor(player, source.name),
                            body,
                            record.code,
                            service.journal().hoverText(player, record),
                            msgCommand = if (mine) "" else ChatLine.msgCommand(source.name),
                            replyCommand = if (mine) "" else ChatLine.replyCommand(source.name, record.code),
                            refCode = service.journal().refCode(record),
                            refHover = service.journal().refHover(player, record),
                        )
                    },
                )
            }
        }
    }

    /**
     * Removes every section sign from a player's text, so none of them can become a colour code.
     *
     * ## Why this is needed at all
     *
     * `§` is U+00A7 — **not** a control character, so [com.meowo.chat.domain.ChatPipeline.normalise]
     * keeps it, and the pipeline's own decoration output is handed to the legacy deserialiser, which
     * reads `§c` as "red from here". Measured, not suspected: typing
     * ``§c§lADMIN 管理员 §r`sad` `` came out of the pipeline as
     * `§c§lADMIN 管理员 §r😢` — changed, so the whole line went through the deserialiser — and
     * rendered as **red bold** text the player typed themselves.
     *
     * It only fires when the decoration changes the string, which is why an emoji is in the example:
     * a message with no emoji and no markup is passed through untouched and stays plain. That makes
     * it a nasty one to spot by reading the code — a plain `§c` test looks safe.
     *
     * ## Why removal, and why not escaping
     *
     * Doubling was the first idea and it is **wrong**, in a way that stays invisible until
     * measured: `§§c` deserialises to a literal `§` followed by **red**. Two section signs are
     * not an escape — they are a section sign and the start of a code. Every clever variant has the
     * same problem: drop the `§` and keep the `c`, and an *earlier* `§` in the same message turns
     * live; drop both, and `§§ca` leaves `§a`, which is dark green. No arrangement of section signs
     * is both inert and still shaped like one.
     *
     * So the sign goes. A player who types `§c` sees `c` — which is what vanilla would have given
     * them anyway — and nobody is misled about whose text they are reading.
     *
     * Nothing legitimate is lost. Vanilla chat is plain text, so a section sign in a player message is
     * only ever an escape attempt; every `§` in this plugin's own output is added **after** this
     * point and stays single.
     */
    private fun withoutSections(text: String): String =
        if (text.indexOf(SECTION) < 0) text else StringBuilder(text.length).apply {
            text.forEach { if (it != SECTION) append(it) }
        }.toString()

    /**
     * Retention is measured from here: how long a player's chat is kept is how long since they last
     * logged in, so this has to be a **join**, not a message. A player who logs in daily and says
     * nothing is still somebody whose words we are keeping, and a message-only signal would quietly
     * delete that player's history for being quiet.
     *
     * Costs one map write and a queued task; see [ChatJournal.noteLogin].
     */
    @EventHandler fun join(event: PlayerJoinEvent) {
        service.journal().noteLogin(event.player.uniqueId)
        // The mailbox is re-hydrated **here**, not only at start-up. The store is the authority and
        // the in-memory box is a cache of it, so a box has to be refilled whenever the player is
        // around to look at it — otherwise the mail that arrived while they were offline sits in the
        // database with nobody reading it, and the start-up hydration is the only thing that ever
        // puts it back.
        service.onJoin(event.player.uniqueId)
    }

    @EventHandler fun quit(event: PlayerQuitEvent) = service.forget(event.player.uniqueId)

    override fun close() {
        active.set(false)
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }

    private companion object {
        const val CHANNEL = "global"
        private const val SECTION = '\u00A7'
        val PLAIN = PlainTextComponentSerializer.plainText()
    }
}
