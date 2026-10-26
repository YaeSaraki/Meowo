package com.meowo.chat

import com.meowo.api.chat.ChatDirectoryAPI
import com.meowo.api.chat.ChatMessageView
import com.meowo.api.chat.ChatModerationAPI
import com.meowo.api.chat.ChatModerator
import com.meowo.api.chat.ParticipantInfo
import com.meowo.chat.infrastructure.MailStore
import com.meowo.api.chat.ReplyCapable
import com.meowo.api.chat.SystemSpeakerSink
import com.meowo.api.event.ChatMessageBlocked
import com.meowo.api.event.DirectMessageToSystem
import com.meowo.chat.domain.AuditBuffer
import com.meowo.chat.domain.ChatMessage
import com.meowo.chat.domain.ChatPipeline
import com.meowo.chat.domain.Directory
import com.meowo.chat.domain.EmojiParser
import com.meowo.chat.domain.GateSettings
import com.meowo.chat.domain.Delivery
import com.meowo.chat.domain.Mail
import com.meowo.chat.domain.Mailbox
import com.meowo.chat.domain.MessageCode
import com.meowo.chat.domain.ModerationGate
import com.meowo.chat.domain.Outcome
import com.meowo.chat.domain.Participant
import com.meowo.chat.domain.Record
import com.meowo.chat.domain.Reference
import com.meowo.chat.domain.ThreadTree
import com.meowo.platform.ChatLine
import com.meowo.platform.LineOrigin
import net.kyori.adventure.text.Component
import com.meowo.chat.domain.RiskRegistry
import com.meowo.chat.domain.RuleSet
import com.meowo.platform.PlatformHost
import com.meowo.platform.Timestamps
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The [Outcome.Drop] reason a mute produces, so the chat handler can tell "you are muted" apart from
 * "the 风纪 AI declined this" and pick the right notice. Shared because the two live in different
 * files, and comparing a bare string across them is how this goes wrong.
 */
const val MUTE_REASON = "muted"

/**
 * Owns the pipeline and all chat state, and is the only place the two service interfaces are built.
 *
 * Keeping the API objects as thin fields over the state here means unregistering cannot be got
 * wrong: the same instance that went in comes out (红线 §5).
 */
