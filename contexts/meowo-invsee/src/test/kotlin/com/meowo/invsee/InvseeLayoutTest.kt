package com.meowo.invsee

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The window layout, pinned as numbers.
 *
 * These are the assertions that would otherwise only be made by an admin holding the wrong stack:
 * the hotbar and the storage rows are stored in **opposite** orders inside `PlayerInventory`, so a
 * layout that transposes them looks perfectly reasonable on screen and hands out the wrong item.
 */
class InvseeLayoutTest {

    @Test fun `the window shows the storage rows before the hotbar`() {
        // Window row 1 is the target's storage row 1, which is PlayerInventory index 9 — not 0.
        assertEquals(9, InvseeLayout.indexOf(0))
        assertEquals(35, InvseeLayout.indexOf(26))
        assertEquals(0, InvseeLayout.indexOf(27))
        assertEquals(8, InvseeLayout.indexOf(35))
    }

    /**
     * The one row where the two orders disagree.
     *
     * `PlayerInventory` stores equipment feet first — 36 boots … 39 helmet — while the window reads
     * head first, so the row is walked backwards. Asserted with the *names* attached, because an
     * index on its own cannot say whether it moved.
     */
    @Test fun `armour reads head first even though the bag stores it feet first`() {
        assertEquals("HEAD", InvseeLayout.requiresEquipment(36), "the first armour slot takes a helmet")
        assertEquals("FEET", InvseeLayout.requiresEquipment(39), "the last one takes boots")
        assertEquals(39, InvseeLayout.indexOf(36), "the first armour slot shows the helmet")
        assertEquals(36, InvseeLayout.indexOf(39), "the last armour slot shows the boots")
        assertEquals(40, InvseeLayout.indexOf(40), "and the offhand is where it always was")
        assertTrue(InvseeLayout.isArmor(36) && InvseeLayout.isArmor(39))
        assertTrue(!InvseeLayout.isArmor(40), "the offhand is not an armour slot")
    }

    @Test fun `the filler row has no inventory index behind it`() {
        for (slot in 41 until InvseeLayout.SIZE) assertNull(InvseeLayout.indexOf(slot), "slot $slot")
        assertNull(InvseeLayout.indexOf(-1))
        assertNull(InvseeLayout.indexOf(InvseeLayout.SIZE))
    }

    /** Both directions come from one table, so a slot can never show an item that lives elsewhere. */
    @Test fun `every live slot round-trips`() {
        for (slot in InvseeLayout.liveSlots) {
            val index = InvseeLayout.indexOf(slot)!!
            assertEquals(slot, InvseeLayout.slotOf(index), "slot $slot ↔ index $index")
        }
        assertEquals(41, InvseeLayout.liveSlots.size)
    }

    @Test fun `a slot cannot be backed by two inventory indices`() {
        val indices = InvseeLayout.liveSlots.mapNotNull(InvseeLayout::indexOf)
        assertEquals(indices.size, indices.distinct().size, "two window slots write to the same bag slot")
        assertEquals((0..40).toSet(), indices.toSet(), "every bag slot is shown exactly once")
    }

    /** Armour is the one place where "does this item go here" is a question the server also asks. */
    @Test fun `each armour slot names the equipment it requires`() {
        assertEquals("HEAD", InvseeLayout.requiresEquipment(36))
        assertEquals("CHEST", InvseeLayout.requiresEquipment(37))
        assertEquals("LEGS", InvseeLayout.requiresEquipment(38))
        assertEquals("FEET", InvseeLayout.requiresEquipment(39))
        assertNull(InvseeLayout.requiresEquipment(0), "a storage slot takes anything")
        assertNull(InvseeLayout.requiresEquipment(40), "the offhand takes anything")
    }

    @Test fun `room is never negative`() {
        assertEquals(64, InvseeLayout.roomIn(amount = 0, maxStack = 64))
        assertEquals(0, InvseeLayout.roomIn(amount = 64, maxStack = 64))
        assertEquals(0, InvseeLayout.roomIn(amount = 65, maxStack = 64))
    }
}