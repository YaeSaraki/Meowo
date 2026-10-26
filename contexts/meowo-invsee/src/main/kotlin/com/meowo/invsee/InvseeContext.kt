package com.meowo.invsee

import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerQuitEvent
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
 * How the context says something to a sender.
 *
 * A SAM rather than a plain function type because the language keys take `{0}`-style placeholders,
 * which a Kotlin function type cannot express (`vararg` is not allowed on function-type parameters)
 * and a fixed three-argument signature would make every key without placeholders write `emptyList()`
 * at its call site.
 */
fun interface Talker {
    fun tell(sender: CommandSender, key: String, vararg args: Any)
}

/**
 * `/invsee <player>` — an operator opens somebody else's bag, looks at it, and takes or hands over
 * items.
 *
 * ### The one thing this context is careful about
 *
 * **The window is a mirror, and every transfer is applied to the real bag immediately.**
 *
 * `InventoryClickEvent` fires *before* vanilla applies the move, and the inventory on screen is a
 * copy: a `Bukkit#createInventory` window whose holder is this session. Vanilla moving an item in
 * that window moves it in the **picture** — the target's `PlayerInventory` would not change at all,
 * so every click would hand the admin a duplicate and leave the target's item exactly where it was.
 * That is why the handler performs each move itself and cancels, and why [mirror] exists only to
 * redraw what the real bag already says.
 *
 * Writing through immediately also means there is nothing to write back: the admin can be kicked,
 * the context can be disabled, the target can log out mid-window, and no stack is ever sitting in a
 * window waiting for an exit path that may never run. See docs/minimax/18 §2.
 *
 * ### The rest of the rules, and why each one is here
 *
 * **Both sides are cancelled, always.** A click inside the window is ours; a click in the admin's own
 * half is theirs, so it is left alone — except a shift-click there, which vanilla would move into the
 * mirror instead of into the target, and is therefore turned into a real transfer.
 *
 * **Every unsupported click is refused out loud.** Drop, hotbar swap, double-click and middle-click
 * each have their own vanilla transaction that would run against the picture. Rather than leaving the
 * admin pressing a key that does nothing, the window says what to do instead.
 *
 * **A drag is refused rather than reproduced.** Vanilla spreads a drag across slots by its own rule;
 * replaying that against somebody else's bag is a second implementation of item arithmetic, and the
 * one thing this context must never do is miscount.
 *
 * **The session is identified by its holder, never its title.** The title is localisable and another
 * plugin may reuse it; [Session] being the holder is the only reliable identity.
 *
 * **A stale session is reclaimed rather than reported as busy.** A window that is not the one in front
 * of the admin is a leftover — a window that never opened, or a close event that was missed — and a
 * single failure must not lock an operator out for the rest of the session.
 *
 * **The permission is re-checked on every click.** The route checks it when the command runs; a node
 * revoked while the window is open closes it at the next interaction rather than at the next one that
 * happens to be harmless.
 */
