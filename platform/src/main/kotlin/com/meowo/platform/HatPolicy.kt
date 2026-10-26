package com.meowo.platform

/**
 * Decides which items may be worn on the head. Kept free of Bukkit so the rules are unit testable:
 * MockBukkit cannot answer `Material#getEquipmentSlot`, so the decision is made here from plain
 * values and the context only supplies them.
 *
 * The rule is "everything except tools", which is the behaviour server owners actually want — wool,
 * lanterns, records, flowers and even a chestplate all look fine on a head, whereas a diamond
 * pickaxe does not.
 */
object HatPolicy {

    /**
     * Durables that are not really tools. A shield is carried rather than equipped, so
     * `getEquipmentSlot` may answer null for it; it is a classic head decoration, so it stays
     * wearable regardless.
     */
    private val NOT_TOOLS = setOf("SHIELD", "TWO_HANDED")

    /**
     * A tool is an item that wears out through use and cannot be equipped.
     * Durability alone is not enough: helmets, chestplates and elytra all have durability and are
     * perfectly wearable, and are excluded by the [equippable] flag.
     *
     * @param maxDurability `Material#getMaxDurability`, 0 for items that never break.
     * @param equippable `Material#getEquipmentSlot() != null` — the item goes in an equipment slot.
     */
    fun isTool(name: String, maxDurability: Int, equippable: Boolean): Boolean =
        maxDurability > 0 && !equippable && name !in NOT_TOOLS

    /** The wearable decision, matching [isTool] but phrased for callers. */
    fun isWearable(name: String, maxDurability: Int, equippable: Boolean): Boolean =
        !isTool(name, maxDurability, equippable) && name != "AIR"

    /**
     * How a stack is divided when one item is put on the head: `head` items leave the hand and
     * `cursor` items stay on it. The two always add up to [amount].
     *
     * This is a conservation invariant, not a convenience: an inventory event that is cancelled
     * leaves the cursor untouched, so any item that is *also* placed elsewhere duplicates. Keeping
     * the remainder on the cursor is what stops that.
     */
    fun splitForHead(amount: Int): HeadSplit {
        require(amount >= 0) { "amount must not be negative" }
        val head = if (amount == 0) 0 else 1
        return HeadSplit(head = head, cursor = amount - head)
    }
}

/** Result of [HatPolicy.splitForHead]; [head] + [cursor] always equals the original amount. */
data class HeadSplit(val head: Int, val cursor: Int)
