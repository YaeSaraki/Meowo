package com.meowo.platform

import java.util.Locale

/**
 * The two bindings that open a shadow box, kept free of Bukkit so the rules are unit testable.
 *
 * MockBukkit's `PersistentDataContainer` is not trustworthy for a cross-plugin contract — if the
 * tag cannot be read, a plain shulker box would silently become a shadow box. The context also
 * cannot be driven by real events for the same reason [HatPolicy] exists. So the *decision* lives
 * here as plain data and the context only supplies the answers.
 *
 * Only the two requested bindings are listed. The inventory binding is shift + **right**-click, and
 * the view guard is what keeps it harmless: the click is only ever taken on the player's **own**
 * inventory screen (`InventoryView#getType() == CRAFTING`), so a box sitting in a chest keeps the
 * vanilla quick-move that would move it into the chest. Shift + left-click in the player's own
 * inventory is likewise left alone — vanilla uses it to move between the hotbar and the storage
 * rows, and a player still needs that for every other item.
 */
object ShadowBoxTrigger {

    /** How the player asked to open the box. */
    enum class Source { INVENTORY_CLICK, HAND_INTERACT, COMMAND }

    /**
     * A decided answer: what to do, and whether the event has to be cancelled.
     *
     * [source] is null for a cancel-only answer — the offhand hand of a double-fisted box. It has
     * to be swallowed so the same right-click is not processed twice, but no box may be opened.
     */
    data class Decision(val source: Source?, val cancelEvent: Boolean, val needsTickDelay: Boolean)

    /**
     * Decides an inventory click.
     *
     * @param click `ClickType#name()`; only `SHIFT_RIGHT` opens the box.
     * @param ownInventory true when the click happened in the player's **own** inventory screen
     *   (`InventoryView#getType() == CRAFTING`). A box inside a chest keeps vanilla quick-move.
     * @param playerSlot true when the clicked raw slot is one of the player's own slots.
     * @param isBox the answer of [com.meowo.shadowbox.BoxItems] — never a `Material` comparison.
     */
    fun inventoryClick(click: String, ownInventory: Boolean, playerSlot: Boolean, isBox: Boolean): Decision? {
        if (!isBox || !ownInventory || !playerSlot) return null
        if (click != "SHIFT_RIGHT") return null
        return Decision(Source.INVENTORY_CLICK, cancelEvent = true, needsTickDelay = true)
    }

    /**
     * Decides a right-click while holding something.
     *
     * Sneaking is required, which is what makes this safe: sneaking + right-click never places a
     * block in vanilla, so an unsneaked right-click keeps placing the box like an ordinary item.
     *
     * A box in the **offhand** opens the box as well. When the main hand holds a box too, the server
     * reports the same right-click twice and the main hand wins, because that is the hand the player
     * is pointing at; the second report is swallowed rather than opening a second window.
     *
     * @param action `Action#name()`.
     * @param mainHandIsBox whether the **other** hand holds a box, judged from the player inventory.
     */
    fun handInteract(
        action: String,
        sneaking: Boolean,
        isMainHand: Boolean,
        handIsBox: Boolean,
        mainHandIsBox: Boolean,
    ): Decision? {
        if (!handIsBox || !sneaking) return null
        if (action != "RIGHT_CLICK_AIR" && action != "RIGHT_CLICK_BLOCK") return null
        if (!isMainHand && mainHandIsBox) return Decision(source = null, cancelEvent = true, needsTickDelay = false)
        return Decision(Source.HAND_INTERACT, cancelEvent = true, needsTickDelay = false)
    }

    /** `/shadowbox` is the same thing without a key, so Bedrock and mis-bound clients still work. */
    fun command(): Decision = Decision(Source.COMMAND, cancelEvent = false, needsTickDelay = false)

    /**
     * The click guards: while a box is open, nothing may move the box itself.
     *
     * Vanilla would otherwise let the player drop it (Q), swap it to the offhand (F), number-key it
     * away, or shift it into the open box, and the content written back on close would then land
     * on the wrong item. Every one of those is refused instead.
     *
     * @param playerSlot true when the click or drag touched one of the player's own slots.
     * @param isBox the answer of [com.meowo.shadowbox.BoxItems] for the item actually moved.
     */
    fun protectBox(isPlayerSlot: Boolean, isBox: Boolean): Boolean = isPlayerSlot && isBox
}
