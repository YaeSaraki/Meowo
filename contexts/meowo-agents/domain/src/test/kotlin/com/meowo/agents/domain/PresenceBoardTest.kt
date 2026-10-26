package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shared "who is around" board.
 *
 * The whole point of it is that it is **cheap enough to always render**, which only holds if it
 * carries almost nothing. These tests exist to pin that: if somebody adds message text, the class
 * stops being safe to put in a prompt, and the tests are what says so.
 */
class PresenceBoardTest {

    private fun board(now: Long = 1_000L) = PresenceBoard(capacity = 8, windowMillis = 60_000)

    /**
     * The single property the whole design rests on.
     *
     * `isPublic` is a parameter rather than something inferred from a channel string precisely so
     * that a caller cannot widen this by accident — and this test is the guarantee that a whisper
     * never lands here.
     */
    @Test fun `a private message never reaches the board`() {
        val board = board()
        board.note("Alice", isPublic = false)
        assertEquals(0, board.size(), "a whisper must not make anyone visible to the cats")
        assertEquals("", board.summary())
    }

    @Test fun `public traffic is counted per player`() {
        val board = board()
        repeat(3) { board.note("Alice", isPublic = true) }
        board.note("Bob", isPublic = true)
        assertEquals(2, board.size())
        assertTrue(board.summary(now = 2_000).contains("Alice（说了 3 句）"))
        assertTrue(board.summary(now = 2_000).contains("Bob（说了 1 句）"))
    }

    @Test fun `the most recent is first`() {
        val board = board()
        board.note("Alice", true, now = 1_000)
        board.note("Bob", true, now = 2_000)
        assertEquals(listOf("Bob", "Alice"), board.recent(now = 3_000).map { it.player })
    }

    /** Re-noting moves the player to the end rather than editing them in place. */
    @Test fun `speaking again puts a player back at the front`() {
        val board = board()
        board.note("Alice", true, now = 1_000)
        board.note("Bob", true, now = 2_000)
        board.note("Alice", true, now = 3_000)
        assertEquals(listOf("Alice", "Bob"), board.recent(now = 4_000).map { it.player })
        assertEquals(2, board.recent(now = 4_000).first().mentions)
    }

    @Test fun `the board is a ring`() {
        val board = PresenceBoard(capacity = 8, windowMillis = 60_000)
        repeat(20) { board.note("p$it", true, now = 1_000L + it) }
        assertEquals(8, board.size())
        assertEquals("p19", board.recent(now = 10_000).first().player)
    }

    @Test fun `somebody who has gone quiet drops off`() {
        val board = board()
        board.note("Alice", true, now = 1_000)
        assertEquals(1, board.recent(now = 30_000).size)
        assertEquals(0, board.recent(now = 120_000).size, "the window is what makes this a presence, not an archive")
    }

    @Test fun `the summary carries names and counts and nothing else`() {
        val board = board()
        board.note("Alice", true, now = 1_000)
        val line = board.summary(now = 2_000)
        assertTrue(line.contains("Alice"))
        assertFalse(line.contains("说了什么"), "the summary must stay countable: $line")
    }

    @Test fun `forgetting a player removes them`() {
        val board = board()
        board.note("Alice", true)
        board.forget("Alice")
        assertEquals(0, board.size())
    }
}
