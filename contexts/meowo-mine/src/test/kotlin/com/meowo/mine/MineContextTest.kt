package com.meowo.mine

import com.meowo.mine.domain.RareKind
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the material mapping. This is where a guessed name turns into a silently missing feature:
 * `Material.NETHERITE_ORE` does not exist in Paper 26.2 at all, and a name-based rule would also
 * wrongly accept `LEGACY_DIAMOND_ORE`.
 */
class MineContextTest {

    private fun kindOf(material: Material) = MineContext.kindOf(material)

    @Test fun `surface and deepslate ore map to different kinds`() {
        assertEquals(RareKind.DIAMOND, kindOf(Material.DIAMOND_ORE))
        assertEquals(RareKind.DEEPSLATE_DIAMOND, kindOf(Material.DEEPSLATE_DIAMOND_ORE))
        assertEquals(RareKind.EMERALD, kindOf(Material.EMERALD_ORE))
        assertEquals(RareKind.DEEPSLATE_EMERALD, kindOf(Material.DEEPSLATE_EMERALD_ORE))
        // The whole point of the split: a deepslate grind never lands in the surface numbers.
        assertNotEquals(kindOf(Material.DIAMOND_ORE), kindOf(Material.DEEPSLATE_DIAMOND_ORE))
    }

    @Test fun `netherite is ancient debris because there is no netherite ore block`() {
        // Material has no NETHERITE_ORE in 26.2; ANCIENT_DEBRIS is the block that yields scrap.
        assertEquals(RareKind.NETHERITE, kindOf(Material.ANCIENT_DEBRIS))
        val names = Material.entries.map { it.name }
        assertTrue(
            names.none { it == "NETHERITE_ORE" },
            "if Paper ever adds NETHERITE_ORE this test must be revisited, not silently ignored",
        )
    }

    @Test fun `the item form is not the block and must not be announced`() {
        assertNull(kindOf(Material.DIAMOND))
        assertNull(kindOf(Material.EMERALD))
        assertNull(kindOf(Material.NETHERITE_BLOCK))
        assertNull(kindOf(Material.NETHERITE_SCRAP))
    }

    @Test fun `common ores and plain blocks are not rare`() {
        for (material in listOf(Material.STONE, Material.IRON_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.COAL_ORE, Material.OAK_LOG, Material.DEEPSLATE)) {
            assertNull(kindOf(material), "$material must not be reported")
        }
    }

    @Test fun `legacy constants are not accepted`() {
        // A name-suffix rule would map LEGACY_DIAMOND_ORE to DIAMOND; an explicit mapping must not.
        assertNull(kindOf(Material.LEGACY_DIAMOND_ORE))
        assertNull(kindOf(Material.LEGACY_EMERALD_ORE))
    }

    @Test fun `every rare kind has at least one block that maps to it`() {
        for (kind in RareKind.entries) {
            val materials = Material.entries.filter { kindOf(it) == kind }
            assertTrue(materials.isNotEmpty(), "$kind has no block mapping")
        }
    }
}
