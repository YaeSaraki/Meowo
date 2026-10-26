package com.meowo.shop

import org.bukkit.Material
import java.math.BigDecimal

/**
 * What ModaCat pays for one ore.
 *
 * Prices are **per item**, never per stack: a player who hands over a stack of 64 coal and one who
 * hand over a single emerald are two different sums, and a per-stack table would quietly pay the
 * rich one sixty-four times too much. [ShopQuote] is where the multiplication happens, once.
 *
 * The table lists surface and deepslate as **separate entries with the same price**, deliberately:
 * a deepslate ore is a different material to the client, so deriving one from the other by name
 * would make the table depend on Minecraft's naming rules (`DEEPSLATE_` prefix, plus the two ores
 * that have no deepslate form at all). Writing both out is longer and cannot silently miss one.
 */
data class ShopSettings(val prices: Map<Material, BigDecimal>) {

    init {
        require(prices.isNotEmpty()) { "The shop needs at least one ore" }
        require(prices.values.all { it >= BigDecimal.ZERO }) { "A price cannot be negative" }
    }

    /** The price of one [material], or null when ModaCat does not buy it. */
    fun priceOf(material: Material): BigDecimal? = prices[material]

    /** Every ore this shop buys, cheapest first — the order the price list is drawn in. */
    fun catalogue(): List<Pair<Material, BigDecimal>> =
        prices.entries.sortedWith(compareBy({ it.value }, { it.key.name })).map { it.key to it.value }

    companion object {
        /**
         * The shipped price list.
         *
         * Nether quartz is the one ore with no deepslate form, so it appears once. Everything else
         * appears twice with the same price, which is what "deepslate pays the same" means in a
         * table that is honestly keyed by material.
         */
        val DEFAULT: Map<Material, BigDecimal> = linkedMapOf(
            Material.COAL_ORE to money(3),
            Material.DEEPSLATE_COAL_ORE to money(3),
            Material.NETHER_QUARTZ_ORE to money(5),
            Material.IRON_ORE to money(5),
            Material.DEEPSLATE_IRON_ORE to money(5),
            Material.COPPER_ORE to money(15),
            Material.DEEPSLATE_COPPER_ORE to money(15),
            Material.REDSTONE_ORE to money(20),
            Material.DEEPSLATE_REDSTONE_ORE to money(20),
            Material.GOLD_ORE to money(50),
            Material.DEEPSLATE_GOLD_ORE to money(50),
            Material.DIAMOND_ORE to money(100),
            Material.DEEPSLATE_DIAMOND_ORE to money(100),
            Material.LAPIS_ORE to money(120),
            Material.DEEPSLATE_LAPIS_ORE to money(120),
            Material.EMERALD_ORE to money(1000),
            Material.DEEPSLATE_EMERALD_ORE to money(1000),
        )

        /**
         * The shipped list with the operator's overrides folded in.
         *
         * Overrides **replace** an entry rather than adding to it: a server that pays 4 for coal
         * means 4, not 3 plus whatever it wrote. An unknown material name is dropped rather than
         * rejected — one wrong line in `shop.prices` must not stop the shop from opening at all.
         */
        fun withOverrides(overrides: Map<Material, BigDecimal>): ShopSettings {
            if (overrides.isEmpty()) return ShopSettings(DEFAULT)
            return ShopSettings(LinkedHashMap(DEFAULT).apply { putAll(overrides) })
        }

        /**
         * `3` coins written as the money the wallet holds it in: `3.00`, not `0.03`.
         *
         * `BigDecimal.valueOf(value, scale)` sets the **scale**, so it is the wrong call here and
         * quietly pays a hundredth of the price. The multiplication that actually scales a whole
         * coin is `value.toBigDecimal()`.
         */
        private fun money(value: Long): BigDecimal = BigDecimal.valueOf(value).setScale(2)
    }
}