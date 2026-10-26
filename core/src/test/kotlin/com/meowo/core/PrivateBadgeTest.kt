package com.meowo.core

import com.meowo.platform.Messages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * The private badge: it **leads** the name, and on a player's message it is a shortcut.
 *
 * Two properties are easy to regress and neither shows up in a screenshot:
 * the badge must come before the name, not after it; and the colour code must be deserialised
 * rather than pushed in as literal `§7`, or the client renders "§7✉" instead of a grey glyph.
 *
 * Lives in `core` because it needs the **real** language bundle — `Messages.readBundle` falls back
 * to `plugin.getResource(...)`, which only exists in the module that actually ships `lang/`.
 */
class PrivateBadgeTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var messages: Messages

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        // One plugin for both bundles: a second `TestPlugin` makes Gradle emit a duplicate class
        // file, which on this iCloud checkout surfaces as "TestPlugin 2" (08 §1).
        val plugin = MockBukkit.load(TestPlugin::class.java)
        messages = Messages(plugin).apply { load("zh_CN") }
        english = Messages(plugin).apply { load("en_US") }
    }

    private lateinit var english: Messages

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    @Test fun `the badge leads the name rather than trailing the message`() {
        val name = messages.privateName(null, "Alice", "/meo msg Alice ")
        assertEquals("✉ Alice", plain(name).trim())
    }

    @Test fun `a bare § never reaches the client`() {
        // Regression: `Component.text(text(...))` puts the colour in as literal characters, so the
        // player would read "§7✉" instead of a grey envelope.
        val shown = plain(messages.privateName(null, "Alice", "/meo msg Alice "))
        assertTrue(!shown.contains("§"), "a raw colour code leaked into the badge: $shown")
    }

    @Test fun `the badge is clickable and prefills a reply`() {
        // Adventure 4 models the click as a payload, so compare against the factory that built it
        // rather than an old `SuggestCommand` subclass that no longer exists.
        val expected = ClickEvent.suggestCommand("/meo msg Alice ")
        val click = messages.privateName(null, "Alice", "/meo msg Alice ").children().first().clickEvent()
        assertNotNull(click, "the badge must be an action, not decoration")
        assertEquals(expected, click, "the badge should prefill the reply box")
        assertEquals(expected, ClickEvent.suggestCommand("/meo msg Alice "))
    }

    @Test fun `each mailbox state gets its own badge`() {
        val expected = mapOf(
            "mail-mark-new" to "✉",
            "mail-mark-read" to "✉",
            "mail-mark-replied" to "✉✓",
        )
        for ((key, glyph) in expected) {
            val shown = plain(messages.privateName(null, "Alice", "/meo msg Alice ", key)).trim()
            assertEquals("$glyph Alice", shown, "badge '$key' is wrong")
        }
    }

    @Test fun `a system speaker leads with the same badge but is not clickable`() {
        val player = server.addPlayer("Viewer")
        val systemBadge = messages.component(player, "mail-badge")
        assertEquals("✉", plain(systemBadge).trim())
        assertNull(systemBadge.clickEvent(), "there is nobody to reply to, so it must not click")
    }

    @Test fun `the English bundle ships the same markers`() {
        assertEquals("✉ Bob", plain(english.privateName(null, "Bob", "/meo msg Bob ")).trim())
        assertEquals("✉✓ Bob", plain(english.privateName(null, "Bob", "/meo msg Bob ", "mail-mark-replied")).trim())
    }
}