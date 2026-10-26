package com.meowo.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one function that turns a player's words into a component, and the reason the plugin has one.
 *
 * ## The bug this replaced
 *
 * `ChatPipeline` decorates with BBCode — `**粗体**` becomes `[b]粗体[/b]` — and that string was handed
 * to `LegacyComponentSerializer`, which reads `§`. Two vocabularies, neither reading the other, so
 * the markup reached players as the literal characters `[b]`. Typing `**粗体**` in chat showed you
 * `[b]粗体[/b]`.
 *
 * Swapping `MarkupFormatter` to emit `§l` would have looked like the smaller change and is the wrong
 * one: `§` is U+00A7, not a control character, so it survives normalisation and the deserialiser reads
 * it as a colour. A player typing `§c§lADMIN` would get red bold text that reads as the server's own.
 * **Format codes belong to whoever wrote the string.**
 *
 * These tests pin the split: [ChatLine.plain] stays for the language bundle, [ChatLine.body] is for
 * everything a player or a model produced, and `§` is a character here.
 */
class ChatLineBodyTest {

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    /**
     * Only a decoration explicitly turned **on** counts.
     *
     * `Component.decoration(...)` returns a `TextDecoration.State`, never null — it answers
     * `NOT_SET` for anything that never asked. A first version of this helper took any non-null
     * answer as "styled" and therefore reported bold on every component in the plugin.
     */
    private fun decorations(component: Component): Set<TextDecoration> {
        val found = mutableSetOf<TextDecoration>()
        fun walk(c: Component) {
            for (decoration in listOf(TextDecoration.BOLD, TextDecoration.ITALIC, TextDecoration.UNDERLINED)) {
                if (c.decoration(decoration) == TextDecoration.State.TRUE) found += decoration
            }
            c.children().forEach(::walk)
        }
        walk(component)
        return found
    }

    // ── the markup actually works now ─────────────────────────────────────────────────────────

    @Test fun `a bold span is styled rather than shown as brackets`() {
        val body = ChatLine.body("[b]粗体[/b]普通")
        assertEquals("粗体普通", plain(body), "the brackets are markup, not text")
        assertTrue(
            decorations(body).contains(TextDecoration.BOLD),
            "`**粗体**` reached players as literal `[b]` for as long as this went through a legacy reader",
        )
    }

    @Test fun `italic and underline are the other two tags the formatter emits`() {
        val body = ChatLine.body("[i]斜[/i][u]下划[/u]")
        assertEquals("斜下划", plain(body))
        val found = decorations(body)
        assertTrue(found.contains(TextDecoration.ITALIC), "italic was not applied")
        assertTrue(found.contains(TextDecoration.UNDERLINED), "underline was not applied")
    }

    @Test fun `a style nested inside another keeps both`() {
        val found = decorations(ChatLine.body("[b][u]粗下划[/u][/b]"))
        assertTrue(found.contains(TextDecoration.BOLD) && found.contains(TextDecoration.UNDERLINED), "got $found")
    }

    /** Text outside the span must not inherit the style — a leak here colours the whole message. */
    @Test fun `text after a closed span is unstyled`() {
        val body = ChatLine.body("[b]粗[/b]普通")
        fun boldAt(c: Component): Boolean =
            c.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE || c.children().any(::boldAt)
        assertEquals(2, body.children().size, "expected a styled run then an unstyled one: ${body.children()}")
        assertTrue(boldAt(body.children()[0]), "the first run should be bold")
        assertFalse(boldAt(body.children()[1]), "the second run must not be: ${body.children()}")
    }

    // ── the injection this closed ───────────────────────────────────────────────────────────

    /**
     * The whole reason [body] is not a legacy deserialisation.
     *
     * Measured on this codebase, not assumed: `LegacyComponentSerializer.legacySection()` turns
     * `§c` into a red `c` and `§#ff0000` into a red start, and `ChatService.renderPrivate` ran the
     * player's body through it **unconditionally** — unlike public chat, which only deserialises once
     * an emoji has changed the string. So `/meo msg Bob §c` was enough, with no decoration at all.
     */
    @Test fun `a section escape in a body stays a character`() {
        val hostile = "§c§lADMIN 管理员"
        assertEquals(hostile, plain(ChatLine.body(hostile)), "a player put colour in their own message")
        assertFalse(decorations(ChatLine.body(hostile)).contains(TextDecoration.BOLD), "and got it applied")
    }

    /** The hex form needs the `§` too — measured, so it is the same escape and not a second one. */
    @Test fun `a hex colour escape in a body stays a character`() {
        val hostile = "§#ff0000ADMIN"
        assertEquals(hostile, plain(ChatLine.body(hostile)))
    }

    /**
     * Proof the escapes really are live elsewhere — without this the two tests above prove nothing.
     *
     * Also pins a thing that was assumed and is **false**: a bare `&#ff0000` is not an escape. Both
     * readers reject it. An earlier version of this file claimed the `ChatContext` reader's
     * `.hexCharacter('#')` opened a second way in; it did not, and the claim cost a wrong edit to a
     * code comment before it was measured.
     */
    @Test fun `the legacy reader this replaced really did colour these`() {
        fun coloured(c: Component): Boolean = c.color() != null || c.children().any(::coloured)
        val line = LegacyComponentSerializer.builder()
            .character('§').hexCharacter('#').useUnusualXRepeatedCharacterHexFormat().build()
        val std = LegacyComponentSerializer.legacySection()
        assertTrue(coloured(std.deserialize("§cADMIN")), "§c must be live or the test above is theatre")
        assertTrue(coloured(line.deserialize("§#ff0000ADMIN")), "§#ff0000 must be live")
        assertFalse(coloured(std.deserialize("&#ff0000ADMIN")), "a bare hex escape is not one — measured")
    }

    // ── it must not eat what it does not understand ──────────────────────────────────────────

    /**
     * Half-consuming a bracket is data loss, and it is the failure MarkupFormatter is explicitly
     * written to avoid for an unclosed `**`. An unmatched `[b]` — a player asking about this markup,
     * or a cat explaining it — has to come out the other side unchanged.
     */
    @Test fun `an unmatched open tag stays literal`() {
        val typed = "数组 [b] 是什么意思"
        assertEquals(typed, plain(ChatLine.body(typed)), "the rest of the sentence was styled by accident")
        assertFalse(decorations(ChatLine.body(typed)).contains(TextDecoration.BOLD))
    }

    @Test fun `an unmatched close tag stays literal`() {
        val typed = "结束语 [/b]"
        assertEquals(typed, plain(ChatLine.body(typed)), "a stray close tag must not eat itself")
    }

    @Test fun `a tag this plugin does not emit stays literal`() {
        val typed = "[color=red]红[/color]"
        assertEquals(typed, plain(ChatLine.body(typed)), "only [b] [i] [u] are markup here")
    }

    @Test fun `a body with no brackets at all is returned unchanged`() {
        val plain_ = "你好 everyone，今天天气不错"
        assertEquals(plain_, plain(ChatLine.body(plain_)))
    }

    /** The empty and single-bracket edges, because an indexOf-based parser can go out of bounds. */
    @Test fun `degenerate input does not throw`() {
        for (input in listOf("", "[", "]", "[b", "b]", "[]", "[/]", "[" + "b".repeat(50) + "]")) {
            plain(ChatLine.body(input))
        }
    }
}