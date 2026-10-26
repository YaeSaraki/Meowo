package com.meowo.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The head-slot rules, tested with the durability and equippability values real Minecraft items
 * have. This stays pure so it does not depend on MockBukkit, which cannot answer
 * `Material#getEquipmentSlot`.
 */
class HatPolicyTest {

    private fun wearable(name: String, durability: Int, equippable: Boolean = false) =
        HatPolicy.isWearable(name, durability, equippable)

    @Test fun `tools are refused`() {
        for (tool in listOf(
            "DIAMOND_PICKAXE" to 1561, "IRON_AXE" to 250, "STONE_SHOVEL" to 131,
            "GOLDEN_HOE" to 63, "DIAMOND_SWORD" to 1561, "SHEARS" to 238,
            "BOW" to 384, "CROSSBOW" to 465, "TRIDENT" to 250,
            "FLINT_AND_STEEL" to 64, "BRUSH" to 64, "MACE" to 500,
        )) {
            assertFalse(wearable(tool.first, tool.second, equippable = false), "${tool.first} is a tool")
        }
    }

    @Test fun `armour is wearable even though it has durability`() {
        for (armour in listOf(
            "LEATHER_HELMET" to 55, "IRON_CHESTPLATE" to 240, "GOLDEN_LEGGINGS" to 77,
            "DIAMOND_BOOTS" to 528, "NETHERITE_HELMET" to 407, "TURTLE_HELMET" to 275,
            "ELYTRA" to 432,
        )) {
            assertTrue(wearable(armour.first, armour.second, equippable = true), "${armour.first} must be wearable")
        }
    }

    @Test fun `plain items and blocks are wearable`() {
        for (item in listOf(
            "WHITE_WOOL" to 0, "STONE" to 0, "LANTERN" to 0, "MUSIC_DISC_13" to 0,
            "DANDELION" to 0, "PUMPKIN" to 0, "FLINT" to 0, "FISHING_FLOAT" to 0,
            "BUCKET" to 0, "TOTEM_OF_UNDYING" to 0, "SNOWBALL" to 0,
        )) {
            assertTrue(wearable(item.first, item.second, equippable = false), "${item.first} must be wearable")
        }
    }

    @Test fun `a shield is wearable even when it reports no equipment slot`() {
        assertTrue(wearable("SHIELD", 336, equippable = false))
    }

    @Test fun `air is never wearable`() {
        assertFalse(HatPolicy.isWearable("AIR", 0, equippable = false))
    }

    @Test fun `putting one item on the head never duplicates the stack`() {
        // Regression: the inventory handlers used to add the remainder to the inventory while the
        // cancelled event still left the whole stack on the cursor, handing out every item twice.
        for (amount in 0..64) {
            val split = HatPolicy.splitForHead(amount)
            assertEquals(amount, split.head + split.cursor, "stack of $amount was not conserved")
            assertTrue(split.head <= 1, "at most one item may be worn, got ${split.head} for $amount")
            assertTrue(split.cursor >= 0, "the cursor cannot go negative for $amount")
        }
    }

    @Test fun `a single item leaves an empty cursor`() {
        val split = HatPolicy.splitForHead(1)
        assertEquals(1, split.head)
        assertEquals(0, split.cursor)
    }
}
