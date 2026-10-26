package com.meowo.shadowbox

import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.IllegalPluginAccessException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * The shadow box: a box you can open without placing it.
 *
 * Two bindings, both open the same window and are both open to everyone:
 *
 * | Binding | Event | How it reaches [present] |
 * |---|---|---|
 * | Shift + right-click in your own inventory | [click] | next tick, see the note there |
 * | Sneak + right-click while holding it | [interact] | immediately, no inventory is in flight |
 *
 * `/shadowbox` does the same thing for players whose client cannot reach either binding.
 *
 * ### Three things this context is careful about
 *
 * **Opening from a click is deferred.** Paper's `InventoryClickEvent` javadoc states that it fires
 * *within* a modification of the inventory and that anything changing the view the player is
 * looking at must run on the next tick. Opening a window from the handler itself desyncs the
 * client, so the box is read in the handler and the window is presented one tick later.
 *
 * **The box cannot move while its window is open.** Vanilla would happily let the player drop it
 * (Q), swap it to the offhand (F), number-key it away or shift it into the open window, and the
 * content written back on close would then land on the wrong item. Those are refused instead, and
 * a click or drag anywhere else in the player's own inventory is left alone.
 *
 * **Nothing inside the window is ever touched.** `InventoryClickEvent` fires *before* vanilla
 * applies the move, so an earlier version that emptied the window and cancelled the click did not
 * take an item out — it deleted it. Only a box is refused there, because a box parked in the window
 * would have nowhere to be written back to. See docs/minimax/14 §3.4.
 *
 * **A box in the offhand opens it too**, and the listener does not skip an event another plugin
 * already cancelled: that is a statement about the world, and opening a private window changes
 * nothing in it. See [ShadowBoxTrigger.handInteract].
 *
 * **The contents go back to the box on every exit path** — window closed, quit, death, or the
 * context being disabled. If the box is not in the player's inventory any more, which our guards
 * make impossible through normal play, the items are handed back to the player rather than
 * destroyed. See [finish].
 */
