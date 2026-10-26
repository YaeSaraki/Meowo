package com.meowo.invsee

/**
 * The window layout, as plain numbers so it can be checked without a server.
 *
 * ## Why a mapping table and not arithmetic
 *
 * `PlayerInventory` stores the hotbar at indices **0–8** and the three storage rows at **9–35**,
 * while a chest-style window is read left-to-right, top-to-bottom. Getting that the wrong way round
 * is the classic invsee bug: the window *looks* right until the admin takes what they read as the
 * fourth row and finds a stack from the hotbar in their hand. The mapping is therefore written out
 * once, here, and both directions come from the same table — [slotOf] cannot drift from [indexOf].
 *
 * | Window slot | Player inventory index | What it is |
 * |---|---|---|
 * | 0–26 | 9–35 | the three storage rows |
 * | 27–35 | 0–8 | the hotbar |
 * | 36–39 | 39–36 | helmet, chest, leggings, boots |
 * | 40 | 40 | offhand |
 * | 41–53 | — | filler, nothing behind them |
 *
 * The armour row is the one place the two orders disagree. `PlayerInventory` stores equipment **from
 * the feet up** — 36 boots, 37 leggings, 38 chestplate, 39 helmet — because that is the order of
 * `EquipmentSlot`'s tail. A window is read the other way, so the row is laid out head first and
 * **walked backwards**: `36 + offset` looks right and puts a pair of boots under the label "helmet".
 *
 * A slot with **no** index is the whole reason [indexOf] returns `Int?`: those slots exist only so the
 * window has a square shape, and a caller that read "slot 45" as inventory index 45 would be writing
 * into a slot that does not exist rather than failing.
 */
object InvseeLayout {

    /** 6 rows. The last two hold the armour, the offhand and the filler. */
    const val SIZE = 54

    /** First window slot backed by the target's first storage row. */
    const val STORAGE_FIRST = 0

    /** First window slot backed by the target's hotbar. */
    const val HOTBAR_FIRST = 27

    /** First window slot backed by the target's armour, helmet first. */
    const val ARMOR_FIRST = 36

    /** The offhand, immediately after the armour. */
    const val OFFHAND = 40

    /** Window slot → `PlayerInventory` index, `-1` where there is nothing behind the slot. */
    private val BACKING = IntArray(SIZE) { -1 }.also { table ->
        for (offset in 0..26) table[STORAGE_FIRST + offset] = 9 + offset
        for (offset in 0..8) table[HOTBAR_FIRST + offset] = offset
        // Head first in the window, and the bag stores equipment feet first: the row is walked
        // backwards on purpose. See the class comment.
        for (offset in 0..3) table[ARMOR_FIRST + offset] = 39 - offset
        table[OFFHAND] = 40
    }

    /**
     * The `PlayerInventory` index a window slot writes to, or null for a slot with nothing behind it.
     *
     * Null is what keeps a click on the filler from becoming `setItem(45, …)`: a slot with no index is
     * one the caller must refuse, not one it may invent.
     */
    fun indexOf(slot: Int): Int? = BACKING.getOrNull(slot)?.takeIf { it >= 0 }

    /** The window slot that shows `PlayerInventory` index [index], or null when there is none. */
    fun slotOf(index: Int): Int? = BACKING.indexOfFirst { it == index }.takeIf { it >= 0 }

    /** Every window slot that writes somewhere in the target's inventory. */
    val liveSlots: List<Int> = BACKING.indices.filter { BACKING[it] >= 0 }

    /**
     * True when the slot is one of the four armour slots.
     *
     * Armour holds exactly one item, so a merge into an occupied armour slot is what would otherwise
     * produce a 2-stack helmet.
     */
    fun isArmor(slot: Int): Boolean = slot in ARMOR_FIRST until ARMOR_FIRST + 4

    /**
     * The `EquipmentSlot` name an armour window slot requires, or null when it takes anything.
     *
     * **A name rather than the Bukkit enum**, so this table stays free of the platform and can be
     * asserted in a plain unit test; the context compares it against
     * `Material#getEquipmentSlot()?.name`. Armour is the one place where "does this item go here"
     * is a question the server also asks, and answering it differently from vanilla is how a stack of
     * cobblestone ends up in a helmet.
     */
    fun requiresEquipment(slot: Int): String? = when (slot - ARMOR_FIRST) {
        0 -> "HEAD"
        1 -> "CHEST"
        2 -> "LEGS"
        3 -> "FEET"
        else -> null
    }

    /** How many more of the same item a slot holding [amount] can take, never negative. */
    fun roomIn(amount: Int, maxStack: Int): Int = (maxStack - amount).coerceAtLeast(0)

    init {
        require(BACKING.size == SIZE) { "the backing table must cover the whole window" }
        require(liveSlots.size == 41) { "36 storage, 9 hotbar, 4 armour and 1 offhand are live" }
        require(liveSlots.distinct().size == liveSlots.size) { "two window slots share one inventory index" }
    }
}