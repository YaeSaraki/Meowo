package com.meowo.invsee

/**
 * What one click inside an invsee window does, decided from plain numbers.
 *
 * The rules an admin actually performs are two verbs — **take** and **give** — and every question
 * about a click is "which of them, and how much". Deciding that here rather than inside the
 * `InventoryClickEvent` handler is what makes it testable without a server, and it is also the only
 * way to state the conservation rule in one place: whatever the plan, the same number of items is on
 * both sides of the click afterwards.
 *
 * ## Why the click is cancelled no matter what
 *
 * `InventoryClickEvent` fires **before** vanilla applies the move, and the window this opens is a
 * mirror of somebody else's inventory — the real `PlayerInventory` is untouched by vanilla no matter
 * what the admin does. Letting vanilla run therefore writes the item into a *picture* of the bag and
 * nowhere else, and the same item is still in the target's real inventory: every click would
 * duplicate. So the handler performs the move itself and cancels, which is also why the decision has
 * to live somewhere that can be read without a GUI.
 *
 * See docs/minimax/18.
 */
object InvseeClick {

    /** The outcome of one click. Every plan except [NONE] moves a strictly non-zero amount. */
    enum class Plan {
        /** Target's stack → the admin's cursor. */
        TAKE_TO_CURSOR,

        /** Admin's cursor → the target's slot. */
        PLACE_IN_SLOT,

        /** Admin's cursor → the target's slot, joining what is already there. */
        MERGE_INTO_SLOT,

        /** The two exchange places: the cursor goes in, the target's stack comes out. */
        SWAP,

        /** Target's stack → straight into the admin's own inventory, skipping the cursor. */
        TAKE_TO_BAG,

        /** Nothing happens: both sides are empty, or the stack is the same and full. */
        NONE,
    }

    /**
     * The plan for one click.
     *
     * @param quickMove a shift-click: "move it to the other inventory", which here is the admin's
     *   own bag rather than the cursor.
     * @param cursorAmount 0 when the admin's cursor is empty.
     * @param slotAmount 0 when the target's slot is empty.
     * @param room how many more of the same item the slot can still take, from
     *   [InvseeLayout.roomIn].
     * @param sameItem whether the slot and the cursor hold the same item.
     */
    fun plan(quickMove: Boolean, cursorAmount: Int, slotAmount: Int, room: Int, sameItem: Boolean): Plan = when {
        quickMove -> if (slotAmount > 0) Plan.TAKE_TO_BAG else Plan.NONE
        cursorAmount <= 0 && slotAmount <= 0 -> Plan.NONE
        cursorAmount <= 0 -> Plan.TAKE_TO_CURSOR
        slotAmount <= 0 -> Plan.PLACE_IN_SLOT
        // Same item and no room: a full stack stays full. Swapping two stacks of diamonds would
        // leave the admin holding exactly what they already held and the target's bag unchanged,
        // which reads as the click having been swallowed.
        sameItem && room <= 0 -> Plan.NONE
        sameItem -> Plan.MERGE_INTO_SLOT
        else -> Plan.SWAP
    }

    /**
     * How many items a [Plan.MERGE_INTO_SLOT] moves.
     *
     * The whole cursor when there is room for it, otherwise exactly the room there is: anything else
     * either loses the overflow or splits a stack the client would have merged anyway.
     */
    fun mergeAmount(cursorAmount: Int, room: Int): Int = minOf(cursorAmount, room).coerceAtLeast(0)

    /**
     * The clicks this window understands.
     *
     * Left and right behave identically on purpose: the admin is moving somebody else's property,
     * and "right-click takes half" is a split they have to undo by hand afterwards. Everything else
     * — drop, hotbar swap, double-click, middle-click, creative inventory — is refused rather than
     * guessed at, because each of them has its own vanilla transaction that would run against the
     * mirror instead of the bag.
     */
    fun isSupported(leftClick: Boolean, rightClick: Boolean, shift: Boolean): Boolean =
        leftClick || rightClick || shift
}