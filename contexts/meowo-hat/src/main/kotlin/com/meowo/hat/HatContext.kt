package com.meowo.hat

import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `/hat` wears the item you are holding on your head, and the head slot in your own inventory
 * accepts the same items.
 *
 * **Anything but a tool is wearable** — wool, lanterns, records, flowers, a chestplate — which is
 * what server owners actually want. The rule lives in [HatPolicy] as a pure function.
 *
 * ### Why the inventory needs an event handler
 *
 * The **client** refuses to move a non-armor item into an armour slot, so the vanilla click never
 * produces a legal swap the server can observe. The only way to support it is to intercept
 * [InventoryClickEvent], perform the swap ourselves, then cancel. `InventoryDragEvent` is
 * cancelled outright so a drag cannot bypass the rule.
 *
 * ### Paper 26.2 API notes
 *
 * `org.bukkit.material.ArmorMaterial` / `ToolMaterial` / `SwordMaterial` no longer exist, and
 * `InventoryType` moved to `org.bukkit.event.inventory`. See docs/minimax/13.
 */
class HatContext(
    private val host: PlatformHost,
    private val settings: HatSettings = HatSettings(),
    /** Injected for tests: MockBukkit's `Material#getEquipmentSlot` is an unimplemented stub. */
    private val equippable: (Material) -> Boolean = { it.getEquipmentSlot() != null },
    /** Injected for tests: MockBukkit's `getHelmet` throws instead of returning an empty stack. */
    private val wornHelmet: (Player) -> ItemStack? = { it.inventory.helmet?.takeIf { h -> !h.type.isAir } },
    private val setHelmet: (Player, ItemStack?) -> Unit = { player, item ->
        if (item == null) player.inventory.setHelmet(ItemStack(Material.AIR)) else player.inventory.setHelmet(item)
    },
) : Context, Listener {
    override val id = "hat"
    private val active = AtomicBoolean(false)
    private val resources = mutableListOf<AutoCloseable>()

    override fun start() {
        active.set(true)
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("hat", "hat", object : Route {
            override val permission = "meowo.hat"
            override fun execute(sender: CommandSender, args: List<String>) = swap(sender)
        })
    }

    // ── /hat ────────────────────────────────────────────────────────────────────────────────

    private fun swap(sender: CommandSender) {
        if (sender !is Player) { host.messages.send(sender, "player-only"); return }
        if (!active.get()) return
        val held = sender.inventory.itemInMainHand
        val worn = wornHelmet(sender)

        // Empty hand means "take it off", so there has to be something to take off.
        if (held.type.isAir) {
            if (worn == null) { host.messages.send(sender, "hat-nothing"); return }
            sender.inventory.setItemInMainHand(worn)
            setHelmet(sender, null)
            host.messages.send(sender, "hat-off", worn.type.name.lowercase())
            return
        }
        if (!wearable(held.type)) { host.messages.send(sender, "hat-refused", held.type.name.lowercase()); return }
        // Pure swap: whatever was on the head comes back to the hand, nothing is dropped.
        setHelmet(sender, held)
        sender.inventory.setItemInMainHand(worn ?: ItemStack(Material.AIR))
        host.messages.send(sender, "hat-on", held.type.name.lowercase())
    }

    private fun wearable(type: Material): Boolean {
        if (type.isAir) return false
        if (type.name in settings.deny) return false
        if (settings.allowTools) return true
        return HatPolicy.isWearable(type.name, type.maxDurability.toInt(), equippable(type))
    }

    // ── head slot in the player inventory ────────────────────────────────────────────────────

    /**
     * Lets a player click an item onto their head. Only the player's own inventory screen is
     * handled, so chests, shulker boxes and plugin GUIs keep vanilla behaviour.
     *
     * Vanilla refuses a non-armor item in an armour slot, so letting the click run would leave the
     * item on the cursor. The placement is therefore performed here and the event cancelled.
     *
     * Uses [InventoryClickEvent#getRawSlot], **not** `getSlot`: the raw slot is unique inside the
     * view (head = 5), while `getSlot` is the index inside the *PlayerInventory* (head = 39,
     * because armour lives at 36-39 there). Matching `getSlot` against 5 silently never fires.
     *
     * ⚠️ **The cursor must be rewritten, or the stack duplicates.** Cancelling an
     * InventoryClickEvent rolls the *slot* back but leaves the cursor exactly as it was. Adding
     * the remainder to the inventory *and* leaving the full stack on the cursor therefore hands
     * out the whole stack twice. [wearFrom] is the only place allowed to consume the cursor.
     */
    @EventHandler
    fun click(event: InventoryClickEvent) {
        if (!active.get() || !settings.inventorySlot) return
        val player = event.whoClicked as? Player ?: return
        // view.type, not view.topInventory.type: the latter is nullable and is null in some mocks.
        if (event.view.type != InventoryType.CRAFTING) return
        if (event.rawSlot != HEAD_SLOT) return
        val incoming = event.cursor ?: return
        if (!wearable(incoming.type)) return
        val outgoing = wornHelmet(player)

        event.isCancelled = true
        wearFrom(player, event::setCursor, incoming, outgoing)
        host.messages.send(player, "hat-on", incoming.type.name.lowercase())
        player.updateInventory()
    }

    /**
     * Moves exactly one item of [incoming] onto the head and puts the rest back on the cursor, so
     * the total is conserved. [outgoing] goes to the inventory. Never called without cancelling
     * the event first.
     */
    private fun wearFrom(
        player: Player,
        setCursor: (ItemStack?) -> Unit,
        incoming: ItemStack,
        outgoing: ItemStack?,
    ) {
        val split = HatPolicy.splitForHead(incoming.amount)
        setCursor(if (split.cursor > 0) incoming.clone().apply { amount = split.cursor } else null)
        setHelmet(player, incoming.clone().apply { amount = split.head })
        if (outgoing != null) give(player, outgoing)
    }

    /**
     * Lets a player drag an item onto their head. Only the player's own inventory screen is
     * handled, so chests, shulker boxes and plugin GUIs keep vanilla behaviour.
     *
     * Vanilla refuses a non-armor item in an armour slot, so letting the drag run would silently
     * skip the head slot. Cancelling alone is not enough either — that aborts the whole drag and
     * the head slot ends up empty. So the placement is performed here and the event cancelled.
     *
     * A cancelled drag gives up vanilla's even distribution across the other slots. The remainder
     * therefore goes back on the cursor — **not** into the inventory, which would duplicate the
     * stack exactly as the click path used to.
     */
    @EventHandler
    fun drag(event: InventoryDragEvent) {
        if (!active.get() || !settings.inventorySlot) return
        val player = event.whoClicked as? Player ?: return
        if (event.view.type != InventoryType.CRAFTING) return
        if (!event.rawSlots.contains(HEAD_SLOT)) return
        // oldCursor is the whole stack before vanilla split it; getCursor is already reduced.
        val dragged = event.oldCursor.takeIf { !it.type.isAir } ?: return
        if (!wearable(dragged.type)) return

        val outgoing = wornHelmet(player)
        event.isCancelled = true
        wearFrom(player, event::setCursor, dragged, outgoing)
        host.messages.send(player, "hat-on", dragged.type.name.lowercase())
        player.updateInventory()
    }

    /** Never lose the old head item: inventory first, and the world only if it does not fit. */
    private fun give(player: Player, item: ItemStack) {
        val leftover = player.inventory.addItem(item)
        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.updateInventory()
    }

    override fun close() {
        active.set(false)
        resources.asReversed().forEach { it.close() }; resources.clear()
    }

    private companion object {
        /**
         * Head slot **inside the player's own inventory view**. This is a view-relative raw slot,
         * which is why the handlers match on `rawSlot` / `rawSlots` and never on `getSlot()`.
         */
        const val HEAD_SLOT = 5
    }
}
