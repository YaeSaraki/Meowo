package com.meowo.chat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The `#k3f9` anchor.
 *
 * Two failure modes worth pinning, because both would be quiet: a code that looks unique but is not,
 * and a `#` that eats the front of a hex colour.
 */
class MessageCodeTest {

    @Test fun `a code is four base-36 characters`() {
        val book = CodeBook()
        repeat(200) {
            val code = book.mint()
            assertEquals(4, code.length, "wrong length: $code")
            assertTrue(MessageCode.isValid(code), "not base-36: $code")
            assertTrue(code.all { it.isDigit() || it in 'a'..'z' }, "unexpected character: $code")
        }
    }

    /** The uniqueness rule itself, and it is the code book's job — see `CodeBookTest`. */
    @Test fun `a code already in the log is never handed out again`() {
        val taken = generateSet(MessageCode.LENGTH * 400)
        val book = CodeBook()
        taken.forEach { book.reserve(it) }
        repeat(500) {
            val code = book.mint(Random(it))
            assertTrue(code !in taken, "minted a code that is already taken: $code")
        }
    }

    @Test fun `generation is actually random rather than constant`() {
        val book = CodeBook()
        val codes = (0 until 60).map { book.mint(Random(it)) }.toSet()
        assertTrue(codes.size > 50, "codes repeat far too often: $codes")
    }

    // ── parsing ───────────────────────────────────────────────────────────────────────────────

    @Test fun `a code is read off the front of a line`() {
        assertEquals("k3f9", MessageCode.leading("#k3f9 hi"))
        assertEquals("k3f9", MessageCode.leading("   #k3f9"))
        assertNull(MessageCode.leading("hi #k3f9"), "a code only counts at the start of a message")
    }

    /**
     * The case that made this parser worth writing: `#ff0000` is a colour. Four word characters
     * followed by more word characters is not a code, and the negative lookahead is what says so.
     */
    @Test fun `a hex colour is not mistaken for a code`() {
        assertNull(MessageCode.leading("#ff0000"))
        assertNull(MessageCode.leading("#abcdef"))
        assertEquals("abcd", MessageCode.leading("#abcd"))
        assertEquals("abcd", MessageCode.leading("#abcd rest"))
    }

    @Test fun `a malformed code is refused`() {
        assertNull(MessageCode.leading("#k3f"), "three characters is not a code")
        assertNull(MessageCode.leading("#k3f99"), "five characters is not a code")
        assertNull(MessageCode.leading("#K3F9"), "upper case is not what we mint")
    }

    @Test fun `validity matches what is minted`() {
        assertTrue(MessageCode.isValid(CodeBook().mint()))
        assertFalse(MessageCode.isValid(""))
        assertFalse(MessageCode.isValid("k3f"))
        assertFalse(MessageCode.isValid("K3F9"))
    }

    private fun generateSet(count: Int): Set<String> {
        val book = CodeBook()
        return (0 until count).map { book.mint(Random(it)) }.toSet()
    }
}