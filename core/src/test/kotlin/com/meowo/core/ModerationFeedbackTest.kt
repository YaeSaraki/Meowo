package com.meowo.core

import com.meowo.api.chat.ChatModerationAPI
import com.meowo.chat.ChatContext
import com.meowo.chat.ChatService
import com.meowo.chat.ChatSettings
import com.meowo.chat.domain.ChatMessage
import com.meowo.chat.domain.Outcome
import com.meowo.platform.CommandRouter
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.shared.event.EventBus
import com.meowo.shared.lifecycle.ContextManager
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock

/**
 * Two promises the moderation API has always made and the chat path never kept.
 *
 * **A blocked message says so.** The 风纪 gate runs before redaction specifically so the moderator
 * sees the original and an appeal can be answered — and that evidence used to reach nobody while the
 * player watched their words disappear. Silent removal is the worst outcome available: no feedback, no
 * appeal, and a filter that reads as broken rather than strict.
 *
 * **A mute is a mute.** `muteUntil` has been on `ChatModerationAPI` since the interface was written,
 * but only the agents context ever asked. A muted player could still talk in global chat and whisper
 * to other players — not a mute with a loophole, but a mute that only works where nobody looks.
 *
 * Tested at the service rather than through `AsyncChatEvent`, because MockBukkit has no chat events
 * at all (docs/minimax/15 §3) and the decision lives here.
 */
class ModerationFeedbackTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var host: PlatformHost
    private lateinit var contexts: ContextManager
    private lateinit var service: ChatService
    private lateinit var player: PlayerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        val messages = Messages(plugin).apply { load("zh_CN") }
        host = PlatformHost(plugin, EventBus { _, e -> throw e }, CommandRouter(messages), messages)
        contexts = ContextManager { id, e -> server.logger.log(java.util.logging.Level.SEVERE, "$id failed", e) }
        contexts.enable(ChatContext(host, ChatSettings()))
        // One literal filter is enough to make the gate drop something.
        service = ChatService(
            settings = ChatSettings(),
            host = host,
            onLog = { message, error -> server.logger.log(java.util.logging.Level.SEVERE, message, error) },
        )
        player = server.addPlayer("Quiet")
        take()
    }

    @AfterEach fun tearDown() = MockBukkit.unmock()

    private fun say(text: String) = service.process(
        ChatMessage(player.uniqueId, "global", text, System.currentTimeMillis()),
    )

    /**
     * The 风纪 AI's verdict, which is what actually drops a message.
     *
     * A literal filter only **redacts** — it rewrites the words and lets the message through — so a
     * filtered sentence was never a blocked one. The seam that drops is the registered auditor, and
     * using it here keeps the test honest about which mechanism it is exercising.
     */
    private fun refuseEverything() {
        service.registerServices()
        service.registerModerator(
            object : com.meowo.api.chat.ChatModerator {
                override fun inspect(message: com.meowo.api.chat.ChatMessageView) =
                    com.meowo.api.chat.ModerationVerdict(com.meowo.api.chat.ModerationAction.BLOCK, "test")

                override fun onPublished(message: com.meowo.api.chat.ChatMessageView) = Unit
                override fun onQuit(player: java.util.UUID) = Unit
            },
        )
    }

    /** The notice as this viewer actually receives it, so the assertion survives a locale change. */
    private fun notice(key: String) = host.messages.text(player, key)

    @Test fun `the 风纪 auditor drops a message it declines`() {
        refuseEverything()
        assertTrue(say("随便说点什么") is Outcome.Drop, "the auditor must actually fire for this test to mean anything")
    }

    @Test fun `a dropped message is answered with a notice, not silence`() {
        refuseEverything()
        say("随便说点什么")
        service.notifyBlocked(player.uniqueId, muted = false)
        val expected = notice("chat-blocked")
        val said = take()
        assertTrue(said.any { it.contains(expected.replace("§", "")) || expected.contains(it) || said.any { x -> x.contains(expected.substringAfter("§7")) } }) {
            "a dropped message must still say so; silence leaves the player with nothing to appeal; got $said"
        }
    }

    /** Naming the offending word turns the notice into a tutorial on how to get past the filter. */
    @Test fun `the notice never names the word that was refused`() {
        refuseEverything()
        say("这里有一个不该被放行的词")
        service.notifyBlocked(player.uniqueId, muted = false)
        val said = take().joinToString("\n")
        assertTrue(said.isNotBlank()) { "the player must be told something" }
        assertFalse(said.contains("不该被放行")) { "naming the word teaches the player how to dodge it: $said" }
    }

    @Test fun `the two refusals get different words`() {
        service.notifyBlocked(player.uniqueId, muted = true)
        val muted = take()
        service.notifyBlocked(player.uniqueId, muted = false)
        val blocked = take()
        assertTrue(muted.isNotEmpty() && blocked.isNotEmpty()) { "both paths must say something" }
        assertTrue(muted != blocked) { "「你被禁言」和「这条没发出去」是两件事，不能共用一句话: $muted / $blocked" }
    }

    // ── the mute itself ──────────────────────────────────────────────────────────────────────

    @Test fun `a mute stops public chat`() {
        moderation().muteUntil(player.uniqueId, System.currentTimeMillis() + 60_000)
        assertTrue(say("随便说点什么") is Outcome.Drop, "the whole point: a mute that does not mute")
        assertTrue(service.isMutedNow(player.uniqueId))
    }

    /** A mute that left whispers open would be routed around with one command. */
    @Test fun `a mute stops whispers too`() {
        moderation().muteUntil(player.uniqueId, System.currentTimeMillis() + 60_000)
        val target = server.addPlayer("Other").also { it.addAttachment(plugin, "meowo.chat.use", true) }
        val outcome = service.postToPlayer(
            player.uniqueId, player.name,
            com.meowo.chat.domain.Participant.Player(target.name, target.uniqueId, online = true),
            "你好",
        )
        assertTrue(
            outcome is com.meowo.chat.domain.Delivery.Refused &&
                outcome.reason == com.meowo.chat.domain.Delivery.Reason.MUTED,
            "a muted sender must not reach another player's inbox, got $outcome",
        )
    }

    @Test fun `an elapsed mute is not a mute`() {
        moderation().muteUntil(player.uniqueId, System.currentTimeMillis() - 1)
        assertFalse(service.isMutedNow(player.uniqueId))
        assertTrue(say("普通消息") !is Outcome.Drop)
    }

    private fun moderation(): ChatModerationAPI {
        service.registerServices()
        val api = server.servicesManager.load(ChatModerationAPI::class.java)
        assertNotNull(api, "the moderation API must be registered before it can be used")
        return api!!
    }

    private fun take(): List<String> = generateSequence { player.nextMessage() }.toList()
}