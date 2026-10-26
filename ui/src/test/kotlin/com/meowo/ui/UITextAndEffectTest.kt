package com.meowo.ui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two halves of the renderer that do not need a server.
 *
 * Text because a config file mixes language keys with literal labels, and the action because the one real
 * interpretation in the module lives there: `@back` means "previous page" on a list and "up one level"
 * everywhere else.
 */
class UITextAndEffectTest {

    private val plain = PlainTextComponentSerializer.plainText()
    private fun text(component: Component) = plain.serialize(component)

    private fun keys(vararg pairs: Pair<String, String>) = pairs.toMap()

    @Test fun `a language key is looked up, a label is taken literally`() {
        val lookup = keys("pomodoro-action-start" to "开始专注")
        assertEquals("开始专注", text(UIText.resolve("pomodoro-action-start", NamedTextColor.GRAY) { Component.text(lookup[it]!!) }))
        assertEquals("我的标签", text(UIText.resolve("我的标签", NamedTextColor.GRAY) { Component.text("WRONG") }))
    }

    @Test fun `a key is recognised by shape, not by whether it happens to resolve`() {
        assertTrue(UIText.isKey("pomodoro-action-start"))
        assertFalse(UIText.isKey("pomodoro action start"), "a space makes it a label")
        assertFalse(UIText.isKey("&d◆ 开始"), "colour codes make it a label")
        assertFalse(UIText.isKey("   "))
    }

    @Test fun `literal text reads ampersand codes as colour`() {
        val component = UIText.resolve("&d◆ 开始", NamedTextColor.GRAY) { Component.text("WRONG") }
        assertEquals("◆ 开始", text(component), "the code must not survive into the tooltip")
    }

    @Test fun `a blank lore line is dropped rather than becoming an empty tooltip`() {
        assertNull(UIText.resolveLore("   ", NamedTextColor.GRAY) { Component.text("x") })
        assertNull(UIText.resolveLore("", NamedTextColor.GRAY) { Component.text("x") })
    }

    @Test fun `a title resolves to legacy text, since that is what a window title takes`() {
        assertEquals("番茄钟", UIText.toLegacy("pomodoro-main-title") { "番茄钟" })
        assertEquals("\u00A7d◆ 开始", UIText.toLegacy("&d◆ 开始") { "WRONG" }, "a person types &, the title wants §")
    }

    @Test fun `a command action runs the command the config named`() {
        val screen = screenStatic()
        assertEquals(UIEffect.Command("pomodoro start"), UIEffect.of(UIAction.parse("pomodoro start"), screen))
    }

    @Test fun `back means the previous screen, except on a list where it means the previous page`() {
        // On a function page the way back is the caller's business.
        assertEquals(UIEffect.Back, UIEffect.of(UIAction.parse("@back"), screenStatic()))
        // On a list, "back" from a list of things means the page before it.
        assertEquals(UIEffect.PagePrevious, UIEffect.of(UIAction.parse("@back"), screenList()))
    }

    @Test fun `the built-ins map to their effects`() {
        val screen = screenStatic()
        assertEquals(UIEffect.Close, UIEffect.of(UIAction.parse("@close"), screen))
        assertEquals(UIEffect.MainMenu, UIEffect.of(UIAction.parse("@main-menu"), screen))
        assertEquals(UIEffect.PageNext, UIEffect.of(UIAction.parse("@page-next"), screen))
        assertEquals(UIEffect.Confirm, UIEffect.of(UIAction.parse("@confirm"), screen))
        assertEquals(UIEffect.Cancel, UIEffect.of(UIAction.parse("@cancel"), screen))
    }

    private fun screenStatic() = UIScreen("s", "t", 6, emptyMap())

    private fun screenList() = UIScreen("l", "t", 6, emptyMap(), contentSlots = listOf(10, 11))
}
