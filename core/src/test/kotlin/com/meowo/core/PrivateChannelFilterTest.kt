package com.meowo.core

import com.meowo.api.chat.ChatModerationAPI
import com.meowo.api.chat.SystemSpeakerSink
import com.meowo.chat.ChatContext
import com.meowo.chat.ChatSettings
import com.meowo.platform.CommandRouter
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.shared.event.EventBus
import com.meowo.shared.lifecycle.ContextManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID

/**
 * `/meo msg` walked through the moderation **gate** and stopped there, and the gate does not redact.
 *
 * [com.meowo.chat.domain.ModerationGate] judges: asked for `REDACT` it returns `replacement = null`
 * alongside the **original** text, because deciding what to do with a word and swapping it out are two
 * different jobs. The swap lives in `RuleSet.redact`, which was reached from `ChatPipeline` and from
 * nowhere else — so every literal and regex filter in the config was a public-chat-only rule. A player
 * who wanted a word on screen typed it into a whisper instead, and both private channels obliged.
 *
 * The old comment on that call site called it "the same gate, the same rules" and "the single most
 * important line in the file", which is worth keeping in mind: a line that reads as load-bearing is
 * a line nobody re-audits, and it was wrong in the exact way its comment denied.
 *
 * These go through the `/meo msg` command rather than reaching into the service, because the service
 * is internal to the chat context and the player-facing route is where the claim has to hold.
 */
class PrivateChannelFilterTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var host: PlatformHost
    private lateinit var contexts: ContextManager
    private lateinit var me: PlayerMock
    private lateinit var them: PlayerMock

    /** Distinctive enough that finding it anywhere in a rendered line means the filter missed. */
    private val filtered = "违禁词"

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        val messages = Messages(plugin).apply { load("zh_CN") }
        host = PlatformHost(plugin, EventBus { _, e -> throw e }, CommandRouter(messages), messages)
        contexts = ContextManager { id, e -> server.logger.log(java.util.logging.Level.SEVERE, "$id failed", e) }
        contexts.enable(ChatContext(host, ChatSettings(literalFilters = listOf(filtered))))
        me = server.addPlayer("meov0")
        them = server.addPlayer("Bystander")
        for (perm in listOf("meowo.use", "meowo.chat.use")) me.addAttachment(plugin, perm, true)
        // The helpdesk owns every cat at start; take one so a whisper to it reaches a sink at all.
        claimSink(object : SystemSpeakerSink {
            override fun onDirectMessage(sender: UUID, speakerId: String, text: String) = true
        })
        drain(me)
        drain(them)
    }

    @AfterEach
    fun tearDown() {
        contexts.close()
        MockBukkit.unmock()
    }

    /** Records what the cat was actually asked, which is the only place the filter's output shows up. */
    private class RecordingSink : SystemSpeakerSink {
        val seen = mutableListOf<String>()
        override fun onDirectMessage(sender: UUID, speakerId: String, text: String): Boolean {
            seen += text
            return true
        }
    }

    private fun claimSink(sink: SystemSpeakerSink) =
        server.servicesManager.load(ChatModerationAPI::class.java)!!.claimSpeaker("economy", sink)

    private fun whisper(to: String, text: String) {
        host.commands.onCommand(me, mockCommand("meowo"), "meowo", arrayOf("msg", to, text))
        server.scheduler.performTicks(3)
    }

    private fun mockCommand(name: String): org.bukkit.command.Command =
        object : org.bukkit.command.Command(name) {
            override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>) = true
        }

    private fun drain(who: PlayerMock): List<String> = generateSequence { who.nextMessage() }.toList()

    /** The reply code the sender's own copy carries; failing here beats skipping the test quietly. */
    private fun code(sentToMe: String): String =
        Regex("#[a-z0-9]+").find(sentToMe)?.value
            ?: org.junit.jupiter.api.Assertions.fail<String>("the sender's own copy must carry a reply code: $sentToMe")

    /**
     * The larger of the two holes: a player-to-player whisper is the private channel players
     * actually reach for.
     *
     * Both directions are asserted on purpose. Checking only for `***` would pass just as happily on
     * a line that never arrived at all, and the failure this guards against is exactly the kind that
     * delivers the word.
     */
    @Test fun `a filtered word is masked before another player ever sees it`() {
        whisper("Bystander", "我们去 $filtered 吧")
        val received = drain(them).joinToString("\n")
        assertTrue(received.isNotBlank()) { "the whisper must actually be delivered for this test to mean anything" }
        assertFalse(received.contains(filtered)) { "the recipient read the raw word: $received" }
        assertTrue(received.contains("***")) { "the word should have been masked, not vanished: $received" }
    }

    /** The other half: the same leak through `/meo msg <cat>`, read at the sink rather than on screen. */
    @Test fun `a filtered word is masked before a cat is asked about it`() {
        val sink = RecordingSink()
        claimSink(sink)
        drain(me)
        whisper("EconCat", "我们去 $filtered 吧")
        assertEquals(1, sink.seen.size, "the cat should have been asked exactly once: ${sink.seen}")
        assertFalse(sink.seen[0].contains(filtered)) { "the cat was handed the raw word: ${sink.seen}" }
        assertTrue(sink.seen[0].contains("***")) { "the word should have been masked: ${sink.seen}" }
    }

    /**
     * The obvious way to "fix" the filter leak — call `redact` and nothing else — would strip the
     * anchor out of every reply, because a leading `#zrme` is not part of the message body. Pin that
     * the full pipeline still leaves replies working.
     *
     * The count is the assertion, not the absence: a reply line **deliberately** shows the code it
     * answers as a badge in front of the words ([ChatJournal.refCode] is `record.anchor`, rendered by
     * `ChatLine.private`). One occurrence is that badge; a second would be the anchor still sitting
     * inside the body, which is the exact failure a naive redaction-only fix would produce.
     */
    @Test fun `replying by code still delivers the message without the code`() {
        whisper("Bystander", "第一句话")
        val mine = drain(me).joinToString("\n")
        val code = code(mine)

        drain(them)
        whisper("Bystander", "$code 第二句话")
        val received = drain(them).joinToString("\n")
        assertTrue(received.contains("第二句话"), "the reply body must arrive: $received")
        assertEquals(
            1, Regex(Regex.escape(code)).findAll(received).count(),
            "the code should appear once, as the reference badge, and never again inside the body: $received",
        )
    }

    /** A reference on its own is still refused rather than stored as an empty message. */
    @Test fun `a code with no words behind it is refused`() {
        whisper("Bystander", "第一句话")
        val code = code(drain(me).joinToString("\n"))
        drain(them)
        whisper("Bystander", code)
        val received = drain(them).joinToString("\n")
        assertFalse(
            received.contains("✉"),
            "an empty reply must not be delivered as a line with no words: $received",
        )
    }

    // ── what the reader actually receives ─────────────────────────────────────────────────────

    /**
     * The other half of the same shape: a whisper is the channel where a `§` **always** reached a
     * legacy reader.
     *
     * Public chat only deserialises when an emoji already changed the string, which is why the old
     * `§c` test needed a `` `sad` `` in it. `renderPrivate` deserialised every body unconditionally,
     * so `/meo msg Bob §c` was enough, with no decoration at all.
     *
     * Read from the **component tree**, not the rendered string: MockBukkit's `nextMessage()`
     * serialises back to legacy, so `§c` would appear in the text whether or not it was honoured.
     *
     * And scoped to the **body**, because the rest of the line is ours and is legitimately coloured
     * — the badge is grey, the tick is green, the `#code` is dark grey. A whole-tree "is anything
     * coloured" assertion fails on the plugin's own chrome and proves nothing either way.
     */
    @Test fun `a section escape in a whisper is shown as the characters typed`() {
        whisper("Bystander", "§c管理员")
        val body = bodyCarrying(them, "管理员")
        assertTrue(body.isNotEmpty()) { "the whisper must be delivered for this test to mean anything" }
        assertFalse(
            body.any(::isColoured),
            "a player put colour in their own private message: $body",
        )
        assertTrue(
            body.any { plainText(it).contains("§c") },
            "the sign should survive as text — vanilla would have shown it too: $body",
        )
    }

    /**
     * The other end of the same fix.
     *
     * `**粗体**` reached players as the literal characters `[b]粗体[/b]` because the pipeline's
     * BBCode was handed to a legacy `§` reader. Asserting on the string alone would pass on a
     * component that merely contains those characters, so this reads the decoration.
     */
    @Test fun `bold typed in a whisper reaches the other player as bold`() {
        whisper("Bystander", "**很重要**")
        val body = bodyCarrying(them, "很重要")
        assertTrue(body.isNotEmpty()) { "the whisper must be delivered for this test to mean anything" }
        val shown = body.map(::plainText).joinToString("")
        assertFalse(shown.contains("[b]"), "the player read the markup itself: $shown")
        assertTrue(body.any(::isBold), "`**很重要**` arrived as literal `[b]很重要[/b]`: $shown")
    }

    /** The leaf components that actually carry [needle] — never the chrome around them. */
    private fun bodyCarrying(who: PlayerMock, needle: String): List<Component> {
        val out = mutableListOf<Component>()
        while (true) {
            val c = who.nextComponentMessage() ?: break
            leavesOf(listOf(c)).filterTo(out) { plainText(it).contains(needle) }
        }
        return out
    }

    private fun leavesOf(roots: List<Component>): List<Component> = roots.flatMap { leaf ->
        if (leaf.children().isEmpty()) listOf(leaf) else leavesOf(leaf.children())
    }

    private fun plainText(c: Component) = PlainTextComponentSerializer.plainText().serialize(c)

    private fun isColoured(c: Component): Boolean =
        c.color() != null || c.children().any(::isColoured)

    private fun isBold(c: Component): Boolean =
        c.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE || c.children().any(::isBold)
}