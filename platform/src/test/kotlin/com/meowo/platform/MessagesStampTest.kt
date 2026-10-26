package com.meowo.platform

import net.kyori.adventure.text.Component
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.bukkit.plugin.java.JavaPlugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * The line stamper: the archive behind every chat line the plugin sends.
 *
 * The rule worth pinning is that a line which **already carries a code** is not stamped again.
 * The chat context mints the code where the record is written and hands the same code to both
 * sides of a conversation, so stamping it again wrote a second copy of the same sentence into the
 * archive — one per reader, so a private line landed there three times.
 */
class MessagesStampTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
    }

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    private fun messages(): Messages = Messages(plugin)

    private fun send(messages: Messages, anchorCode: String) {
        val sender = server.consoleSender
        messages.privateMessage(
            sender,
            Component.text("Alice"),
            Component.text("Bob"),
            delivered = true,
            body = Component.text("hi"),
            anchorCode = anchorCode,
            origin = LineOrigin(LineOrigin.Kind.PLAYER, "Alice", "Bob"),
        )
    }

    /** A line with no code of its own has nothing in the archive yet, so it is stamped. */
    @Test fun `a line without a code is stamped`() {
        val messages = messages()
        var stamped = 0
        messages.stamper = com.meowo.platform.LineStamper { _, _, _ -> stamped++; Component.empty() }
        send(messages, "")
        assertEquals(1, stamped, "a line with no code has no record yet")
    }

    /** A line that already carries a code has already been recorded. */
    @Test fun `a line that already carries a code is not stamped again`() {
        val messages = messages()
        var stamped = 0
        messages.stamper = com.meowo.platform.LineStamper { _, _, _ -> stamped++; Component.empty() }
        send(messages, "abcd")
        assertEquals(0, stamped, "the record is already written; stamping again duplicates it")
    }
}
