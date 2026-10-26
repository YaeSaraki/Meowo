package com.meowo.chat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The codes that are taken.
 *
 * The book exists because the code space is finite and the log is not: a code that was already
 * used must never be handed out again, or a reader following `#k3f9` lands on somebody else's
 * message — and, with `INSERT OR REPLACE`, the older message is destroyed on the way.
 */
class CodeBookTest {

    @Test fun `a minted code is never handed out twice`() {
        val book = CodeBook()
        val minted = HashSet<String>()
        repeat(2_000) {
            val code = book.mint(Random(it))
            assertTrue(code !in minted, "minted a code that was already minted: $code")
            minted += code
        }
        assertEquals(2_000, book.size())
    }

    @Test fun `a reserved code is never minted again`() {
        val book = CodeBook()
        val reserved = MessageCode.codeOf(123_456)
        assertTrue(book.reserve(reserved))
        assertFalse(book.reserve(reserved), "the same code cannot be reserved twice")
        assertTrue(book.isUsed(reserved))
        repeat(500) {
            assertFalse(book.mint(Random(it)) == reserved, "a reserved code was minted again")
        }
    }

    @Test fun `a code that is not a code cannot be reserved`() {
        val book = CodeBook()
        assertFalse(book.reserve("NOPE"), "upper case is not what we mint")
        assertFalse(book.reserve("no-e"), "a dash is not base-36")
        assertFalse(book.reserve("TOOLONG"))
        assertFalse(book.reserve("ab"), "two characters is not a code")
    }

    /** The index is the whole point of the bitmap, so the two must be exact inverses. */
    @Test fun `a code and its index are inverses`() {
        for (index in listOf(0, 1, 35, 36, 1_234, 999_999, MessageCode.SPACE - 1)) {
            assertEquals(index, MessageCode.indexOf(MessageCode.codeOf(index)))
        }
        assertEquals(-1, MessageCode.indexOf("NOPE"))
        assertEquals(-1, MessageCode.indexOf("ab"))
        // Four lower-case letters *are* a code: the space is base-36, not hexadecimal.
        assertEquals(MessageCode.indexOf("nope"), 1_105_106)
    }

    @Test fun `every code of the space round-trips`() {
        for (index in 0 until MessageCode.SPACE step 4_097) {
            val code = MessageCode.codeOf(index)
            assertEquals(4, code.length)
            assertEquals(index, MessageCode.indexOf(code))
        }
    }
}
