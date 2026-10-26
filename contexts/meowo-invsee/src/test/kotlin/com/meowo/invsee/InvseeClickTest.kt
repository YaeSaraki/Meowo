package com.meowo.invsee

import com.meowo.invsee.InvseeClick.Plan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The click table, as a truth table.
 *
 * Every row is a click an operator can actually make, and the point of the table is the one thing an
 * invsee must never get wrong: **the same number of items is on both sides of the click afterwards**.
 * A plan that is wrong here is an item that has been destroyed or, far worse, duplicated.
 */
class InvseeClickTest {

    private fun plan(
        quickMove: Boolean = false,
        cursor: Int = 0,
        slot: Int = 0,
        room: Int = 0,
        same: Boolean = false,
    ) = InvseeClick.plan(quickMove, cursor, slot, room, same)

    @Test fun `an empty cursor over an empty slot does nothing`() {
        assertEquals(Plan.NONE, plan())
    }

    @Test fun `an empty cursor takes the whole stack`() {
        assertEquals(Plan.TAKE_TO_CURSOR, plan(cursor = 0, slot = 32))
        assertEquals(Plan.TAKE_TO_CURSOR, plan(cursor = 0, slot = 1))
    }

    @Test fun `a held stack goes into an empty slot`() {
        assertEquals(Plan.PLACE_IN_SLOT, plan(cursor = 5, slot = 0))
    }

    @Test fun `the same item merges when there is room and stops when there is not`() {
        assertEquals(Plan.MERGE_INTO_SLOT, plan(cursor = 5, slot = 60, room = 4, same = true))
        assertEquals(Plan.NONE, plan(cursor = 5, slot = 64, room = 0, same = true))
    }

    /** Two different items exchange places; a full stack of the same item would change nothing. */
    @Test fun `a different item swaps`() {
        assertEquals(Plan.SWAP, plan(cursor = 3, slot = 12, room = 0, same = false))
    }

    @Test fun `shift-click skips the cursor and takes the stack into the bag`() {
        assertEquals(Plan.TAKE_TO_BAG, plan(quickMove = true, cursor = 0, slot = 8))
        assertEquals(Plan.NONE, plan(quickMove = true, cursor = 4, slot = 0), "nothing to take")
    }

    /** Conservation: what merges is what the cursor had, or the room, never more than either. */
    @Test fun `a merge never moves more than the cursor or the room`() {
        assertEquals(4, InvseeClick.mergeAmount(cursorAmount = 5, room = 4))
        assertEquals(5, InvseeClick.mergeAmount(cursorAmount = 5, room = 64))
        assertEquals(0, InvseeClick.mergeAmount(cursorAmount = 0, room = 64))
        assertEquals(0, InvseeClick.mergeAmount(cursorAmount = 5, room = 0))
    }

    @Test fun `a full stack never merges more than its own maximum`() {
        val cursor = 17
        val room = 64 - 60
        val moved = InvseeClick.mergeAmount(cursor, room)
        assertEquals(4, moved)
        assertEquals(cursor - moved, 13, "the rest stays on the cursor")
    }

    @Test fun `only left right and shift are understood`() {
        assertTrue(InvseeClick.isSupported(leftClick = true, rightClick = false, shift = false))
        assertTrue(InvseeClick.isSupported(leftClick = false, rightClick = true, shift = false))
        assertTrue(InvseeClick.isSupported(leftClick = false, rightClick = false, shift = true))
        assertFalse(InvseeClick.isSupported(leftClick = false, rightClick = false, shift = false),
            "drop, hotbar swap and double-click must not be let through")
    }
}