class ShadowBoxContext(
    private val host: PlatformHost,
    private val settings: ShadowBoxSettings = ShadowBoxSettings(),
) : Context, Listener {

    override val id = "shadowbox"
    private val active = AtomicBoolean(false)
    private val resources = mutableListOf<AutoCloseable>()
    /** One window per player, so the box is never on screen twice. */
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private lateinit var items: BoxItems

    /**
     * One open window. The session **is** the inventory holder, which is the only reliable way to
     * recognise our own window: the title is localisable and another plugin may reuse it, so it can
     * never be used for identity. The window cannot be passed to the constructor because
     * `Bukkit#createInventory` wants the holder first.
     */
    private class Session(val boxId: String) : InventoryHolder {
        lateinit var window: Inventory
        override fun getInventory(): Inventory = window
    }

    override fun start() {
        items = BoxItems(host.plugin, settings, host.messages)
        if (settings.requestedMaterial != settings.material.name) {
            host.plugin.logger.warning(
                "shadowbox.material=${settings.requestedMaterial} cannot hold an inventory; using ${settings.material}",
            )
        }
        active.set(true)
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("shadowbox", "shadowbox", object : Route {
            override val permission = USE_PERMISSION
            override fun execute(sender: CommandSender, args: List<String>) = run(sender, args)
            // `give` is a subcommand that hands out items, so it is neither suggested nor completed
            // for a sender who may not run it. Advertising an action that then answers "no
            // permission" is worse than not listing it.
            override fun complete(sender: CommandSender, args: List<String>): List<String> = when {
                args.size <= 1 -> listOfNotNull(
                    OPEN,
                    GIVE.takeIf { mayGive(sender) },
                )
                args.size == 2 && args[0].equals(GIVE, true) && mayGive(sender) ->
                    Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }.sorted()
                else -> emptyList()
            }
        })
    }

    // ── commands ───────────────────────────────────────────────────────────────────────────

    /** True when [sender] may mint boxes. The only action here that is not open to everyone. */
    private fun mayGive(sender: CommandSender): Boolean =
        !settings.givePermission || sender.hasPermission(GIVE_PERMISSION)

    private fun run(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            null, OPEN -> {
                val player = sender as? Player
                if (player == null) { host.messages.send(sender, "player-only"); return }
                open(player, player.inventory.itemInMainHand ?: ItemStack(Material.AIR))
            }
            GIVE -> give(sender, args)
            else -> usage(sender)
        }
    }

    /**
     * The usage text, trimmed to what [sender] can actually do.
     *
     * A line the sender would be refused is noise, and it is the same information the tab
     * completer withholds — both come from [mayGive], so the two cannot drift apart.
     */
    private fun usage(sender: CommandSender) =
        host.messages.send(sender, if (mayGive(sender)) "shadowbox-usage" else "shadowbox-usage-open")

    private fun give(sender: CommandSender, args: List<String>) {
        // `give` mints items, so it is the only gated action; the two key bindings stay open to
        // everyone whatever the operator sets here.
        if (!mayGive(sender)) { host.messages.send(sender, "no-permission"); return }
        if (args.size < 2) { usage(sender); return }
        val target = resolvePlayer(args[1])
        if (target == null) { host.messages.send(sender, "unknown-player"); return }
        // A console sender has no language of its own, so the box is named in the recipient's.
        val box = items.create(sender as? Player ?: target)
        if (box == null) { host.messages.send(sender, "shadowbox-invalid"); return }
        target.inventory.addItem(box).values.forEach { target.world.dropItemNaturally(target.location, it) }
        host.messages.send(sender, "shadowbox-given", target.name)
    }

    /** Online name first, then UUID. An offline player has no inventory to hand a box to. */
    private fun resolvePlayer(argument: String): Player? {
        Bukkit.getPlayerExact(argument)?.let { return it }
        val id = runCatching { UUID.fromString(argument) }.getOrNull() ?: return null
        return Bukkit.getPlayer(id)
    }

    // ── opening ─────────────────────────────────────────────────────────────────────────────

    private fun open(player: Player, box: ItemStack) = openFrom(player, box, BoxPlace.MainHand)

    private fun openFrom(player: Player, box: ItemStack, place: BoxPlace) {
        if (!active.get()) return
        // The key bindings reach this without a route, so the permission is checked here.
        if (!player.hasPermission(USE_PERMISSION)) { host.messages.send(player, "no-permission"); return }
        if (!items.isBox(box)) { host.messages.send(player, "shadowbox-empty-hand"); return }
        if (box.amount > 1) { host.messages.send(player, "shadowbox-split-first"); return }
        if (!reclaim(player)) { host.messages.send(player, "shadowbox-busy"); return }
        openBox(player, box, place) { host.messages.send(player, "shadowbox-open-failed") }
    }

    /** Where a box was taken from, so an adopted copy can be put back in exactly the same place. */
    private sealed interface BoxPlace {
        data object MainHand : BoxPlace
        data object OffHand : BoxPlace

        /** A player inventory index, which is what a CRAFTING view's converted slot already is. */
        data class Slot(val index: Int) : BoxPlace
    }

    /**
     * Puts the contents of [box] on screen.
     *
     * A box the plugin did not hand out has no id yet, and the write-back on close finds the box by
     * id, so it is **adopted here** and [writeBack] puts the marked copy back where the box was.
     * That is also why no slot is remembered: the id is stable and the player may move the box.
     *
     * A box that already has an id is not adopted and nothing is written back, so a session can
     * never come to belong to a *different* box than the one that was opened.
     */
    private fun openBox(player: Player, box: ItemStack, place: BoxPlace, onFailure: () -> Unit) {
        if (items.idOf(box) != null) {
            present(player, items.idOf(box)!!, items.snapshot(box))
            return
        }
        val marked = items.adopt(box) ?: return onFailure()
        val boxId = items.idOf(marked) ?: return onFailure()
        writeBack(player, place, marked)
        present(player, boxId, items.snapshot(marked))
    }

    /**
     * Puts [replacement] back where the box came from, so an adopted box is not duplicated.
     *
     * The place is passed in rather than searched for, because two **identical untagged** boxes are
     * ordinary items and compare equal: matching on the stack itself tags whichever one happens to
     * be in the main hand, which is the wrong box whenever the player opens the one in the offhand
     * or somewhere in their inventory.
     */
    private fun writeBack(player: Player, place: BoxPlace, replacement: ItemStack) {
        when (place) {
            BoxPlace.MainHand -> player.inventory.setItemInMainHand(replacement)
            BoxPlace.OffHand -> player.inventory.setItemInOffHand(replacement)
            is BoxPlace.Slot -> player.inventory.setItem(place.index, replacement)
        }
        player.updateInventory()
    }

    /**
     * True when the player may open a window now.
     *
     * A session whose window is **not** the one in front of the player is a leftover — a window that
     * never opened, or a close event that was missed — so its contents are written back and the
     * session is dropped rather than reporting the player as busy. Without this, a single failure
     * would lock that player out of every box for the rest of the session.
     */
    private fun reclaim(player: Player): Boolean {
        val existing = sessions[player.uniqueId] ?: return true
        if (player.openInventory.topInventory === existing.window) return false
        finish(player)
        return true
    }

    /** Builds the window, records the session and shows it. The holder identifies the window later. */
    private fun present(player: Player, boxId: String, contents: Array<ItemStack>) {
        if (contents.isEmpty() || contents.size % 9 != 0) { host.messages.send(player, "shadowbox-invalid"); return }
        val session = Session(boxId)
        session.window = Bukkit.createInventory(session, contents.size, host.messages.text(player, "shadowbox-title"))
        contents.forEachIndexed { slot, stack -> session.window.setItem(slot, stack) }
        sessions[player.uniqueId] = session
        try {
            player.openInventory(session.window)
        } catch (e: Exception) {
            // A session with no window in front of the player is a trap for every later binding, so
            // it is taken back here instead of being left behind.
            sessions.remove(player.uniqueId)
            host.plugin.logger.log(Level.WARNING, "Could not open a shadow box for ${player.name}", e)
            finish(player)
            host.messages.send(player, "shadowbox-open-failed")
        }
    }

    /**
     * Runs [action] on the next tick.
     *
     * Required by the click binding: a click is processed inside an inventory modification, and
     * Paper forbids changing the player's view from that handler.
     */
    private fun nextTick(player: Player, action: () -> Unit) {
        try {
            Bukkit.getScheduler().runTask(host.plugin, Runnable {
                if (host.plugin.isEnabled && active.get() && player.isOnline) action()
            })
        } catch (e: IllegalPluginAccessException) {
            if (host.plugin.isEnabled) throw e
        }
    }

    // ── inventory click ─────────────────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    fun click(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val top = event.view.topInventory
        val session = sessions[player.uniqueId]

        // Inside our own window everything is the player's to arrange, and **nothing may be touched
        // here**: InventoryClickEvent fires *before* vanilla applies the move, so clearing the window
        // and cancelling the click destroys the stack instead of taking it out. Only a box is
        // refused — the box lives in the player's inventory and its contents are written back to it,
        // so a box parked in the window would have nowhere to go.
        if (session != null && top.holder === session && event.rawSlot in 0 until top.size) {
            if (items.isBox(event.currentItem) || items.isBox(event.cursor)) event.isCancelled = true
            return
        }

        if (session != null) {
            // Raw slots, not `event.slot`: while our window is open the box sits in the *bottom*
            // inventory, whose own indices start at 0, so the own-inventory range below would miss
            // the first eight slots entirely.
            val outside = event.slotType == InventoryType.SlotType.OUTSIDE
            val inBottom = event.rawSlot >= top.size && event.rawSlot < event.view.countSlots()
            val moving = if (outside) event.cursor else event.currentItem
            // Matched by id, not by "is any container item": now that a plain shulker box counts, a
            // blanket refusal would stop the player rearranging their *other* boxes.
            if (ShadowBoxTrigger.protectBox(outside || inBottom, items.idOf(moving) == session.boxId)) {
                event.isCancelled = true
                return
            }
            // Otherwise it is an ordinary click in the player's half of our window: vanilla rules.
            if (top.holder === session) return
        }

        val decision = ShadowBoxTrigger.inventoryClick(
            event.click.name,
            ownInventory = event.view.type == InventoryType.CRAFTING,
            // **Raw** slots, never the converted `event.slot`. Bukkit renumbers the player's own
            // inventory when the view is of type CRAFTING: the hotbar (raw 36-44) comes back as
            // converted slot 0-8 and storage (raw 9-35) as 9-35. Any fixed range of converted slots
            // therefore either loses the hotbar — where the main hand, the likeliest home of a box,
            // lives — or swallows the view's own crafting grid. A raw slot at or past the top of the
            // view is the player's own half, and needs no guesswork. See docs/minimax/14 §3.5.
            playerSlot = event.rawSlot >= event.view.topInventory.size,
            isBox = items.isBox(event.currentItem),
        ) ?: return
        event.isCancelled = true
        // Read in the handler, present next tick: see the note on nextTick.
        val box = event.currentItem ?: return
        // In a CRAFTING view the converted slot *is* the player inventory index, so it names the
        // exact slot to put an adopted box back into.
        val place = BoxPlace.Slot(event.slot)
        nextTick(player) { openFrom(player, box, place) }
    }

    @EventHandler(ignoreCancelled = true)
    fun drag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val top = event.view.topInventory
        val session = sessions[player.uniqueId]
        if (session != null) {
            // A drag out of the window must not clear it either, for the same reason as [click]: the
            // source slots still hold the items when this runs. Only two drags are refused — one that
            // would park a box in the window, and one that would carry this session's box away.
            if (top.holder === session && event.newItems.values.any { items.isBox(it) }) {
                event.isCancelled = true
                return
            }
            if (event.rawSlots.any { holdsSessionBox(event, it) }) event.isCancelled = true
        }
    }

    /** True when the raw slot [raw] is in the player's own half and holds this session's box. */
    private fun holdsSessionBox(event: InventoryDragEvent, raw: Int): Boolean {
        val session = sessions[event.whoClicked.uniqueId] ?: return false
        val bottom = event.view.bottomInventory
        val index = raw - event.view.topInventory.size
        if (index !in 0 until bottom.size) return false
        return items.idOf(bottom.getItem(index)) == session.boxId
    }

    // ── sneak + right-click in hand ─────────────────────────────────────────────────────────

    /**
     * Not `ignoreCancelled`: another plugin cancelling the right-click is a statement about the
     * **world** (a protected region, a cancelled block use), and opening a private window changes
     * nothing in the world. Skipping the event then would make the binding silently depend on which
     * other plugins happen to be installed.
     */
    @EventHandler
    fun interact(event: PlayerInteractEvent) {
        if (!active.get()) return
        val player = event.player
        val decision = ShadowBoxTrigger.handInteract(
            event.action.name,
            sneaking = player.isSneaking,
            isMainHand = event.hand == null || event.hand == EquipmentSlot.HAND,
            handIsBox = items.isBox(event.item),
            mainHandIsBox = items.isBox(player.inventory.itemInMainHand),
        ) ?: return
        // The offhand hand of a box held in both hands is swallowed so the click is handled once.
        event.isCancelled = decision.cancelEvent
        if (decision.source == null) return
        val box = event.item ?: return
        val place = if (event.hand == EquipmentSlot.OFF_HAND) BoxPlace.OffHand else BoxPlace.MainHand
        if (!player.hasPermission(USE_PERMISSION)) { host.messages.send(player, "no-permission"); return }
        if (box.amount > 1) { host.messages.send(player, "shadowbox-split-first"); return }
        if (!reclaim(player)) { host.messages.send(player, "shadowbox-busy"); return }
        openBox(player, box, place) { host.messages.send(player, "shadowbox-open-failed") }
    }

    // ── writing the contents back ───────────────────────────────────────────────────────────

    @EventHandler
    fun close(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.inventory !== session.inventory) return
        finish(player)
    }

    /** Quit saves the inventory afterwards, so writing back here is what persists the contents. */
    @EventHandler
    fun quit(event: PlayerQuitEvent) = finish(event.player)

    /**
     * The box is still in the player's inventory here, and the death drops are computed from it
     * afterwards — so the contents travel with the box instead of vanishing.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun death(event: PlayerDeathEvent) = finish(event.player)

    /**
     * Returns the window's contents to the box and forgets the session.
     *
     * The box is found by id rather than by slot, so a player may rearrange their inventory while
     * the window is open. If it cannot be found, the items are handed back to the player: losing a
     * box to another plugin is bad, destroying somebody's items is worse.
     *
     * The window is emptied afterwards no matter how this ends — see [drain], which is the whole
     * reason this method is split in two.
     */
    private fun finish(player: Player) {
        val session = sessions.remove(player.uniqueId) ?: return
        try {
            save(player, session)
        } catch (e: Throwable) {
            // The session is already gone, so an exception here would silently destroy the window
            // contents. They are handed to the player instead of being lost with the stack trace.
            host.plugin.logger.log(Level.SEVERE, "Shadow box ${session.boxId} could not be saved for ${player.name}", e)
            session.window.contents.filterNotNull().filter { !it.type.isAir }.forEach { give(player, it) }
        } finally {
            drain(session.window)
        }
    }

    private fun save(player: Player, session: Session) {
        val contents = session.window.contents.map { it?.clone() ?: ItemStack(Material.AIR) }.toTypedArray()
        // A box must never be stored inside another box: it would be a second copy of an item that
        // still exists out in the world. Clicks and drags refuse to park one, so anything left here
        // arrived by another route — those are handed back to the player rather than kept.
        val (nested, mine) = contents.partition { items.isBox(it) }
        val mineArray = mine.toTypedArray()
        if (nested.isNotEmpty()) {
            host.plugin.logger.log(
                Level.WARNING, "${nested.size} box(es) were left inside ${session.boxId} by something else; returning them",
            )
            nested.forEach { give(player, it) }
        }
        val located = items.locate(player, session.boxId)
        if (located == null) return returnItems(player, session, mineArray, "left the inventory")
        val (slot, box) = located
        // A box holding enough NBT to overflow the item limit throws here rather than returning
        // null, and a throw after the session was dropped would take the contents with it.
        val stored = try {
            items.store(box, mineArray)
        } catch (e: Exception) {
            host.plugin.logger.log(Level.SEVERE, "Could not write ${session.boxId} back into its box", e)
            null
        } ?: return returnItems(player, session, mineArray, "could not be written back")
        items.replace(player, slot, stored)
    }

    /**
     * Empties the window once its contents are safe in the box or in the player's inventory.
     *
     * This is **not** the rule from §3.2 of the docs — that one is about `InventoryClickEvent`,
     * which fires *before* vanilla applies the move. Closing is different, and getting it wrong
     * duplicates everything: a window from `Bukkit#createInventory` has no block entity behind it
     * (`CraftInventoryCustom` is a bare list in NMS), so on close vanilla hands its contents back
     * to the player by itself. CraftBukkit's order is
     *
     * ```java
     * handleInventoryCloseEvent(this, reason);   // this class runs here
     * this.connection.send(...);
     * this.doCloseContainer();                   // …and vanilla refunds here
     * ```
     *
     * Saving into the box without emptying the window therefore pays the player twice. Draining
     * is safe in both worlds: if vanilla were not to refund, the window is closed and nothing
     * observes it; if it does, there is nothing left to refund.
     */
    private fun drain(window: Inventory) {
        for (slot in 0 until window.size) window.setItem(slot, null)
    }

    private fun returnItems(player: Player, session: Session, contents: Array<ItemStack>, why: String) {
        val lost = contents.count { !it.type.isAir }
        host.plugin.logger.log(
            Level.WARNING, "Shadow box ${session.boxId} $why while open for ${player.name}; returning $lost stacks",
        )
        contents.forEach { if (!it.type.isAir) give(player, it) }
    }

    /** Inventory first, and the world only when it does not fit. Nothing is ever destroyed. */
    private fun give(player: Player, item: ItemStack) {
        player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.updateInventory()
    }

    override fun close() {
        active.set(false)
        resources.asReversed().forEach { it.close() }; resources.clear()
        // Persist first, then dismiss: the close event must find no session left to write twice.
        sessions.keys.toList().forEach { id ->
            val player = Bukkit.getPlayer(id) ?: return@forEach
            finish(player)
            if (player.isOnline) player.closeInventory()
        }
        sessions.clear()
    }

    private companion object {
        const val USE_PERMISSION = "meowo.shadowbox"
        const val GIVE_PERMISSION = "meowo.shadowbox.give"
        const val OPEN = "open"
        const val GIVE = "give"
    }
}
