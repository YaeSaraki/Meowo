package com.meowo.shadowbox

import org.bukkit.Material
import org.bukkit.NamespacedKey
import io.papermc.paper.block.TileStateInventoryHolder
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * The shadow box item itself: how one is recognised, minted, read and written.
 *
 * A shadow box is **an ordinary block item that carries a window-sized inventory**. It does not
 * have to be one this plugin handed out: any shulker box the player found in the world, of any
 * colour, opens too, and so does a chest, a barrel or an ender chest. Its contents live in
 * the block-entity NBT the item already has (`BlockStateMeta`), exactly like a vanilla shulker box.
 * That single choice buys three properties which a server-side database would have had to
 * re-implement and keep bug-free:
 *
 * 1. **The contents follow the box.** Dropped, traded, stored in a chest, or dropped on death.
 * 2. **No orphan data and no async path.** Nothing to migrate, nothing to reconcile, no
 *    "player-owned row" to hand out, and the main thread never touches a database.
 * 3. **Vanilla already refuses to stack a filled box.** The client splits a stack as soon as the
 *    NBT differs, so one physical box can never carry two divergent sets of contents.
 *
 * The price is one honest limitation, documented in docs/minimax/14: a process crash mid-session
 * loses what is in the open window, never the box. That is exactly what vanilla does.
 */
class BoxItems(
    private val plugin: JavaPlugin,
    private val settings: ShadowBoxSettings,
    /** Language values come from the platform, so this context never loads a file itself. */
    private val messages: com.meowo.platform.Messages,
) {

    val tagKey: NamespacedKey = NamespacedKey(plugin, "shadowbox")
    val idKey: NamespacedKey = NamespacedKey(plugin, "box-id")

    /**
     * A fresh, empty box with its own id, so two boxes never share a destiny.
     *
     * Returns null when the configured material cannot carry an inventory, which
     * [ShadowBoxSettings.materialOf] already prevents at load time; the null is a defensive
     * fallback for a material that lost its container block state in a future version.
     *
     * The block state is written back with the tags, so the box is identified even when empty.
     */
    fun create(owner: Player): ItemStack? {
        val stack = ItemStack(settings.material)
        val meta = stack.itemMeta as? BlockStateMeta ?: return null
        if ((meta.blockState as? TileStateInventoryHolder)?.inventory?.size ?: 0 <= 0) return null
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, 1)
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, UUID.randomUUID().toString())
        meta.setDisplayName(messages.text(owner, "shadowbox-name"))
        meta.lore = listOf(
            messages.text(owner, "shadowbox-lore-open"),
            messages.text(owner, "shadowbox-lore-command"),
            messages.text(owner, "shadowbox-slot-count", sizeOf(stack)),
        )
        stack.itemMeta = meta
        return stack
    }

    /**
     * Any container item can be opened as a shadow box — including a plain shulker box the player
     * found in the world, of any colour. Nothing has to have been minted here, so a shulker box
     * keeps working as portable storage even if the plugin is removed.
     *
     * The test is therefore about the **item's own capability**, never about our tag: its block state
     * must hold an inventory, and that inventory must lay out as a window. `TileStateInventoryHolder`
     * rather than `Container` on purpose — an ender chest is a perfectly good 27-slot box but Bukkit
     * does **not** make it a `Container`, so asking for `Container` alone would silently exclude it.
     *
     * A hopper or a furnace has an inventory too, but 5 or 3 slots do not lay out as a window, so
     * those are not boxes.
     */
    fun isBox(item: ItemStack?): Boolean {
        val size = capacityOf(item) ?: return false
        return size in MIN_SLOTS..MAX_SLOTS && size % 9 == 0
    }

    /** Slots of the inventory this item carries, or null when it carries none. */
    private fun capacityOf(item: ItemStack?): Int? {
        if (item == null || item.type.isAir) return null
        val meta = item.itemMeta as? BlockStateMeta ?: return null
        return (meta.blockState as? TileStateInventoryHolder)?.inventory?.size
    }

    /**
     * Gives [item] this plugin's marker and an id, and returns the updated stack.
     *
     * A box the player brought from somewhere else has no id, and the write-back on close finds the
     * box **by id**. Rather than tracking a slot — which the player may change while the window is
     * open — the box is adopted on its very first use, which also makes it tracked from then on.
     * The marker is invisible in game, so the item looks exactly as it did.
     */
    fun adopt(item: ItemStack): ItemStack? {
        if (idOf(item) != null) return item
        val meta = item.itemMeta as? BlockStateMeta ?: return null
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, 1)
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, UUID.randomUUID().toString())
        val marked = item.clone()
        marked.itemMeta = meta
        return marked
    }

    /** The box's own identity, or null when [item] is not a box. */
    fun idOf(item: ItemStack?): String? {
        val meta = item?.itemMeta as? BlockStateMeta ?: return null
        return meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING)
    }

    /** Slot count of the container stored in the item; 0 when there is none. */
    fun sizeOf(item: ItemStack): Int = capacityOf(item) ?: 0

    /** A snapshot of the stored contents, for showing in a window. Cloned so the window owns them. */
    fun snapshot(item: ItemStack): Array<ItemStack> {
        val holder = (item.itemMeta as? BlockStateMeta)?.blockState as? TileStateInventoryHolder
            ?: return emptyArray()
        // Empty slots come back as null on this version; normalising them to AIR keeps the
        // array the same shape as the one `store` writes back.
        return holder.inventory.contents.map { it?.clone() ?: ItemStack(Material.AIR) }.toTypedArray()
    }

    /**
     * Writes [contents] back into the box and returns the updated item, or null when [item] is not
     * a box. The returned stack is a **new** object: the caller must put it back into the slot it
     * came from, because mutating a stack in place does not resend it to the client.
     */
    fun store(item: ItemStack, contents: Array<ItemStack>): ItemStack? {
        val meta = item.itemMeta as? BlockStateMeta ?: return null
        val state = meta.blockState as? TileStateInventoryHolder ?: return null
        val inventory = state.inventory
        for (slot in 0 until inventory.size) inventory.setItem(slot, contents.getOrNull(slot))
        meta.setBlockState(state)
        val stored = item.clone()
        stored.itemMeta = meta
        return stored
    }

    /** Finds the box carrying [id] in the player's own inventory, offhand included. */
    fun locate(player: Player, id: String): Pair<Int, ItemStack>? {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            if (idOf(item) == id) return slot to item
        }
        val offhand = inventory.itemInOffHand
        if (idOf(offhand) == id) return OFFHAND_SLOT to offhand
        return null
    }

    /** Puts [item] back where it was. */
    fun replace(player: Player, slot: Int, item: ItemStack) {
        if (slot == OFFHAND_SLOT) player.inventory.setItemInOffHand(item)
        else player.inventory.setItem(slot, item)
        player.updateInventory()
    }

    companion object {
        /** `PlayerInventory#getSize` covers storage and armour, so the offhand lives outside it. */
        const val OFFHAND_SLOT = -1
        /** A window is laid out in rows of nine, so a box holds between one and six rows. */
        const val MIN_SLOTS = 9
        const val MAX_SLOTS = 54
    }
}
