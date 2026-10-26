package com.meowo.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The chat line shape: `<图标> <名字> <状态> <消息>`.
 *
 * Two rules are easy to regress and neither is visible in a screenshot:
 * the receiver's own name must be inert, or a reader replies to themselves; and the badge
 * must lead rather than trail, or the line reads as `<名字> ✉ 消息`.
 */
class ChatLineTest {

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    private val alice = ChatLine.who("Alice", null, "/meo msg Alice ")
    private val steve = ChatLine.who("Steve", null, "/meo msg Steve ")

    @Test fun `a private line reads icon sender arrow receiver status body`() {
        val line = ChatLine.private(alice, steve, delivered = true, body = ChatLine.plain("你在吗"))
        assertEquals("✉ Alice → Steve ✔ 你在吗", plain(line))
    }

    @Test fun `an undelivered line shows a circle instead of the tick`() {
        val line = ChatLine.private(alice, steve, delivered = false, body = ChatLine.plain("你在吗"))
        assertEquals("✉ Alice → Steve 〇 你在吗", plain(line))
    }

    @Test fun `every part is separated by a single space`() {
        val text = plain(ChatLine.private(alice, steve, delivered = true, body = ChatLine.plain("hi")))
        assertEquals("✉ Alice → Steve ✔ hi", text)
        assertEquals(listOf("✉", "Alice", "→", "Steve", "✔", "hi"), text.split(" "))
    }

    @Test fun `the icon leads the line rather than trailing the body`() {
        val text = plain(ChatLine.private(alice, steve, delivered = true, body = ChatLine.plain("hi")))
        assertEquals("✉", text.substringBefore(" "), "the badge must be the first thing on the line")
        assertFalse(text.contains("hi ✉"), "the badge must not end up inside the message")
        assertFalse(text.contains("<"), "a stray angle bracket must never reach the line: $text")
        assertFalse(text.contains("§"), "a raw colour code must never reach the client: $text")
    }

    @Test fun `the sender name carries the reply shortcut`() {
        val click = alice.clickEvent()
        assertNotNull(click, "a name must offer the reply shortcut")
        assertEquals(ClickEvent.suggestCommand("/meo msg Alice "), click)
    }

    @Test fun `the receiver side is inert so a reader cannot reply to themselves`() {
        // For the recipient this is their own name; for the sender it is who they wrote to.
        // Neither is a reply target, so it carries a hover and nothing else.
        assertNull(ChatLine.receiver("Steve", null).clickEvent(), "the receiver name must not be a shortcut")
        assertNull(ChatLine.receiver("Steve", ChatLine.plain("card")).clickEvent())
    }

    /**
     * The same line is rendered for both sides, so the sender reads their own name in it. A reply
     * shortcut built from that name is a command to whisper oneself, which is exactly what players
     * reported. A blank command is how the caller says "this is the sender's copy" — the click is
     * dropped, the hover stays.
     */
    @Test fun `a blank reply command leaves the name hoverable but not clickable`() {
        val inert = ChatLine.who("Alice", ChatLine.plain("card"), "")
        assertNull(inert.clickEvent(), "the sender must not be offered a whisper to themselves")
        assertNotNull(inert.hoverEvent(), "the profile card is still the point of hovering")
    }

    /**
     * There is no quote glyph any more.
     *
     * A reply used to print the words it answered behind `❝`, looked up in the log. Players read it as
     * clutter: the line **already opens with its own `#k3f9`**, and that code is the reference —
     * hover it for who and when, `/meo replay` for the exchange. Two references on one line is one too
     * many, and the long one was the ugly one.
     */
    @Test fun `a reply carries the code and nothing that looks like a quote`() {
        val line = ChatLine.private(
            alice, steve, true, ChatLine.plain("好"), replyCommand = "/meo msg Alice #k3f9 ",
            anchorCode = "bbbb", anchorHover = "Bob → Alice · 21:24:40",
        )
        assertEquals("#bbbb ✉ Alice → Steve ✔ 好", plain(line))
        assertFalse(plain(line).contains("\u275d"), "the quote glyph must be gone: ${plain(line)}")
    }

    /**
     * A reply names the message it answers, right in front of its own words.
     *
     * ```
     * #bbbb ✉ Bob → Alice  #8i5f 收到
     * ```
     *
     * Three earlier shapes failed: no mark (a reply looked exactly like a fresh message), a `↩`
     * glyph, and a `❝` plus the quoted sentence. The code is the one the plugin already speaks —
     * hovering it shows the words, clicking it opens the thread.
     */
    @Test fun `a reply shows the referenced code in front of its words`() {
        val line = ChatLine.private(
            alice, steve, true, ChatLine.plain("收到"), replyCommand = "/meo msg Bob #8i5f ",
            anchorCode = "bbbb", refCode = "8i5f", refHover = "在吗",
        )
        assertEquals("#bbbb ✉ Alice → Steve ✔ #8i5f 收到", plain(line))
    }

