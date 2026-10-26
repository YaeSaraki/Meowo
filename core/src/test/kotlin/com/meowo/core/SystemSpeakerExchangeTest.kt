package com.meowo.core

import com.meowo.api.chat.ChatModerationAPI
import com.meowo.api.chat.ReplyCapable
import com.meowo.api.chat.SystemSpeakerSink
import com.meowo.chat.ChatContext
import com.meowo.chat.ChatSettings
import com.meowo.platform.CommandRouter
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.shared.event.EventBus
import com.meowo.shared.lifecycle.ContextManager
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID

/**
 * What a player actually sees when they whisper a cat that answers **later**.
 *
 * Both of these were reported from a live server after the agents context went in: the question
 * never appeared at all, and the reply shortcut offered a command that whispered the player
 * themselves. Neither is visible from the pure domain tests, because both are about the shape of
 * the rendered line rather than about any decision.
 */
class SystemSpeakerExchangeTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var host: PlatformHost
    private lateinit var contexts: ContextManager
    private lateinit var player: PlayerMock
    private val playerName = "meov0"

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        val messages = Messages(plugin).apply { load("zh_CN") }
        host = PlatformHost(plugin, EventBus { _, e -> throw e }, CommandRouter(messages), messages)
        contexts = ContextManager { id, e -> server.logger.log(java.util.logging.Level.SEVERE, "$id failed", e) }
        contexts.enable(ChatContext(host, ChatSettings()))
        player = server.addPlayer(playerName)
        for (perm in listOf("meowo.use", "meowo.chat.use")) {
            player.addAttachment(plugin, perm, true)
        }
        take("economy")
    }

    @AfterEach
    fun tearDown() {
        contexts.close()
        MockBukkit.unmock()
    }

    /**
     * A sink that answers later, the way an agent waiting on a model does.
     *
     * [autoAnswer] is the whole point of the class: a sink that answers inside
     * [SystemSpeakerSink.onDirectMessage] must not have its question echoed, and one that answers
     * afterwards must.
     */
    private class LateSink(private val autoAnswer: Boolean) : SystemSpeakerSink, ReplyCapable {
        private var send: ((UUID, String, String, String) -> Unit)? = null
        val replies = mutableListOf<List<String>>()

        override fun onDirectMessage(sender: UUID, speakerId: String, text: String) = true

        override fun attachReply(send: (UUID, String, String, String) -> Unit) {
            this.send = send
            if (autoAnswer) fire(UUID.randomUUID(), "meov0", "economy", "你有 100 块。")
        }

        fun fire(sender: UUID, name: String, id: String, text: String) =
            send?.invoke(sender, name, id, text)
    }

    private fun take(speaker: String) {
        server.servicesManager.load(ChatModerationAPI::class.java)!!
            .claimSpeaker(speaker, object : SystemSpeakerSink {
                override fun onDirectMessage(sender: UUID, speakerId: String, text: String) = true
            }).close()
        drain()
    }

    /**
     * Goes through the command the player actually types, rather than reaching into
     * [com.meowo.chat.ChatService] — which is private to the chat context, and rightly so.
     */
    private fun say(speaker: String, sink: SystemSpeakerSink): String {
        server.servicesManager.load(ChatModerationAPI::class.java)!!.claimSpeaker(speaker, sink)
        drain()
        host.commands.onCommand(player, mockCommand("meowo"), "meowo", arrayOf("msg", "EconCat", "我还有多少钱？"))
        server.scheduler.performTicks(3)
        return generateSequence { player.nextMessage() }.toList().joinToString("\n")
    }

    private fun mockCommand(name: String): org.bukkit.command.Command =
        object : org.bukkit.command.Command(name) {
            override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>) = true
        }

    /**
     * The reported bug. Silence followed by an answer reads as a lost message, so the question has
     * to appear when it is accepted.
     */
    @Test fun `a player sees their own question when the cat answers later`() {
        val said = say("economy", LateSink(autoAnswer = false))
        assertTrue(said.contains("我还有多少钱？"), "the player's own line must appear: $said")
    }

    /**
     * The other reported bug: "reply" offered `/meo msg <the player> `, whispering oneself.
     *
     * Read from the **click events**, not the rendered text. A first version asserted on the
     * plain string and passed with the bug present — the command lives in a `ClickEvent`, so
     * checking the text proved nothing. A mutation run (restoring the old `replyCommand`) is what
     * caught that, and it is why this now walks the component tree.
     */
    @Test fun `no click on the line targets the reader themselves`() {
        val sink = LateSink(autoAnswer = true)
        server.servicesManager.load(ChatModerationAPI::class.java)!!.claimSpeaker("economy", sink)
        drain()
        host.commands.onCommand(player, mockCommand("meowo"), "meowo", arrayOf("msg", "EconCat", "我还有多少钱？"))
        server.scheduler.performTicks(3)

        val parts = mutableListOf<net.kyori.adventure.text.Component>()
        while (true) { val c = player.nextComponentMessage() ?: break; parts += flatten(c) }
        val clicks: List<String> = parts.mapNotNull { c ->
            val click = c.clickEvent() ?: return@mapNotNull null
            if (click.action() != net.kyori.adventure.text.event.ClickEvent.Action.SUGGEST_COMMAND) null
            else (click.payload() as? net.kyori.adventure.text.event.ClickEvent.Payload.Text)?.value()
        }
        assertTrue(clicks.isNotEmpty(), "the line should offer some command")
        for (command in clicks) {
            assertFalse(
                command.contains("msg $playerName "),
                "a click offered a command that whispers the reader: $command (all: $clicks)",
            )
        }
        assertTrue(
            clicks.any { it.contains("EconCat") },
            "the cat should be reachable by clicking: $clicks",
        )
    }

    private fun flatten(component: net.kyori.adventure.text.Component): List<net.kyori.adventure.text.Component> {
        val out = mutableListOf<net.kyori.adventure.text.Component>()
        collect(component, out)
        return out
    }

    private fun collect(component: net.kyori.adventure.text.Component, into: MutableList<net.kyori.adventure.text.Component>) {
        into += component
        for (child in component.children()) collect(child, into)
    }

    @Test fun `a sink that answers synchronously gets no double echo`() {
        val said = say("economy", object : SystemSpeakerSink {
            override fun onDirectMessage(sender: UUID, speakerId: String, text: String) = true
        })
        assertFalse(
            said.contains("我还有多少钱？"),
            "a sink that already replied must not also have the question echoed: $said",
        )
    }

    private fun drain() {
        while (player.nextMessage() != null) { /* discard */ }
    }
}
