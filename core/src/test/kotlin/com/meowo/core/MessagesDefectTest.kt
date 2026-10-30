package com.meowo.core

import com.meowo.platform.Messages
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.bukkit.plugin.java.JavaPlugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * The three defects the architecture review found in the language module.
 *
 * Each of them is a case where the plugin renders something, the build stays green, and a player
 * reads the wrong thing. That is the failure mode this module has to be hardest against, so the
 * three are pinned here rather than left to a scan of the source.
 */
class MessagesDefectTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
    }

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    private fun messages(): Messages = Messages(plugin).apply { load("zh_CN", "server") }

    // ────────────────────────────────────────────────────────────────────────────
    // B3 · a substituted value must never be scanned again
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * The home name is player-supplied and reaches a message as `{0}`. If the substitution ran
     * over the string it had already produced, a home called `{1}` would be replaced a second
     * time — and the *price* would land in the name slot.
     */
    @Test fun `a value that looks like a placeholder is not substituted again`() {
        val result = messages().text(null, "home-confirm-chat", "{1}", "12.50", "10")

        assertTrue(
            result.contains("{1}"),
            "the home name must survive verbatim: $result",
        )
        assertFalse(
            result.contains("12.50 §7要"),
            "the fee must not appear in the name slot: $result",
        )
        assertTrue(
            result.contains("§6§l12.50§7"),
            "the fee must still be rendered in the fee slot: $result",
        )
    }

    /** The same rule for a value that looks like the *earlier* placeholder. */
    @Test fun `a value that looks like an earlier placeholder is not substituted again`() {
        val result = messages().text(null, "home-confirm-chat", "{0}", "12.50", "10")

        assertTrue(result.contains("{0}"), "the name must survive verbatim: $result")
        assertTrue(result.contains("12.50"), "the fee must still be rendered: $result")
    }

    /** A value carrying a `}` cannot break out of its slot either. */
    @Test fun `a value containing braces does not corrupt the other slots`() {
        val result = messages().text(null, "home-confirm-chat", "a}b{c", "12.50", "10")

        assertTrue(result.contains("a}b{c"), "the name must survive verbatim: $result")
        assertTrue(result.contains("12.50"), "the fee must still be rendered: $result")
    }

    // ────────────────────────────────────────────────────────────────────────────
    // B2 · a missing speaker summary is empty, not a raw key
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * The summary is looked up as `speaker-summary-<id>`, a key no scan can see. A speaker id
     * without a summary therefore has to yield **nothing**: the directory shows the summary
     * verbatim, and a raw key is not a sentence.
     */
    @Test fun `a speaker without a summary yields an empty string`() {
        val result = messages().speakerSummary("a-speaker-that-does-not-exist")

        assertEquals("", result, "an absent summary must be empty, not the key it was looked up under")
    }

    /** A speaker that *does* have a summary still gets it. */
    @Test fun `a speaker with a summary still gets it`() {
        val result = messages().speakerSummary("economy")

        assertTrue(result.isNotBlank(), "a known speaker must keep its summary: '$result'")
        assertFalse(result.contains("speaker-summary-"), "the summary must not be a raw key: '$result'")
    }

    // ────────────────────────────────────────────────────────────────────────────
    // B1 · one key, three contexts, one cat
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * `unavailable` was sent by shop, home and menu alike, and mapped to exactly one cat. The
     * shop's message therefore arrived signed by MenuCat — a cat that has nothing to do with the
     * shop — and the text said the *economy* was disabled, which is a third thing again.
     *
     * Each context now has its own key, so each line carries its own cat and its own reason.
     */
    @Test fun `each context that can be unavailable owns its own message`() {
        val messages = messages()

        assertEquals("shop", messages.speakerOf("shop-unavailable"), "the shop's message must be signed by ShopCat")
        assertEquals("home", messages.speakerOf("home-unavailable"), "the home's message must be signed by NestCat")
        assertEquals("menu", messages.speakerOf("menu-unavailable"), "the menu's message must be signed by MenuCat")
    }

    /** The shared key is gone, so no context can sign another's line by accident. */
    @Test fun `the shared unavailable key no longer routes anywhere`() {
        assertEquals(
            "system",
            messages().speakerOf("unavailable"),
            "the shared key must not survive: a context that still sent it would be signed by MeowCat",
        )
    }

    /**
     * Three contexts, three reasons — and three different reasons.
     *
     * The text names the module that is actually missing, because that is the thing an operator
     * has to turn back on. Two contexts sharing one sentence is how "the economy is disabled"
     * ended up being said by a shop that had nothing to do with the economy.
     */
    @Test fun `each unavailable message names its own reason`() {
        val messages = messages()

        val shop = messages.text(null, "shop-unavailable")
        val home = messages.text(null, "home-unavailable")
        val menu = messages.text(null, "menu-unavailable")

        assertTrue(shop.isNotBlank(), "the shop's message must say something: '$shop'")
        assertTrue(home.isNotBlank(), "the home's message must say something: '$home'")
        assertTrue(menu.isNotBlank(), "the menu's message must say something: '$menu'")

        assertTrue(
            shop != home && shop != menu && home != menu,
            "three contexts, three sentences: '$shop' / '$home' / '$menu'",
        )
    }
}