    @Test fun `a fresh message has no reference and no extra gap`() {
        val line = ChatLine.private(alice, steve, true, ChatLine.plain("在吗"), anchorCode = "8i5f")
        assertEquals("#8i5f ✉ Alice → Steve ✔ 在吗", plain(line))
        assertFalse(plain(line).contains("  "), "no part may be padded: '${plain(line)}'")
    }

    /** The reference is the one thing to read, so hovering it must say what was answered. */
    @Test fun `the reference hovers with the message it answers and opens its thread`() {
        val ref = ChatLine.replyRef("8i5f", "在吗")
        assertEquals("#8i5f", plain(ref))
        val shown = (ref.hoverEvent() as? HoverEvent<*>)?.let { it.value() as? Component }
        assertNotNull(shown, "hovering the reference must say what it answers")
        assertEquals("在吗", plain(shown!!))
        assertEquals(ClickEvent.runCommand("/meo replay #8i5f"), ref.clickEvent())
    }

    /**
     * The receiver is the one name on a private line the sender can always talk to.
     *
     * It used to be hover-only, which left the **sender's own copy** — the half a sender re-reads most
     * carefully — with no way to answer at all. On the recipient's copy it is their own name, so it
     * stays inert there.
     */
    @Test fun `the receiver name opens a private message for the sender`() {
        assertEquals(
            ClickEvent.suggestCommand("/meo msg Steve "),
            ChatLine.receiver("Steve", null, ChatLine.msgCommand("Steve")).clickEvent(),
        )
        assertNull(ChatLine.receiver("Steve", ChatLine.plain("card"), "").clickEvent())
    }

    @Test fun `an unquoted message has no double space`() {
        val text = plain(ChatLine.private(alice, steve, true, ChatLine.plain("hi")))
        assertFalse(text.contains("  "), "no part may be padded with a stray second space: '$text'")
    }

    @Test fun `a hover card is attached only when one is supplied`() {
        val withCard = ChatLine.who("Alice", ChatLine.plain("card"), "/meo msg Alice ")
        assertNotNull(withCard.hoverEvent())
        assertNull(alice.hoverEvent(), "no card means no empty tooltip")
    }

    @Test fun `a speaker line is the name then the message`() {
        assertEquals("✉ EconCat 已转账", plain(ChatLine.say(Component.text("✉ EconCat"), ChatLine.plain("已转账"))))
    }

    /**
     * The public chat line. The brackets are gone because the plugin installs this as a
     * `ChatRenderer`, which makes Paper use `paper:raw` — a chat type registered with no
     * decoration — instead of `minecraft:chat`, whose client-side `chat.type.text` is `"<%s> %s"`.
     */
    @Test fun `a public chat line is the name then the message, with no bracket pair`() {
        val line = ChatLine.publicLine(Component.text("Steve"), null, ChatLine.plain("在吗"))
        assertEquals("Steve 在吗", plain(line))
    }

    @Test fun `a name opens a card, and a reply prefix makes it clickable`() {
        val hoverOnly = ChatLine.member(Component.text("Steve"), ChatLine.plain("card"))
        assertNotNull(hoverOnly.hoverEvent())
        assertNull(hoverOnly.clickEvent(), "a blank command must leave the name inert")

        val clickable = ChatLine.member(Component.text("Steve"), ChatLine.plain("card"), "/meo msg Steve ")
        assertEquals(ClickEvent.suggestCommand("/meo msg Steve "), clickable.clickEvent())
    }

    /**
     * A player is as addressable as a cat. Public chat used to be the one line whose name was
     * hover-only, which read as an oversight rather than a decision.
     */
    @Test fun `a public chat line lets the reader start a private message`() {
        val line = ChatLine.publicLine(
            Component.text("Steve"), ChatLine.plain("card"), ChatLine.plain("hi"),
            replyCommand = "/meo msg Steve ",
        )
        assertEquals("Steve hi", plain(line), "no code means no anchor and no leading gap")

        val anchored = ChatLine.publicLine(
            Component.text("Steve"), null, ChatLine.plain("hi"),
            anchorCode = "k3f9", anchorHover = "Steve · 21:24:33",
            replyCommand = "/meo msg Steve ",
        )
        assertEquals("#k3f9 Steve hi", plain(anchored), "the code needs exactly one space before the name")
        assertNull(anchored.children().first().clickEvent(), "the code is hover-only on purpose")
    }

