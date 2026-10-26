package com.meowo.shop

import org.bukkit.Material
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One ore in one inventory slot, as plain data.
 *
 * The slot index travels with the item because taking the ore is a **per-slot** operation: a
 * partially emptied stack has to keep the rest, and the only way to know which stack was counted
 * is to say which slot it was in. Keeping the quote and the removal derived from the same record
 * is what stops the two from disagreeing.
 */
data class OreStack(val slot: Int, val material: Material, val amount: Int)

/** What one ore is worth, and exactly which stacks make it up. */
data class ShopLine(val material: Material, val unit: BigDecimal, val taken: List<OreStack>) {
    val amount: Int get() = taken.sumOf { it.amount }

    /** `unit × amount`, rounded **down**: the shop never owes a fraction of a coin it does not hold. */
    val sum: BigDecimal get() = unit.multiply(amount.toBigDecimal()).setScale(2, RoundingMode.DOWN)
}

/** Everything a player is about to be paid for, and the price of all of it. */
data class ShopQuote(val lines: List<ShopLine>) {
    val amount: Int get() = lines.sumOf { it.amount }
    val total: BigDecimal
        get() = lines.fold(BigDecimal.ZERO) { acc, line -> acc.add(line.sum) }.setScale(2, RoundingMode.DOWN)
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * The shop's arithmetic, free of Bukkit so it can be pinned with plain values.
 *
 * Two rules are the whole contract:
 *
 * **Only what the table names is bought.** An ore with no price is not worth zero — it is not for
 * sale, so it never enters the quote at all and never gets removed from anybody's inventory.
 *
 * **The quote names the slots it took.** A total without the slots behind it cannot be applied
 * safely: the player may have moved a stack between the quote and the payment, and the removal
 * would then hit the wrong item. [ShopQuote.lines] is what [ShopContext] removes, and both come
 * out of one call so they cannot drift.
 */
object ShopMath {

    /**
     * Prices [stacks] against [prices].
     *
     * @param only restricts the quote to one material; null prices everything the table names.
     * @return lines ordered cheapest first, so the receipt reads from the common ore to the rare.
     */
    fun quote(stacks: List<OreStack>, prices: Map<Material, BigDecimal>, only: Material? = null): ShopQuote {
        val sellable = stacks.filter { it.amount > 0 && (only == null || it.material == only) }
        val priced = sellable.filter { prices.containsKey(it.material) }
        val lines = priced.groupBy { it.material }
            .map { (material, held) -> ShopLine(material, prices.getValue(material), held.sortedBy { it.slot }) }
            .sortedWith(compareBy({ it.unit }, { it.material.name }))
        return ShopQuote(lines)
    }
}