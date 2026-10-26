package com.meowo.chat.domain

import com.meowo.platform.ChatLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `#k3f9 ❝quote body` prefix that prefilling a reply puts in the input box.
 *
 * The round-trip test at the bottom is the one that matters: the string the player is handed has to
 * parse back into the same code and quote, or a reply silently stops threading.
 */
class ReferenceTest {

    @Test fun `a reply carries its code and its body`() {
        val parsed = Reference.parse("#k3f9 你好")
        assertEquals("k3f9", parsed.anchor)
        assertEquals("你好", parsed.body)
        assertTrue(parsed.isReply)
    }

    @Test fun `a plain message has no code`() {
        val parsed = Reference.parse("你好")
        assertNull(parsed.anchor)
        assertEquals("你好", parsed.body)
        assertFalse(parsed.isReply)
    }

    @Test fun `leading and trailing whitespace is trimmed`() {
        val parsed = Reference.parse("   #k3f9   你好   ")
        assertEquals("k3f9", parsed.anchor)
        assertEquals("你好", parsed.body)
    }

    /**
     * `❝` is **decoration the renderer draws**, never something the player has to type.
     *
     * It used to be parsed here, and because the marker ran to the end of the line the quoted text
     * and the message were the same string — the renderer then had to choose, and printing the quote
     * dropped the player's actual reply. One is the words before, the other is the words now.
     */
    @Test fun `a quote marker is ordinary text, not a second copy of the body`() {
        val parsed = Reference.parse("#k3f9 ❝在吗 你好")
        assertEquals("k3f9", parsed.anchor)
        assertEquals("❝在吗 你好", parsed.body)
    }

    @Test fun `a hex colour at the front is left in the body`() {
        val parsed = Reference.parse("#ff0000 是红色")
        assertNull(parsed.anchor)
        assertEquals("#ff0000 是红色", parsed.body)
    }

    @Test fun `an empty input parses to nothing rather than throwing`() {
        val parsed = Reference.parse("")
        assertNull(parsed.anchor); assertEquals("", parsed.body)
    }

    /**
     * What the reply button prefills must parse back into the same code. Without this the two halves
     * can drift and every reply silently stops threading.
     */
    @Test fun `what the reply button prefills parses back to the same code`() {
        val command = ChatLine.replyCommand("Alice", "k3f9")
        val typed = command.removePrefix("${ChatLine.DM} Alice").trim()
        val parsed = Reference.parse(typed)
        assertEquals("k3f9", parsed.anchor)
        assertEquals("", parsed.body)
        assertTrue(parsed.isReply, "a reply with nothing typed yet is still a reply")
    }

    @Test fun `a reply with no code prefills nothing extra`() {
        assertEquals("/meo msg Alice ", ChatLine.replyCommand("Alice", ""))
    }
}