    /**
     * The two halves of the regression this file exists for.
     *
     * `<> ` around a line is drawn by the **client**, not by us: binding a `minecraft:chat` sender
     * slot made the client render the vanilla `chat.type.text`, whose value is `"<%s> %s"`. Nothing
     * in our sources ever held a `<`, which is why stripping it from the message text changed
     * nothing. The line is composed and delivered whole now, so the assertions below are on the
     * shape a player actually receives.
     */
    @Test fun `no delivered line carries the client's angle-bracket decoration`() {
        val lines = listOf(
            plain(ChatLine.private(alice, steve, true, ChatLine.plain("hi"))),
            plain(ChatLine.private(alice, steve, false, ChatLine.plain("hi"), "hi", "/meo msg Alice ❝hi ")),
            plain(ChatLine.system(alice, ChatLine.plain("hi"))),
            plain(ChatLine.say(Component.text("✉ EconCat"), ChatLine.plain("hi"))),
            plain(ChatLine.publicLine(Component.text("Steve"), null, ChatLine.plain("hi"))),
        )
        for (line in lines) {
            assertFalse(line.contains('<'), "a stray '<' must never reach the line: '$line'")
            assertFalse(line.contains('>'), "a stray '>' must never reach the line: '$line'")
        }
    }

    /**
     * A private line used to be handed over twice — once as the chat-type sender, once as the body —
     * so the sentence appeared both inside the brackets and after them. The body must now be
     * composed in exactly one place, which this pins at the shape level.
     */
    @Test fun `the body is composed once, not repeated as a name slot`() {
        val line = plain(ChatLine.private(alice, steve, true, ChatLine.plain("hi")))
        assertEquals(1, Regex("\\bhi\\b").findAll(line).count(), "the body must appear once: '$line'")
    }

    /**
     * The reply affordance is the **body**, not a button.
     *
     * A `↩ 回复` label said the same thing as the body in five more characters on every line, and it
     * made the reply look like an extra control rather than something you do to what someone said.
     */
    @Test fun `the body carries the reply command`() {
        val line = ChatLine.private(alice, steve, true, ChatLine.plain("hi"), replyCommand = "/meo msg Alice #k3f9 ")
        assertEquals("✉ Alice → Steve ✔ hi", plain(line), "the trailing button is gone")
        assertEquals(
            ClickEvent.suggestCommand("/meo msg Alice #k3f9 "),
            line.children().last().clickEvent(),
            "clicking the message replies to it",
        )
    }

    /** The name is a **person**: clicking it starts something new, with no reference attached. */
    @Test fun `the sender name starts a blank private message`() {
        assertEquals("/meo msg Alice ", ChatLine.msgCommand("Alice"))
        assertEquals(
            ClickEvent.suggestCommand("/meo msg Alice "),
            ChatLine.who("Alice", null, ChatLine.msgCommand("Alice")).clickEvent(),
        )
    }

    /** A blank command leaves the name hover-only — never a dead target, never a whisper to oneself. */
    @Test fun `a blank message command leaves the name inert`() {
        val name = ChatLine.who("Alice", ChatLine.plain("card"), "")
        assertNull(name.clickEvent())
        assertNotNull(name.hoverEvent())
    }

    @Test fun `a blank reply command leaves the body plain and selectable`() {
        val line = ChatLine.private(alice, steve, true, ChatLine.plain("hi"))
        assertNull(line.children().last().clickEvent())
    }

    /**
     * Every line a player can read answers to the same rule.
     *
     * A cat's notice used to be the one kind of line whose text could not be clicked, so asking a cat
     * about what it just said meant aiming at a five-character name. Same rule everywhere:
     * name → `/meo msg`, body → the same.
     */
    @Test fun `a notice body is clickable like every other body`() {
        val name = ChatLine.who("EconCat", null, ChatLine.msgCommand("EconCat"))
        val line = ChatLine.say(
            name, ChatLine.plain("已转账"), anchorCode = "k3f9", anchorHover = "EconCat · 21:24:33",
            bodyCommand = ChatLine.msgCommand("EconCat"),
        )
        assertEquals("#k3f9 EconCat 已转账", plain(line))
        assertEquals(ClickEvent.suggestCommand("/meo msg EconCat "), line.children().last().clickEvent())
    }

    @Test fun `a notice without a command leaves its body inert`() {
        val line = ChatLine.say(Component.text("EconCat"), ChatLine.plain("已转账"))
        assertNull(line.children().last().clickEvent())
        assertEquals("EconCat 已转账", plain(line))
    }

}