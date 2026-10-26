package com.meowo.shop

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The pricing rules, pinned with plain values.
 *
 * These are the cases that actually pay somebody: a stack of sixty-four coal, two ores that are
 * worth different amounts in one quote, and an item the shop does not buy at all. None of them
 * can be driven through a real event in a unit test, which is exactly why the arithmetic lives
 * in [ShopMath] instead of inside the context.
 */
class ShopMathTest {

    private val prices = ShopSettings.DEFAULT

    private fun stack(slot: Int, material: Material, amount: Int) = OreStack(slot, material, amount)

    @Test fun `the shipped table prices coal at three and deepslate coal at the same`() {
        assertEquals(BigDecimal("3.00"), prices[Material.COAL_ORE])
        assertEquals(BigDecimal("3.00"), prices[Material.DEEPSLATE_COAL_ORE])
        assertEquals(BigDecimal("1000.00"), prices[Material.EMERALD_ORE])
        assertEquals(BigDecimal("1000.00"), prices[Material.DEEPSLATE_EMERALD_ORE])
    }

    @Test fun `every ore in the table has a deepslate twin unless the game has no such block`() {
        // Nether quartz is the one exception, and it is the exception that proves the rule is real:
        // there is no deepslate nether quartz in any version.
        assertTrue(prices.containsKey(Material.NETHER_QUARTZ_ORE))
        for ((ore, price) in prices) {
            if (ore.name.contains("NETHER_QUARTZ")) continue
            val twin = Material.matchMaterial(ore.name.replaceFirst("DEEPSLATE_", ""))
            assertEquals(price, prices[twin], "$ore must pay the same as its surface twin")
        }
    }

    @Test fun `a price is per item and a whole stack pays for every one of them`() {
        val quote = ShopMath.quote(listOf(stack(4, Material.COAL_ORE, 64)), prices)
        assertEquals(64, quote.amount)
        assertEquals(BigDecimal("192.00"), quote.total)
    }

    @Test fun `a quote adds up several ores cheapest first`() {
        val quote = ShopMath.quote(
            listOf(
                stack(9, Material.EMERALD_ORE, 2),
                stack(1, Material.COAL_ORE, 10),
                stack(30, Material.DIAMOND_ORE, 3),
            ),
            prices,
        )
        assertEquals(listOf(Material.COAL_ORE, Material.DIAMOND_ORE, Material.EMERALD_ORE), quote.lines.map { it.material })
        // 10 coal + 3 diamond + 2 emerald = 30 + 300 + 2000
        assertEquals(BigDecimal("2330.00"), quote.total)
    }

    @Test fun `an ore the shop does not buy is not in the quote and worth nothing`() {
        val quote = ShopMath.quote(
            listOf(stack(0, Material.COAL_ORE, 5), stack(1, Material.DIRT, 64), stack(2, Material.DIAMOND, 3)),
            prices,
        )
        assertEquals(listOf(Material.COAL_ORE), quote.lines.map { it.material })
        assertEquals(BigDecimal("15.00"), quote.total)
    }

    @Test fun `one kind of ore can be quoted on its own`() {
        val quote = ShopMath.quote(
            listOf(stack(0, Material.COAL_ORE, 10), stack(1, Material.DIAMOND_ORE, 1)),
            prices,
            only = Material.DIAMOND_ORE,
        )
        assertEquals(1, quote.amount)
        assertEquals(BigDecimal("100.00"), quote.total)
    }

    @Test fun `the quote names the exact stacks so the removal cannot hit the wrong slot`() {
        val quote = ShopMath.quote(
            listOf(stack(3, Material.COAL_ORE, 7), stack(21, Material.COAL_ORE, 64), stack(8, Material.GOLD_ORE, 1)),
            prices,
        )
        val coal = quote.lines.single { it.material == Material.COAL_ORE }
        assertEquals(listOf(3, 21), coal.taken.map { it.slot })
        assertEquals(71, coal.amount)
    }

    @Test fun `an empty or worthless bag quotes nothing at all`() {
        assertTrue(ShopMath.quote(emptyList(), prices).isEmpty)
        assertTrue(ShopMath.quote(listOf(stack(0, Material.DIRT, 64)), prices).isEmpty)
        // A zero-sized stack is a hole in the bag, not thirty free emeralds.
        assertTrue(ShopMath.quote(listOf(stack(0, Material.EMERALD_ORE, 0)), prices).isEmpty)
    }

    @Test fun `an override replaces the shipped price instead of adding to it`() {
        val cheap = ShopSettings.withOverrides(mapOf(Material.COAL_ORE to BigDecimal("4.00")))
        assertEquals(BigDecimal("4.00"), cheap.priceOf(Material.COAL_ORE))
        assertEquals(BigDecimal("5.00"), cheap.priceOf(Material.IRON_ORE), "untouched ores keep the shipped price")
        assertEquals(ShopSettings.DEFAULT.size, cheap.prices.size, "an override must not drop an ore")
    }

    @Test fun `the catalogue is ordered cheapest first and covers every ore`() {
        val catalogue = ShopSettings(prices).catalogue()
        assertEquals(catalogue.map { it.second }.sortedBy { it }, catalogue.map { it.second })
        assertEquals(17, catalogue.size, "the shipped table has seventeen entries")
    }
}