class InvseeContext(
    private val host: PlatformHost,
    private val settings: InvseeSettings = InvseeSettings(),
    /**
     * How a sender is told what happened.
     *
     * A parameter for the same reason the `avatar` context has one: `Messages` reads the plugin's own
     * language resources out of a jar, and a test fixture is a bare class next to a descriptor file,
     * so with the real sender fixed in place this could not be tested at all. The default is exactly
     * what every other context does, and a test passes a recorder instead.
     */
    private val talk: Talker = Talker { sender, key, args -> host.messages.send(sender, key, *args) },
    /**
     * The window's title, rendered in the **reader's** language.
     *
     * Its own seam for the same reason [talk] is one. A test cannot load a bundle, so without it the
     * window could not be opened at all in a fixture — and the title is the one string a player
     * reads before deciding whose bag they are looking at.
     */
    private val titleOf: (Player, String, String) -> String =
        { viewer, key, target -> host.messages.text(viewer, key, target) },
) : Context, Listener {

    override val id = "invsee"
    private val active = AtomicBoolean(false)
    private val resources = mutableListOf<AutoCloseable>()

    /** The window an admin currently has open. One per admin: a bag is either being read or not. */
    private val sessions = ConcurrentHashMap<UUID, Session>()

    /**
     * Reverse index, so a change in the watched player's own bag can be answered without a scan.
     *
     * A **set**, not one viewer: two moderators watching the same player is ordinary, and a single
     * slot would silently stop refreshing the older one's window — which looks correct and is not,
     * because an operator acting on a stale picture is the one way this window can mislead.
     */
    private val watched = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /**
     * One open window. The session **is** the inventory holder, which is the only reliable way to
     * recognise our own window: see the class comment.
     */
    private class Session(val target: UUID) : InventoryHolder {
        lateinit var window: Inventory
        override fun getInventory(): Inventory = window
    }

    /** Which way a stack moved, for the chat line and the audit line. */
    private enum class Flow(val key: String) { TOOK("invsee-took"), GAVE("invsee-gave"), SWAPPED("invsee-swapped") }

    override fun start() {
        active.set(true)
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("invsee", "invsee", object : Route {
            override val permission = PERMISSION
            override fun execute(sender: CommandSender, args: List<String>) = run(sender, args)
            // Only players who may run the command ever get here, so the list cannot leak who is online
            // to somebody without the permission.
            override fun complete(sender: CommandSender, args: List<String>): List<String> =
                if (args.size <= 1) Bukkit.getOnlinePlayers().map { it.name }
                    .filter { it.startsWith(args.firstOrNull().orEmpty(), true) }
                    .sorted()
                else emptyList()
        })
    }

    // ── /invsee <player> ───────────────────────────────────────────────────────────────────

    private fun run(sender: CommandSender, args: List<String>) {
        if (!active.get()) return
        if (sender !is Player) { talk.tell(sender, "player-only"); return }
        val name = args.getOrNull(0)
        if (name == null || args.size > 1) { talk.tell(sender, "invsee-usage"); return }
        val target = Bukkit.getPlayerExact(name)
        if (target == null) { talk.tell(sender, "unknown-player"); return }
        // Your own bag is already open, and "take from yourself" would be the one transfer whose
        // audit line reads as nonsense.
        if (target.uniqueId == sender.uniqueId) { talk.tell(sender, "invsee-self"); return }
        if (!reclaim(sender)) { talk.tell(sender, "invsee-busy"); return }
        open(sender, target)
    }

    /**
     * True when the admin may open a window now.
     *
     * A session whose window is **not** the one in front of the admin is a leftover, so it is written
     * off and the admin is let through. Reporting "already open" instead would turn one missed close
     * event into a lock that lasts the rest of the session.
     */
    private fun reclaim(viewer: Player): Boolean {
        val existing = sessions[viewer.uniqueId] ?: return true
        if (viewer.openInventory.topInventory.holder === existing) return false
        finish(viewer)
        return true
    }

    private fun open(viewer: Player, target: Player) {
        val session = Session(target.uniqueId)
        session.window = Bukkit.createInventory(
            session, InvseeLayout.SIZE, titleOf(viewer, "invsee-title", target.name),
        )
        mirror(session, target)
        sessions[viewer.uniqueId] = session
        watched.computeIfAbsent(target.uniqueId) { ConcurrentHashMap.newKeySet() }.add(viewer.uniqueId)
        try {
            viewer.openInventory(session.window)
        } catch (e: Exception) {
            // A session with no window in front of the admin would block every later /invsee, so it
            // is taken back here instead of being left behind.
            sessions.remove(viewer.uniqueId)
            unwatch(viewer.uniqueId, target.uniqueId)
            host.plugin.logger.log(Level.WARNING, "Could not open the bag of ${target.name} for ${viewer.name}", e)
            talk.tell(viewer, "invsee-open-failed")
            return
        }
        talk.tell(viewer, "invsee-opened", target.name)
    }

    // ── inventory events ───────────────────────────────────────────────────────────────────

    /**
     * Both halves of this handler exist for the same reason: **the real bag is the only truth.**
     *
     * A click by the admin inside the window moves items between the admin and the target's real
     * inventory; a click by the *watched player* in their own bag changes nothing we would learn from
     * vanilla, so the window is redrawn to match. Redrawing is enough — there is no pending state to
     * reconcile, because every transfer was applied when it was made.
     */
    @EventHandler(ignoreCancelled = true)
    fun click(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.uniqueId]
        if (session != null && event.view.topInventory.holder === session) inWindow(player, session, event)
        else refreshFor(player)
    }

    @EventHandler(ignoreCancelled = true)
    fun drag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.uniqueId]
        if (session != null && event.view.topInventory.holder === session) {
            // Only a drag that reaches into the window is refused: a drag inside the admin's own
            // half is their business and vanilla handles it.
            if (event.rawSlots.any { it in 0 until session.window.size }) {
                event.isCancelled = true
                talk.tell(player, "invsee-no-drag")
            }
            return
        }
        refreshFor(player)
    }

    /** The watched player touched their own bag, so every window onto it has to catch up. */
    private fun refreshFor(player: Player) {
        watched[player.uniqueId]?.forEach { viewerId ->
            sessions[viewerId]?.let { mirror(it, player) }
        }
    }

    /** Drops one viewer from the reverse index, and the entry itself once nobody is left. */
    private fun unwatch(viewerId: UUID, targetId: UUID) {
        val viewers = watched[targetId] ?: return
        viewers.remove(viewerId)
        if (viewers.isEmpty()) watched.remove(targetId, viewers)
    }

    private fun inWindow(viewer: Player, session: Session, event: InventoryClickEvent) {
        // The permission is re-read here rather than trusted from the command: a node revoked while
        // the window is open must end the window, not the next restart of it.
        if (!active.get() || !viewer.hasPermission(PERMISSION)) {
            event.isCancelled = true
            end(viewer, "invsee-no-longer")
            return
        }
        val target = Bukkit.getPlayer(session.target)
        if (target == null || !target.isOnline) {
            event.isCancelled = true
            end(viewer, "invsee-gone")
            return
        }
        val raw = event.rawSlot
        // The admin's own half: vanilla rules, because those are their own items.
        if (raw >= session.window.size) { give(viewer, session, target, event); return }
        // Everything else in the window is ours, and vanilla must not touch a single item of it.
        event.isCancelled = true
        // Outside the window: nothing of ours to move, and letting the drop run would throw whatever
        // is on the cursor onto the floor.
        if (raw < 0) return
        take(viewer, session, target, event)
    }

    /** A click on a slot that shows part of the target's inventory. */
    private fun take(viewer: Player, session: Session, target: Player, event: InventoryClickEvent) {
        val index = InvseeLayout.indexOf(event.rawSlot) ?: return
        val supported = InvseeClick.isSupported(
            leftClick = event.click == ClickType.LEFT || event.click == ClickType.SHIFT_LEFT,
            rightClick = event.click == ClickType.RIGHT || event.click == ClickType.SHIFT_RIGHT,
            shift = event.isShiftClick,
        )
        if (!supported) { talk.tell(viewer, "invsee-unsupported"); return }

        val held = target.inventory.getItem(index)
        val cursor = event.cursor
        val same = held != null && cursor != null && cursor.isSimilar(held)
        // Armour holds exactly one item, whatever the material's maximum stack size says. Left
        // uncapped, a second helmet would merge into the first and the bag would carry a 2-stack
        // helmet that the client cannot render.
        val room = if (same && held != null && !InvseeLayout.isArmor(event.rawSlot))
            InvseeLayout.roomIn(held.amount, held.maxStackSize) else 0
        val required = InvseeLayout.requiresEquipment(event.rawSlot)
        val plan = InvseeClick.plan(
            quickMove = event.isShiftClick,
            cursorAmount = cursor?.amount ?: 0,
            slotAmount = held?.amount ?: 0,
            room = room,
            sameItem = same,
        )
        // Checked **after** the plan, and only for the plans that put the cursor's stack into the
        // slot. Testing it before would refuse a shift-click take as well — an administrator holding
        // cobblestone could then not lift a helmet out of an armour slot at all.
        if (required != null && cursor != null &&
            plan in setOf(InvseeClick.Plan.PLACE_IN_SLOT, InvseeClick.Plan.MERGE_INTO_SLOT, InvseeClick.Plan.SWAP) &&
            cursor.type.getEquipmentSlot()?.name != required) {
            // Refused rather than attempted: Paper may reject the write outright, and a throw in the
            // middle of a transfer leaves the two sides disagreeing about where the stack is.
            talk.tell(viewer, "invsee-not-equipment", cursor.type.name.lowercase(Locale.ROOT))
            return
        }

        when (plan) {
            InvseeClick.Plan.NONE -> Unit
            InvseeClick.Plan.TAKE_TO_CURSOR -> {
                // The stack leaves the bag **now**, because a cancelled click never applies the move
                // and the cursor is the only place it would otherwise live.
                target.inventory.setItem(index, null)
                event.view.setCursor(held!!.clone())
                report(viewer, target, Flow.TOOK, held)
            }
            InvseeClick.Plan.PLACE_IN_SLOT -> {
                target.inventory.setItem(index, cursor!!.clone())
                event.view.setCursor(null)
                report(viewer, target, Flow.GAVE, cursor)
            }
            InvseeClick.Plan.MERGE_INTO_SLOT -> {
                val moved = InvseeClick.mergeAmount(cursor!!.amount, room)
                val left = cursor.amount - moved
                held!!.amount += moved
                target.inventory.setItem(index, held)
                event.view.setCursor(cursor.takeIf { left > 0 }?.apply { amount = left })
                report(viewer, target, Flow.GAVE, cursor.apply { amount = moved })
            }
            InvseeClick.Plan.SWAP -> {
                target.inventory.setItem(index, cursor!!.clone())
                event.view.setCursor(held!!.clone())
                report(viewer, target, Flow.SWAPPED, held, cursor)
            }
            InvseeClick.Plan.TAKE_TO_BAG -> {
                target.inventory.setItem(index, null)
                collect(viewer, held!!.clone())
                report(viewer, target, Flow.TOOK, held)
            }
        }
        if (plan == InvseeClick.Plan.NONE) return
        redraw(session, target, event.rawSlot)
    }

    /**
     * A click in the admin's own half.
     *
     * Only a shift-click is ours. Vanilla would move that stack into the top inventory — the mirror —
     * and the target would never see it, so it becomes a real transfer instead. An ordinary click
     * here is the admin rearranging their own bag and is left entirely alone.
     */
    private fun give(viewer: Player, session: Session, target: Player, event: InventoryClickEvent) {
        if (!event.isShiftClick) return
        event.isCancelled = true
        val index = event.rawSlot - event.view.topInventory.size
        val stack = viewer.inventory.getItem(index)?.clone() ?: return
        viewer.inventory.setItem(index, null)
        // Overflow is dropped at the target's feet rather than refused: an operator handing over a
        // full stack should not have to empty the other player's bag first to do it.
        target.inventory.addItem(stack).values.forEach { target.world.dropItemNaturally(target.location, it) }
        report(viewer, target, Flow.GAVE, stack)
        // The stack lands wherever the target's bag had room, so the whole window is redrawn rather
        // than only the slot the admin happened to click.
        mirror(session, target)
        viewer.updateInventory()
    }

    // ── redrawing ──────────────────────────────────────────────────────────────────────────

    /** Copies the whole real bag into the window, and fills the slots that show nothing. */
    private fun mirror(session: Session, target: Player) {
        for (slot in 0 until InvseeLayout.SIZE) {
            val index = InvseeLayout.indexOf(slot)
            session.window.setItem(slot, if (index == null) filler() else target.inventory.getItem(index))
        }
    }

    /** Redraws one slot, so a transfer is visible immediately without touching the other 53. */
    private fun redraw(session: Session, target: Player, slot: Int) {
        val index = InvseeLayout.indexOf(slot) ?: return
        session.window.setItem(slot, target.inventory.getItem(index))
        target.updateInventory()
    }

    /**
     * The slots that exist only to keep the window square.
     *
     * A pane rather than nothing at all: an empty gap in a grid of items reads as an item that
     * failed to load, and an admin who thinks there is an item there will click it.
     */
    private fun filler() = ItemStack(Material.GRAY_STAINED_GLASS_PANE)

    // ── bookkeeping ────────────────────────────────────────────────────────────────────────

    private fun collect(viewer: Player, stack: ItemStack) {
        viewer.inventory.addItem(stack).values.forEach { viewer.world.dropItemNaturally(viewer.location, it) }
    }

    /**
     * Says what just happened, and records it when the operator asked for an audit trail.
     *
     * Both stacks are listed for a swap because it is the one transfer with two ends, and an audit
     * line naming only the stack that came out would describe half of it.
     */
    private fun report(viewer: Player, target: Player, flow: Flow, vararg stacks: ItemStack) {
        val moved = stacks.joinToString(", ") { "${it.amount}x ${it.type.name.lowercase(Locale.ROOT)}" }
        talk.tell(viewer, flow.key, target.name, moved)
        if (settings.audit) host.plugin.logger.info("[invsee] ${viewer.name} $flow ${target.name}: $moved")
    }

    /** Ends the session and, one tick later, the window: closing a view inside a click desyncs it. */
    private fun end(viewer: Player, key: String) {
        finish(viewer)
        talk.tell(viewer, key)
        nextTick(viewer) { it.closeInventory() }
    }

    private fun finish(viewer: Player) {
        sessions.remove(viewer.uniqueId)?.let { unwatch(viewer.uniqueId, it.target) }
    }

    private fun nextTick(player: Player, action: (Player) -> Unit) {
        try {
            Bukkit.getScheduler().runTask(host.plugin, Runnable {
                if (host.plugin.isEnabled && active.get() && player.isOnline) action(player)
            })
        } catch (e: IllegalPluginAccessException) {
            // A disabled plugin makes the scheduler throw, which is a normal shutdown rather than a
            // failure; anything else is a real one.
            if (host.plugin.isEnabled) throw e
        }
    }

    @EventHandler
    fun closeWindow(event: InventoryCloseEvent) {
        val viewer = event.player as? Player ?: return
        val session = sessions[viewer.uniqueId] ?: return
        // Another window closing — a chest, say — must not drop the session behind our window.
        if (event.inventory !== session.inventory) return
        unwatch(viewer.uniqueId, session.target)
        sessions.remove(viewer.uniqueId)
    }

    /** Losing either party ends the window: there is nothing left to act on. */
    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        sessions.remove(id)?.let { unwatch(id, it.target) }
        val viewers = watched.remove(id) ?: return
        viewers.forEach { viewerId ->
            sessions.remove(viewerId)
            Bukkit.getPlayer(viewerId)?.closeInventory()
        }
    }

    /** A death clears the bag and scatters it, so a window onto it would show items that no longer exist. */
    @EventHandler
    fun death(event: PlayerDeathEvent) {
        val viewers = watched.remove(event.entity.uniqueId) ?: return
        viewers.forEach { viewerId ->
            sessions.remove(viewerId)
            Bukkit.getPlayer(viewerId)?.closeInventory()
        }
    }

    override fun close() {
        active.set(false)
        val viewers = sessions.keys.toList()
        sessions.clear()
        watched.clear()
        viewers.forEach { Bukkit.getPlayer(it)?.closeInventory() }
        resources.asReversed().forEach { it.close() }; resources.clear()
    }

    internal companion object {
        /**
         * Its own node rather than plain `meowo.admin`, so a server can hand out bag duty without
         * handing out the rest of administration — the same reasoning as `meowo.home.admin`.
         */
        const val PERMISSION = "meowo.admin.invsee"
    }
}