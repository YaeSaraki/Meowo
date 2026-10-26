package com.meowo.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A chat message is a [Component], and the client chose it.
 *
 * Vanilla clients send plain text. A modified one can attach a
 * `ClickEvent.runCommand("/op attacker")` to a single word — and the click handler runs on the
 * **clicker's** client with the **clicker's** permissions, so the attacker needs none of their own.
 * They post an ordinary-looking message, an operator clicks a word, and the operator has op'd them.
 *
 * This was **measured on this class**, not suspected: a hostile body put through [ChatLine.publicLine]
 * came out the other side with `{"click_event":{"action":"run_command","command":"/op attacker"}}`
 * still nested inside it. `clickEvent()` sets one node and leaves every other node as it found it.
 */
class ChatLineInjectionTest {

    /** Serialised the way the server sends it, so the assertion is about what a client receives. */
    private fun wire(c: Component): String = GsonComponentSerializer.gson().serialize(c)

    private fun line(body: Component, replyCommand: String = "/meo msg Mallory #k3f9 ") =
        ChatLine.publicLine(
            displayName = Component.text("Mallory"),
            profile = null,
            body = body,
            anchorCode = "k3f9",
            anchorHover = "hover",
            msgCommand = "/meo msg Mallory ",
            replyCommand = replyCommand,
        )

    private val hostile = Component.text("free ")
        .append(Component.text("CLICK HERE").clickEvent(ClickEvent.runCommand("/op attacker")))
        .append(Component.text(" "))
        .append(Component.text("tip").hoverEvent(HoverEvent.showText(Component.text("owned"))))

    @Test fun `a click event from the client does not survive rendering`() {
        val wire = wire(line(hostile))
        assertFalse(wire.contains("run_command") && wire.contains("/op attacker"),
            "a client-authored click handler must not reach another player: $wire")
        assertFalse(wire.contains("/op attacker"), "nor the command it would have run: $wire")
    }

    @Test fun `a hover event from the client does not survive either`() {
        val wire = wire(line(hostile))
        assertFalse(wire.contains("\"owned\""), "a forged tooltip is impersonation: $wire")
    }

    /**
     * Nested, not just top level — the whole attack is in the children, because the root of a chat
     * body is where a plugin puts *its own* click and would be overwritten anyway.
     */
    @Test fun `events are removed from every level, not only the root`() {
        val deep = Component.text("a")
            .append(Component.text("b").append(Component.text("c").append(
                Component.text("d").clickEvent(ClickEvent.openUrl("http://evil.example")),
            )))

        val wire = wire(line(deep))

        assertFalse(wire.contains("evil.example"), "four levels down and still stripped: $wire")
    }

    /** Stripping must not eat the text: the message still says what it said. */
    @Test fun `the text survives being cleaned`() {
        val cleaned = ChatLine.withoutEvents(hostile)
        assertEquals("free CLICK HERE tip", PlainTextComponentSerializer.plainText().serialize(cleaned))
    }

    /**
     * Colour is **kept**.
     *
     * Another plugin may legitimately have coloured the line before this handler ran, and colour is
     * not an execution surface. Stated as a test so that if somebody later widens the strip, this
     * fails and the widening has to be argued for rather than slipped in.
     */
    @Test fun `colour and formatting are left alone`() {
        val coloured = Component.text("hello").color(NamedTextColor.RED).decorate(TextDecoration.BOLD)

        val cleaned = ChatLine.withoutEvents(coloured)

        assertEquals(NamedTextColor.RED, cleaned.color(), "a player's colour is not a shell")
        assertEquals(TextDecoration.State.TRUE, cleaned.decoration(TextDecoration.BOLD))
    }

    /** Our own click — the reply button — is the whole point of the line and must still work. */
    @Test fun `the plugin's own click still lands`() {
        val wire = wire(line(Component.text("plain body")))
        assertTrue(wire.contains("suggest_command"), "reply-to-this-line is not optional: $wire")
        assertFalse(wire.contains("run_command"), "and it is a suggestion, never an execution: $wire")
    }
}
