package com.meowo.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two bindings are the whole feature, and neither can be driven through a real event in a unit
 * test, so the rules live in [ShadowBoxTrigger] and are pinned here with plain values.
 */
class ShadowBoxTriggerTest {

    // ── shift + right-click in the inventory ───────────────────────────────────────────────

    @Test fun `shift right click on a box in your own inventory opens it`() {
        val decision = ShadowBoxTrigger.inventoryClick("SHIFT_RIGHT", ownInventory = true, playerSlot = true, isBox = true)
        assertNotNull(decision)
        assertEquals(ShadowBoxTrigger.Source.INVENTORY_CLICK, decision!!.source)
        assertTrue(decision.cancelEvent, "the vanilla quick-move must be suppressed")
        assertTrue(decision.needsTickDelay, "Paper forbids changing the view inside a click")
    }

    @Test fun `a plain left click on a box keeps vanilla behaviour`() {
        assertNull(ShadowBoxTrigger.inventoryClick("LEFT", ownInventory = true, playerSlot = true, isBox = true))
    }

    @Test fun `a plain right click on a box keeps vanilla behaviour`() {
        assertNull(ShadowBoxTrigger.inventoryClick("RIGHT", ownInventory = true, playerSlot = true, isBox = true))
    }

    @Test fun `shift left click in the inventory is left to vanilla`() {
        // Vanilla moves the stack between the hotbar and the storage rows; hijacking it would take
        // that away for the box and every other item in the same gesture.
        assertNull(ShadowBoxTrigger.inventoryClick("SHIFT_LEFT", ownInventory = true, playerSlot = true, isBox = true))
    }

    @Test fun `a box in a chest is not hijacked`() {
        assertNull(ShadowBoxTrigger.inventoryClick("SHIFT_RIGHT", ownInventory = false, playerSlot = true, isBox = true))
    }

    @Test fun `the crafting grid and armour slots are not a quick open`() {
        // Slot 5 is the helmet slot; the hat context owns it.
        assertNull(ShadowBoxTrigger.inventoryClick("SHIFT_RIGHT", ownInventory = true, playerSlot = false, isBox = true))
    }

    @Test fun `an ordinary item is never hijacked`() {
        assertNull(ShadowBoxTrigger.inventoryClick("SHIFT_RIGHT", ownInventory = true, playerSlot = true, isBox = false))
    }

    // ── sneak + right-click in hand ────────────────────────────────────────────────────────

    @Test fun `sneak right click on a held box opens it in either direction`() {
        for (action in listOf("RIGHT_CLICK_AIR", "RIGHT_CLICK_BLOCK")) {
            val decision = ShadowBoxTrigger.handInteract(
                action, sneaking = true, isMainHand = true, handIsBox = true, mainHandIsBox = true,
            )
            assertNotNull(decision, action)
            assertEquals(ShadowBoxTrigger.Source.HAND_INTERACT, decision!!.source)
            assertTrue(decision.cancelEvent, "sneak + right-click on a box must not place the block")
            assertTrue(!decision.needsTickDelay, "no inventory is being modified, so this may open now")
        }
    }

    @Test fun `an unsneaked right click places the box like any other block`() {
        assertNull(ShadowBoxTrigger.handInteract(
            "RIGHT_CLICK_AIR", sneaking = false, isMainHand = true, handIsBox = true, mainHandIsBox = true,
        ))
    }

    @Test fun `a left click never opens the box`() {
        for (action in listOf("LEFT_CLICK_AIR", "LEFT_CLICK_BLOCK")) {
            assertNull(ShadowBoxTrigger.handInteract(
                action, sneaking = true, isMainHand = true, handIsBox = true, mainHandIsBox = true,
            ))
        }
    }

    @Test fun `a box in the offhand opens the box on its own`() {
        val decision = ShadowBoxTrigger.handInteract(
            "RIGHT_CLICK_AIR", sneaking = true, isMainHand = false, handIsBox = true, mainHandIsBox = false,
        )
        assertNotNull(decision, "an offhand box must be usable, not a dead end")
        assertEquals(ShadowBoxTrigger.Source.HAND_INTERACT, decision!!.source)
    }

    @Test fun `a box in both hands opens once`() {
        val decision = ShadowBoxTrigger.handInteract(
            "RIGHT_CLICK_AIR", sneaking = true, isMainHand = false, handIsBox = true, mainHandIsBox = true,
        )
        assertNotNull(decision)
        assertNull(decision!!.source, "the second report of a double-fisted box must be swallowed")
        assertTrue(decision.cancelEvent, "…but it must still not place the block")
    }

    @Test fun `an ordinary held item is never hijacked`() {
        assertNull(ShadowBoxTrigger.handInteract(
            "RIGHT_CLICK_AIR", sneaking = true, isMainHand = true, handIsBox = false, mainHandIsBox = false,
        ))
    }

    // ── the guards that keep the box still while its window is open ────────────────────────

    @Test fun `anything that would move the box in your own slots is refused`() {
        for (click in listOf("LEFT", "RIGHT", "SHIFT_LEFT", "SHIFT_RIGHT", "DROP", "CONTROL_DROP", "SWAP_OFFHAND", "NUMBER_KEY")) {
            assertTrue(ShadowBoxTrigger.protectBox(isPlayerSlot = true, isBox = true), click)
        }
    }

    @Test fun `rearranging your own items while a box is open is still allowed`() {
        assertTrue(!ShadowBoxTrigger.protectBox(isPlayerSlot = true, isBox = false))
        assertTrue(!ShadowBoxTrigger.protectBox(isPlayerSlot = false, isBox = true))
    }

    @Test fun `the command binding needs no event and no delay`() {
        val decision = ShadowBoxTrigger.command()
        assertEquals(ShadowBoxTrigger.Source.COMMAND, decision.source)
        assertTrue(!decision.cancelEvent)
        assertTrue(!decision.needsTickDelay)
    }
}