class ChatService(
    private val settings: ChatSettings,
    private val host: PlatformHost,
    private val profiles: ProfileCache = ProfileCache(host),
    private val onLog: (String, Throwable) -> Unit,
    /** Mints and keeps the `#k3f9` on every line. Injected so tests can run without a database. */
    private val journal: ChatJournal = ChatJournal(host, settings, onLog),
) {
    private val rules = RuleSet.fromConfig(settings.literalFilters, settings.regexFilters)

    init {
        // A filter that would not compile is **reported**, not thrown. The operator wrote the pattern
        // by hand, so "your regex is broken" is the single most useful thing this plugin can say
        // about it — and the alternative used to be that the whole chat context refused to start.
        rules.rejected.forEach { bad ->
            onLog("filter rule rejected and skipped: ${bad.id} — ${bad.reason}", IllegalArgumentException(bad.reason))
        }
    }
    private val risk = RiskRegistry()
    private val audit = AuditBuffer(settings.auditCapacity)
    private val mutes = ConcurrentHashMap<UUID, Long>()
    private val mailbox = Mailbox(settings.mailCapacity)
    /**
     * The mailbox is authoritative in memory; this only keeps it across restarts. Opened lazily in
     * [startPersistence] so a context that never runs does not create a database file.
     */
    @Volatile private var store: MailStore? = null

    /** Hydrates the in-memory mailbox from disk. Safe to call once per context start. */
    fun startPersistence(plugin: org.bukkit.plugin.java.JavaPlugin) {
        if (store != null) return
        val opened = runCatching { MailStore(plugin.dataFolder.toPath().resolve("chat/mail.db")) }
            .onFailure { error -> onLog("mail store unavailable, mail will not survive a restart", error) }
            .getOrNull() ?: return
        store = opened
        // The store's reads run on its worker, so they come back as futures. This is start-up, on the
        // main thread, and the point of the hydration is to have the boxes in place **before** the
        // first player asks for their mail — so it waits, with a bound, rather than hydrating
        // asynchronously and racing the first `/meo mail`.
        runCatching {
            val recipients = opened.recipients().get(5, java.util.concurrent.TimeUnit.SECONDS)
            recipients.forEach { box ->
                mailbox.replaceAll(box, opened.loadFor(box).get(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }.onFailure { error -> onLog("mail store unreadable, starting with an empty mailbox", error) }
    }

    private val sinks = ConcurrentHashMap<String, SystemSpeakerSink>()
    private val moderators = CopyOnWriteArrayList<ChatModerator>()

    @Volatile private var directory: Directory = SpeakerDirectory(host).build(settings.playerFirst)
    @Volatile private var closed = AtomicBoolean(false)

    private val gate = ModerationGate(
        rules = rules,
        risk = risk,
        settings = GateSettings(
            maxLength = settings.maxLength,
            warnAt = settings.warnAt,
            blockAt = settings.blockAt,
            failMode = if (settings.failOpen) GateSettings.FailMode.OPEN else GateSettings.FailMode.CLOSED,
        ),
        onFailure = onLog,
    // The gate already asks whether a player is muted, and had always answered no, because nobody
    // ever plugged the seam in: a mute set through `ChatModerationAPI` was honoured by the agents
    // context and by nothing else. Wiring it here covers **every** path through `decide` — public
    // chat, whispers and the helpdesk alike — instead of each caller remembering to ask.
    ).withMuteCheck { isMutedNow(it) }
    /**
     * The built-in table with the operator's additions on top.
     *
     * Merging rather than replacing is deliberate: a server that defines one emoji should not silently
     * lose the other forty, and a typo in a key should fall back to whatever the plugin ships rather
     * than leave players with a broken face.
     */
    private val emoji = LinkedHashMap(EmojiParser.DEFAULTS).apply { putAll(settings.extraEmoji) }

    /**
     * The pipeline, with **no** length argument.
     *
     * It used to be given `settings.maxLength` here and the gate was given the same value a few
     * lines up, from two separate constructor calls. The pipeline applied its copy *before* the gate
     * saw the text, so the gate's `too-long` verdict was unreachable in production: every message
     * had already been trimmed to the limit by the time it was asked about. The limit now lives in
     * the gate alone.
     */
    private val pipeline = ChatPipeline(gate, rules, emoji)

    private val moderationApi = object : ChatModerationAPI {
        /**
         * Registers a 风纪 auditor for **both** jobs: deciding messages and being told about them.
         *
         * Registering only with [moderators] was a real hole. That list drives the `onPublished` and
         * `onQuit` callbacks, while the judgement iterates the gate's own `auditors` — and nothing ever
         * populated that one, so every registered auditor was told what had happened and never asked
         * whether it should have been allowed. The seam the whole moderation API exists for was
         * connected to half of itself.
         */
        override fun register(moderator: ChatModerator): AutoCloseable {
            moderators.add(moderator)
            val gateHandle = gate.register(moderator)
            return AutoCloseable {
                gateHandle.close()
                moderators.remove(moderator)
            }
        }
        /**
         * Claims a speaker, **replacing** whoever held it.
         *
         * Replacement is the point: the built-in helpdesk holds every speaker at boot so `/msg` is
         * answerable out of the box, and the 风纪 AI takes over by claiming the same id. Closing the
         * handle restores the previous holder, so a crashed AI hands the cats back rather than
         * leaving them mute.
         */
        override fun claimSpeaker(speakerId: String, sink: SystemSpeakerSink): AutoCloseable {
            check(directory.speaker(speakerId) != null) { "Unknown speaker id: $speakerId" }
            // Injected, not wrapped: a sink that answers later has to be able to reach *this*
            // rendering, and it cannot import this context to find it. The identity check on close
            // still compares against the sink the caller handed over, so the map keeps the original.
            (sink as? ReplyCapable)?.attachReply { sender, name, id, body ->
                systemReply(name, id, sender, body)
            }
            val previous = sinks.put(speakerId, sink)
            refreshAnswerable()
            return AutoCloseable {
                // Only roll back if nobody else has claimed it since; a later claim owns the slot.
                if (sinks[speakerId] === sink) {
                    if (previous != null) sinks[speakerId] = previous else sinks.remove(speakerId)
                }
                refreshAnswerable()
            }
        }
        override fun history(player: UUID?, limit: Int) = audit.history(player, limit).map(::view)
        override fun risk(player: UUID) = risk.risk(player)
        override fun setRisk(player: UUID, value: Int) = risk.set(player, value)
        override fun bumpRisk(player: UUID, delta: Int) = risk.bump(player, delta)
        override fun muteUntil(player: UUID, epochMillis: Long) { mutes[player] = epochMillis }
        override fun isMuted(player: UUID) = (mutes[player] ?: 0L) > System.currentTimeMillis()
    }

    private val directoryApi = object : ChatDirectoryAPI {
        override fun participants(prefix: String, limit: Int) =
            directory.complete(prefix, SpeakerDirectory(host).onlinePlayers(), limit).map { participant ->
                when (participant) {
                    is Participant.System -> ParticipantInfo(participant.handle, participant.kind.name, participant.id, participant.answerable)
                    is Participant.Player -> ParticipantInfo(participant.handle, participant.kind.name, null, false)
                }
            }
        override fun speakerIds() = directory.speakerIds()

        /**
         * Through [Directory.find] rather than a second resolver, on purpose: that is the same
         * lookup `/msg` uses, so the same name cannot mean two different people in two places, and
         * it resolves a player who is offline but still known.
         */
        override fun uuidOf(handle: String): UUID? =
            (directory.find(handle) as? Participant.Player)?.uuid

        /**
         * From the journal's in-memory view, so this stays synchronous.
         *
         * The table behind it is the chat log's own; this context is the only thing that can answer,
         * which is why the method lives here rather than being recomputed by whoever asks.
         */
        override fun lastSeen(player: String): Long? =
            runCatching { UUID.fromString(player) }.getOrNull()?.let { journal.lastSeen(it) }

        /**
         * Deletes every recorded line belonging to [player] — chat log **and** mailbox — and
         * reports how many went.
         *
         * The mailbox is included because it is where a player's private messages actually live:
         * the chat log holds what they said in public channels, and the mailbox holds what was said
         * to them. Leaving the second out would mean a forget request that a player could see had
         * missed the most personal part of their record.
         */
        override fun forgetPlayer(player: String): Int = forgetPlayer(player)
    }

    // ── services ────────────────────────────────────────────────────────────────────────────

    /**
     * Puts the built-in helpdesk on every speaker so `/msg` answers out of the box. The real AI
     * replaces it by claiming the same ids; see [HelpdeskSink].
     */
    fun installHelpdesk(host: PlatformHost, enabled: Boolean): AutoCloseable {
        if (!enabled) return AutoCloseable {}
        val helpdesk = HelpdeskSink(host, this)
        val handles = directory.speakerIds().map { id ->
            moderationApi.claimSpeaker(id, helpdesk).also { moderators.add(helpdesk) }
        }
        return AutoCloseable {
            moderators.remove(helpdesk)
            handles.forEach { it.close() }
        }
    }

    fun registerServices(): AutoCloseable {
        Bukkit.getServicesManager().register(ChatModerationAPI::class.java, moderationApi, host.plugin, ServicePriority.Normal)
        Bukkit.getServicesManager().register(ChatDirectoryAPI::class.java, directoryApi, host.plugin, ServicePriority.Normal)
        return AutoCloseable { unregisterServices() }
    }

    fun unregisterServices() {
        if (!closed.compareAndSet(false, true)) return          // close() is idempotent
        Bukkit.getServicesManager().unregister(ChatModerationAPI::class.java, moderationApi)
        Bukkit.getServicesManager().unregister(ChatDirectoryAPI::class.java, directoryApi)
        sinks.clear(); moderators.clear(); mutes.clear()
        store?.close(); store = null
        risk.clear(); audit.clear()
        directory = directory.withAnswerable(emptySet())
    }

    // ── message flow ────────────────────────────────────────────────────────────────────────

    /**
     * Whether [player] is muted **right now**.
     *
     * The API has always exposed this and the chat path has always ignored it — only the agents
     * context asked, so a muted player could still talk in global chat and whisper to other players.
     * A mute that one code path honours looks enforced and is not; this is the one check every
     * outgoing path goes through.
     */
    fun isMutedNow(player: UUID): Boolean = (mutes[player] ?: 0L) > System.currentTimeMillis()

    fun process(message: ChatMessage): Outcome = pipeline.process(message)

    /** Whether [reason] is the gate's mute verdict rather than a 风纪 judgement. */
    private fun isMute(reason: String): Boolean = reason == MUTE_REASON

    fun reportBlocked(message: ChatMessage, reason: String) =
        host.events.publish(ChatMessageBlocked(message.player, message.channel, reason, message.atEpochMillis))

    /**
     * Tells a player their message did not go out.
     *
     * Silence here is the worst outcome available: the player sees their words vanish with nothing to
     * act on, and the 风纪 AI's carefully ordered judgement — gate before redaction, so the moderator
     * sees the original — has produced evidence that reaches nobody. And an unexplained block is
     * unappealable, which makes the whole gate pointless.
     *
     * **The reason is never shown.** Saying "it contained a word from the filter list" turns the
     * notice into a tutorial on how to get past it. The detail goes to the audit trail and to staff,
     * which is where an appeal can actually be answered.
     *
     * Sent on the main thread because the chat handler runs off it, and through [host.mainThread] so
     * a blocked message from an async thread cannot race the server.
     */
    fun notifyBlocked(player: UUID, muted: Boolean) {
        val target = Bukkit.getPlayer(player) ?: return
        host.mainThread {
            host.messages.send(target, if (muted) "chat-muted" else "chat-blocked")
        }
    }

    /**
     * Records a published public-chat line and returns its record, so the renderer can open it with
     * the same `#k3f9` that was stored.
     *
     * Returning the record rather than minting the code inside the renderer is not tidiness: a
     * renderer runs **per viewer**, and a code minted there would differ for each of them.
     */
    fun recordPublished(message: ChatMessage, authorName: String): Record {
        audit.record(message)
        val record = journal.record(
            LineOrigin(LineOrigin.Kind.PLAYER, authorName),
            message.rawText,
            null,
            authorId = message.player,
            atEpochMillis = message.atEpochMillis,
        )
        val view = view(message)
        for (moderator in moderators) {
            try { moderator.onPublished(view) } catch (error: Exception) { onLog("onPublished failed", error) }
        }
        // Published rather than moderated: a listener that only counts messages must not have to
        // register on the hot path to hear them (see ChatMessagePublished).
        host.events.publish(
            com.meowo.api.event.ChatMessagePublished(
                player = message.player,
                channel = message.channel,
                text = message.rawText,
                isPublic = !message.channel.startsWith("dm:"),
                hasEmoji = message.rawText.any { it.code > 0x2000 },
                atEpochMillis = message.atEpochMillis,
            ),
        )
        return record
    }

    /** The archive behind every `#k3f9`. */
    fun journal(): ChatJournal = journal

    /**
     * `/meo replay #k3f9` — the line, then everything answering it, oldest first.
     *
     * **Anyone may run it.** A private thread is visible to the whole server on purpose: the
     * alternative is a support question ("what did they send me at 21:24?") that only staff can
     * answer, and a code the reader cannot follow is not a code.
     *
     * The store hop is asynchronous, so printing happens on the main thread via [PlatformHost.reply]
     * rather than wherever SQLite happens to answer.
     */
    /**
     * Registers a 风纪 auditor, the seam the AI plugs into.
     *
     * Exposed so a caller can install one **after** the services are up without reaching for the
     * private [moderationApi]; the agents context registers through the service manager instead, and
     * having both spellings invites the two drifting apart.
     */
    fun registerModerator(moderator: com.meowo.api.chat.ChatModerator): AutoCloseable =
        moderationApi.register(moderator)

    /** Recent lines one player said or received, for `/meo chat <player>`. */
    fun recentOf(name: String, limit: Int = 20): List<Record> = journal.recentBy(name, limit)

    fun replayThread(sender: org.bukkit.entity.Player, raw: String?) {
        val code = raw?.trim()?.removePrefix(ChatLine.PREFIX)?.lowercase()
        if (code.isNullOrBlank() || !MessageCode.isValid(code)) {
            host.messages.sendPlain(sender, "replay-usage"); return
        }
        host.messages.sendPlain(sender, "replay-header", code)
        journal.thread(code).whenComplete { records, error ->
            host.reply(sender, CompletableFuture.completedFuture(records), { true }) { found ->
                if (error != null || found.isEmpty()) {
                    host.messages.sendPlain(sender, "replay-gone")
                    return@reply
                }
                host.messages.sendPlain(sender, "replay-body", found.size)
                // **Two lines per message, flat**: the number and the time, then the words. Nothing
                // else.
                //
                // Every earlier layout was one row of `code │ sender → recipient │ time │ body`, and
                // in a conversation the sender and recipient are the same on every row — a column of
                // `MeowCat → Alice` repeated ten times says less than the column it occupies. The
                // indentation that was supposed to carry the nesting just added another shape to read
                // past. The order still runs root-first, so replies follow what they answer.
                ThreadTree.nest(found).forEach { entry ->
                    host.messages.sendPlain(sender, "replay-head", entry.record.code, Timestamps.full(entry.record.atEpochMillis))
                    host.messages.sendPlain(sender, "replay-text", entry.record.body)
                }
            }
        }
    }

    /**
     * Keeps the profile cards honest: load on join, refresh the balance whenever the economy says
     * it moved, drop on quit. Without this a hovered balance would be whatever it was at join.
     */
    fun registerProfileEvents(): AutoCloseable {
        val handles = listOf(
            host.events.subscribe(com.meowo.api.event.PlayerJoined::class.java) { event ->
                Bukkit.getPlayer(event.player)?.let(profiles::load)
            },
            host.events.subscribe(com.meowo.api.event.BalanceChanged::class.java) { event ->
                profiles.onBalanceChanged(event.player, event.after)
            },
        )
        return AutoCloseable {
            handles.forEach { it.close() }
            profiles.forgetAll()
        }
    }

    fun profileCard(viewer: org.bukkit.command.CommandSender?, id: UUID) = profiles.card(viewer, id)

    fun profileCardFor(viewer: org.bukkit.command.CommandSender?, name: String) = profiles.cardFor(viewer, name)

    fun speakerCard(viewer: org.bukkit.command.CommandSender?, name: String, summary: String) =
        profiles.speakerCard(viewer, name, summary)

    /**
     * Teaches [`platform`] what a cat's card looks like.
     *
     * `Messages.systemName` renders every cat line and has no idea what a profile is — it only ever
     * got a `profile` parameter that **nothing ever passed**, so hovering `EconCat` showed no card at
     * all. `platform` must not import the chat context, so the card arrives as a callback and the
     * chat context owns building it. Cleared on close, so a disabled chat context leaves no listener.
     */
    fun installSpeakerCards(): AutoCloseable {
        host.messages.speakerCard = { viewer, speakerId ->
            val name = host.messages.speaker(viewer, speakerId)
            profiles.speakerCard(viewer, name, host.messages.speakerSummary(viewer, speakerId))
        }
        return AutoCloseable { host.messages.speakerCard = null }
    }

    /**
     * A player left: drop what only made sense while they were here.
     *
     * **The mailbox is not one of those things**, and dropping it here is what made a player's
     * unread mail vanish on logout and reappear after the next restart. The box was removed from
     * memory while its rows stayed in `chat/mail.db`, and the only code that read those rows back
     * was the start-up hydration — so the player's mailbox was empty on their next login and full
     * again after a server restart. Two sources of truth that only agreed at boot.
     *
     * The fix is that the box is re-hydrated **per player, when they join** (see [onJoin]), which
     * is where the box belongs: it is a cache of the store, and the store is the authority.
     */
    fun forget(player: UUID) {
        profiles.forget(player)
        risk.forget(player)
        // A mute is an **absolute deadline**, so it survives a disconnect: dropping it here would
        // let a muted player log out and back in and find the mute gone.
        val now = System.currentTimeMillis()
        mutes.entries.removeIf { (_, until) -> until <= now }
        mailbox.forget(player)
        for (moderator in moderators) {
            try { moderator.onQuit(player) } catch (error: Exception) { onLog("onQuit failed", error) }
        }
    }

    /**
     * Re-hydrates one player's mailbox from the store.
     *
     * Called on join, because that is the moment a box is needed and the moment the store may hold
     * rows the cache does not: mail that arrived while they were offline.
     *
     * The store is the authority and the cache is not, so the cache is **replaced** rather than
     * merged. Merging would leave a row in the cache that the store has already deleted — and the
     * only way a row is deleted is by a forget request, whose whole point is that the words are
     * gone.
     */
    fun onJoin(player: UUID) {
        val opened = store ?: return
        runCatching { opened.loadFor(player).get(5, java.util.concurrent.TimeUnit.SECONDS) }
            .onSuccess { mails -> mailbox.replaceAll(player, mails) }
            .onFailure { error -> onLog("mail store unreadable for $player", error) }
    }

    /**
     * The privacy erase: everything recorded about this player, in **both** stores.
     *
     * The chat log is only half of it. The mailbox is the other half, and it used to be left
     * entirely alone — so a player who asked to be forgotten kept every private message addressed
     * to them, on disk, forever, and saw them again the next time the server started.
     *
     * Both directions are deleted, exactly as the chat log deletes both directions: the copies
     * **of** them (their mailbox) and the copies **they sent** (in other people's mailboxes).
     * Leaving the second half would be the same mistake the chat log's own retention rule
     * documents — "the receiver is usually the one who has no idea it is on disk" applies to mail
     * just as much as to chat.
     *
     * @return how many rows went, chat log and mailbox together.
     */
    fun forgetPlayer(player: String): Int {
        // An id that is not a uuid **throws** rather than answering `0`. Three states would
        // otherwise collapse into one — could not parse, nothing matched, deleted a few — and the
        // caller is about to print this number at a player who asked to be forgotten
        // (docs/minimax/08 §38). Failing here is what lets it say "I could not reach the chat log"
        // instead of a confident zero.
        val id = UUID.fromString(player)
        val lines = journal.forgetPlayer(id)
        val mail = runCatching { store?.forget(id) ?: 0 }
            .onFailure { error -> onLog("mail forget failed for $id", error) }
            .getOrDefault(0)
        // The caches must not outlive the rows: a box that still holds a deleted row would put the
        // words back on screen the moment the player opened their mailbox.
        mailbox.forget(id)
        mailbox.forgetSentBy(id)
        return lines + mail
    }

    // ── direct messages ─────────────────────────────────────────────────────────────────────

    /**
     * Renders an answer to a system speaker as a private message, the same shape a player-to-player
     * whisper uses.
     *
     * A player addressing a cat never sees their own message echoed (see [directMessage]'s caller):
     * the reply is the receipt. Sending it as an ordinary chat line instead — which is what happens
     * if a sink answers through the public `Messages.say` path — leaves the player with no sign their
     * question was ever asked, and the answer wearing the wrong shape.
     *
     * **Threading**: [deliver] is called from the sink's own worker, so this hops to the main thread
     * before touching the journal or a [Player].
     */
    private fun systemReply(senderName: String, speakerId: String, recipient: UUID, body: String) {
        host.mainThread {
            val player = Bukkit.getPlayer(recipient) ?: return@mainThread
            val speakerName = host.messages.speaker(player, speakerId)
            val record = journal.record(
                LineOrigin(LineOrigin.Kind.CAT, speakerId, senderName),
                body,
                null,
                recipientId = recipient,
            )
            host.messages.privateMessage(
                player,
                ChatLine.who(speakerName, profiles.cardFor(player, speakerName), ""),
                ChatLine.receiver(player.name, profiles.cardFor(player, player.name), ChatLine.msgCommand(speakerName)),
                delivered = true,
                ChatLine.body(body),
                // The **cat**, not the player. Pointing this at the player handed the reader a
                // command that whispers themselves, which is what "reply" must never do.
                replyCommand = ChatLine.replyCommand(speakerName, record.code),
                anchorCode = record.code,
                anchorHover = journal.hoverText(player, record),
                refCode = journal.refCode(record),
                refHover = journal.refHover(player, record),
                origin = LineOrigin(LineOrigin.Kind.CAT, speakerId, senderName),
                text = record.body,
            )
        }
    }

    /**
     * A player talking to a system speaker.
     *
     * ## Why this runs the **whole** pipeline
     *
     * This used to call [ModerationGate.decide] on its own, under a comment calling it "the same
     * gate, the same rules" and "the single most important line in the file". The rules were not the
     * same. The gate **judges**; it does not redact. Asked for `REDACT` it returns `replacement = null`
     * together with the **original text**, because deciding what to do with a word and actually
     * replacing it are two different jobs — and the second one lives in [RuleSet.redact], which was
     * reached from nowhere except [ChatPipeline]. So `/meo msg <cat>` was a free pass around every
     * literal and regex filter in the config, and the comment that denied it is what kept it there:
     * the line looked load-bearing and load-bearing is exactly what nobody re-audits.
     *
     * Routing the line through [pipeline] closes it structurally instead of by remembering: there is
     * now one way into the filters and both private channels use it, so the next filter added is
     * applied in both places by default rather than in one of them after somebody reports it.
     *
     * **The price, chosen deliberately**: a whisper now also gets emoji and markup, exactly like
     * public chat. Two channels that cannot drift are worth more than two that happen to agree today,
     * and the alternative — remembering to call `redact` at every new call site — is the thing that
     * just failed.
     *
     * @return true when somebody answered; false means the caller must refuse it visibly.
     */
    fun directMessage(sender: UUID, senderName: String, speakerId: String, text: String): Boolean {
        val outcome = pipeline.process(ChatMessage(sender, "dm:$speakerId", text, System.currentTimeMillis()))
        if (outcome is Outcome.Drop) return false
        // A leading `#k3f9` answers another message; it is not part of this one. Parsing after the
        // pipeline is safe: `#` is not markup and normalisation only ever trims the far end.
        val reference = Reference.parse((outcome as Outcome.Send).text)
        val body = reference.body
        val sink = sinks[speakerId]
        val handled = try {
            sink?.onDirectMessage(sender, speakerId, body) ?: false
        } catch (error: Exception) {
            onLog("speaker sink '$speakerId' failed", error); false
        }
        if (handled) {
            // Recorded so a player can look up what a cat said to them, and when.
            journal.record(
                LineOrigin(LineOrigin.Kind.CAT, speakerId, senderName),
                body,
                reference.anchor,
                recipientId = sender,
            )
            // A sink that answers *synchronously* — the helpdesk — replies before this returns, so
            // its own line is the receipt and echoing the question would double it up.
            //
            // A sink that answers later owes the player something in the meantime, and silence
            // reads as a lost message: they typed, nothing appeared, and seconds later an answer
            // arrived that they had no visible way of having prompted. So the question is echoed at
            // the moment it is accepted, which is also what a player-to-player whisper does.
            if (sink is ReplyCapable) echoToSystemSpeaker(sender, senderName, speakerId, body, reference.anchor)
            host.events.publish(DirectMessageToSystem(sender, speakerId, System.currentTimeMillis()))
        }
        return handled
    }

    /**
     * The player's own line to a system speaker: `✉ me -> Cat ✔ text`.
     *
     * Both the name and the reply shortcut target the **cat**, because the cat is the party that
     * will answer. [renderPrivate] resolves this per reader for two players; here the reader is
     * always the sender, so there is only one answer to give.
     */
    private fun echoToSystemSpeaker(sender: UUID, senderName: String, speakerId: String, body: String, anchor: String?) {
        host.mainThread {
            val player = Bukkit.getPlayer(sender) ?: return@mainThread
            val speakerName = host.messages.speaker(player, speakerId)
            val record = journal.record(
                LineOrigin(LineOrigin.Kind.PLAYER, senderName, speakerName),
                body,
                anchor,
                recipientId = sender,
            )
            host.messages.privateMessage(
                player,
                ChatLine.who(senderName, profiles.cardFor(player, senderName), ""),
                ChatLine.receiver(speakerName, profiles.speakerCard(player, speakerName, host.messages.speakerSummary(player, speakerId)), ""),
                delivered = true,
                ChatLine.body(body),
                replyCommand = ChatLine.replyCommand(speakerName, record.code),
                anchorCode = record.code,
                anchorHover = journal.hoverText(player, record),
                refCode = journal.refCode(record),
                refHover = journal.refHover(player, record),
                origin = LineOrigin(LineOrigin.Kind.PLAYER, senderName, speakerName),
                text = record.body,
            )
        }
    }

    /**
     * Player-to-player delivery.
     *
     * Online means pushed straight into chat — storing it too would make the reader read it twice.
     * Offline means the mailbox, because otherwise it is simply lost.
     *
     * The same line goes to sender and recipient so the sender can re-read what they just wrote —
     * otherwise a mis-typed private message is unrecoverable. Only the recipient's copy is a
     * delivery; the sender's is an echo.
     *
     * Because both sides read the same line, the reply shortcut is resolved **per reader** in
     * [renderPrivate]. Sharing one command across both is what made the reply click hand a player a
     * whisper to themselves.
     *
     * The whole pipeline runs here, for the same reason [directMessage] uses it — and this used to be
     * the larger of the two holes, because a player-to-player whisper is the private channel players
     * actually reach for. The gate only judged; the redaction that makes a filter a filter lived in
     * [ChatPipeline] alone. Same fix, same reason (15 §5).
     */
    fun postToPlayer(sender: UUID, senderName: String, target: Participant.Player, text: String): Delivery {
        val now = System.currentTimeMillis()
        val outcome = pipeline.process(ChatMessage(sender, "dm:${target.handle}", text, now))
        if (outcome is Outcome.Drop) {
            return Delivery.Refused(if (isMute(outcome.reason)) Delivery.Reason.MUTED else Delivery.Reason.BLOCKED)
        }
        // A leading `#k3f9` answers another message; it is not part of this one.
        val reference = Reference.parse((outcome as Outcome.Send).text)
        val body = reference.body
        // Clicking a message prefills `/meo msg Alice #k3f9 ` — the code alone is a **reference**, not
        // a message. Sending it unchanged would deliver a line with no words at all, and on the sender's
        // own copy that reads as their own reply having vanished. Say so instead of storing the gap.
        if (body.isBlank()) return Delivery.Refused(Delivery.Reason.EMPTY)
        val senderSide = Bukkit.getPlayer(sender)
        // One code for the whole message. Minting it here rather than at delivery is what keeps the
        // sender's copy and the recipient's copy pointing at the same record.
        val record = journal.record(
            LineOrigin(LineOrigin.Kind.PLAYER, senderName, target.handle),
            body,
            reference.anchor,
            authorId = sender,
            recipientId = target.uuid,
            atEpochMillis = now,
        )

        val online = Bukkit.getPlayer(target.uuid)
        if (online == null) {
            val mail = mailbox.store(sender, senderName, target.uuid, body, now)
                ?: return if (sender == target.uuid) Delivery.Refused(Delivery.Reason.SELF)
                    else Delivery.Refused(Delivery.Reason.RATE_LIMIT)
            store?.insert(mail, target.uuid)
            store?.trim(target.uuid, settings.mailCapacity)
            senderSide?.let { renderPrivate(it, senderName, target.handle, body, delivered = false, record = record) }
            return Delivery.Stored(target.handle, mailbox.unreadCount(target.uuid))
        }
        // Replying clears the unanswered mark on whatever they left, so their mailbox can settle.
        if (mailbox.markReplied(sender, target.uuid).isNotEmpty()) store?.markReplied(sender, target.uuid)
        renderPrivate(online, senderName, target.handle, body, delivered = true, record = record)
        senderSide?.let { renderPrivate(it, senderName, target.handle, body, delivered = true, record = record) }
        return Delivery.Pushed(target.handle)
    }

    /**
     * One private line, `✉ <发件人> -> <收件人> ✔ 消息`.
     *
     * **The reply shortcut is resolved per reader**, because both sides read the same line. Only the
     * recipient gets it: pointing it at [from] gives the reader something to answer, and the reader's
     * own name is the other half of the line. For the sender that same command would be
     * `/meo msg <自己>`, so it is left empty — [ChatLine.who] and the trailing button both drop the click
     * rather than offering a shortcut to whisper oneself.
     *
     * The receiver's name is never a reply target for either side, so it only carries a hover.
     */
    private fun renderPrivate(
        viewer: Player,
        from: String,
        to: String,
        body: String,
        delivered: Boolean,
        record: Record,
    ) {
        // Both halves are resolved per reader. `from` is the reader's own name on the sender's copy, so
        // it gets no click at all rather than one pointing at themselves.
        val iAmSender = viewer.name.equals(from, ignoreCase = true)
        val other = if (iAmSender) to else from
        val msg = if (iAmSender) "" else ChatLine.msgCommand(from)
        val reply = ChatLine.replyCommand(other, record.code)

        host.messages.privateMessage(
            viewer,
            ChatLine.who(from, profiles.cardFor(viewer, from), msg),
            // The receiver is the one name on this line the sender can always talk to. On the
            // recipient's copy it is their own name, so it goes quiet — `/meo msg <自己>` is a dead end.
            ChatLine.receiver(to, profiles.cardFor(viewer, to), if (iAmSender) ChatLine.msgCommand(to) else ""),
            delivered,
            ChatLine.body(body),
            reply,
            record.code,
            journal.hoverText(viewer, record),
            refCode = journal.refCode(record),
            refHover = journal.refHover(viewer, record),
            LineOrigin(LineOrigin.Kind.PLAYER, from, to),
            record.body,
        )
    }

    fun mailbox(): Mailbox = mailbox

    fun mailboxOf(player: UUID): List<Mail> = mailbox.all(player)

    fun markMailRead(player: UUID, ids: Set<UUID>): List<Mail> {
        val changed = mailbox.markRead(player, ids)
        if (changed.isNotEmpty()) store?.markRead(player, ids)
        return changed
    }

    fun unread(player: UUID): Int = mailbox.unreadCount(player)

    // ── directory ───────────────────────────────────────────────────────────────────────────

    fun find(input: String): Participant? = directory.find(input)

    fun speakers(): List<Participant.System> = directory.speakerIds().mapNotNull { directory.speaker(it) }

    fun complete(prefix: String, limit: Int) = directory.complete(prefix, SpeakerDirectory(host).onlinePlayers(), limit)

    fun speakerCount(): Int = directory.speakerIds().size

    fun ruleCount(): Int = rules.filters.size

    fun auditCapacity(): Int = settings.auditCapacity

    /** Machine-readable one-liner for the startup log. Player-facing text goes through the language file. */
    fun describe(): String = "speakers=${speakerCount()} rules=${ruleCount()} audit=${auditCapacity()}"

    private fun refreshAnswerable() = synchronized(this) {
        directory = directory.withAnswerable(sinks.keys.toSet())
    }

    private fun view(message: ChatMessage) = ChatMessageView(
        player = message.player,
        channel = message.channel,
        rawText = message.rawText,
        atEpochMillis = message.atEpochMillis,
        risk = risk.risk(message.player),
    )
}
