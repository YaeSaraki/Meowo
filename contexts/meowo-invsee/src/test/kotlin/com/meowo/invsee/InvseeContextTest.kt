package com.meowo.invsee

import com.meowo.platform.CommandRouter
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.shared.event.EventBus
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.inventory.PlayerInventoryViewMock
import org.bukkit.inventory.InventoryView
import java.util.Locale

/** MockBukkit loads a plugin from a main class, and this module has no descriptor of its own. */
open class TestPlugin : JavaPlugin()

/**
 * The transfer paths, driven through the real inventory events.
 *
 * MockBukkit fires `InventoryClickEvent` but does not run vanilla's transaction behind it, so these
 * tests assert what **this context** did — the real `PlayerInventory` before and after, and the
 * cursor — rather than a stack that vanilla would have moved. That is the right seam for this
 * feature: the whole reason the handler cancels is that vanilla must not touch these items at all.
 *
 * Each test states the invariant that matters for an admin moving somebody else's property: **every
 * item is either in the target's bag, on the admin's cursor, or in the admin's own bag.**
 */
class InvseeContextTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var bus: EventBus
    private lateinit var router: CommandRouter
    private lateinit var host: PlatformHost
    private lateinit var context: InvseeContext
    private lateinit var admin: PlayerMock
    private lateinit var target: PlayerMock

    /** Every message the context sent, as `key` plus its arguments. */
    private val said = mutableListOf<Pair<String, List<Any>>>()

    @BeforeEach fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        bus = EventBus { _, error -> throw error }
        val messages = Messages(plugin)
        router = CommandRouter(messages)
        host = PlatformHost(plugin, bus, router, messages)
        context = InvseeContext(host, talk = { _, key, args -> said += key to args.toList() }) {
                viewer, _, target -> "${target}'s bag"
            }
        context.start()
        admin = server.addPlayer("Inspector")
        target = server.addPlayer("Holder")
        listOf(admin, target).forEach { it.setLocale(Locale.SIMPLIFIED_CHINESE) }
        admin.addAttachment(plugin, "meowo.admin.invsee", true)
    }

    @AfterEach fun teardown() {
        try { context.close(); bus.close() } finally { MockBukkit.unmock() }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** Stands in for the descriptor's `Command`; the router reads the label and the arguments. */
    private val INVSEE_COMMAND = object : Command("invsee") {
        override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true
    }

    private fun run(vararg args: String) = router.onCommand(admin, INVSEE_COMMAND, "invsee", args)

    private fun window(): Inventory = admin.openInventory.topInventory

    /** Everything the target is carrying, by material name. */
    private fun bagOf(player: PlayerMock): Map<Material, Int> =
        (0 until player.inventory.size).mapNotNull { player.inventory.getItem(it) }
            .groupBy { it.type }
            .mapValues { (_, stacks) -> stacks.sumOf { it.amount } }

    private fun stackOf(player: PlayerMock, type: Material): Int? = bagOf(player)[type]

    /** A click inside the window, exactly as the server would deliver it. */
    private fun click(slot: Int, type: ClickType = ClickType.LEFT): InventoryClickEvent {
        val event = InventoryClickEvent(
            admin.openInventory, InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.PICKUP_ALL,
        )
        server.pluginManager.callEvent(event)
        return event
    }

    /** The cursor, or null when it is empty. MockBukkit answers an empty cursor with `AIR x 0`. */
    private fun cursorOf(): ItemStack? =
        admin.openInventory.cursor?.takeIf { !it.type.isAir && it.amount > 0 }

    private fun bag(slot: Int, material: Material, amount: Int) {
        target.inventory.setItem(InvseeLayout.indexOf(slot)!!, ItemStack(material, amount))
    }

    // ── opening ──────────────────────────────────────────────────────────────────────────────

    @Test fun `the route is registered and an operator gets the window`() {
        assertTrue(router.hasRoute("invsee"))
        target.inventory.setItem(9, ItemStack(Material.DIAMOND, 5))
        run("invsee", "Holder")
        assertEquals(InvseeLayout.SIZE, window().size)
        assertEquals(5, window().getItem(0)?.amount, "storage row 1 shows in the first window row")
        assertEquals("invsee-opened", said.last().first)
    }

    @Test fun `an unknown player and your own bag are both refused with a sentence`() {
        run("invsee", "Nobody")
        assertEquals("unknown-player", said.last().first)
        run("invsee", "Inspector")
        assertEquals("invsee-self", said.last().first)
        assertEquals(InventoryType.CRAFTING, admin.openInventory.type, "no window may open")
    }

    @Test fun `the hotbar is not shown where the storage rows are`() {
        // Hotbar slot 0 is PlayerInventory index 0, which is the window's **last** storage slot.
        target.inventory.setItem(0, ItemStack(Material.GOLD_INGOT, 3))
        run("invsee", "Holder")
        assertNull(window().getItem(0), "window row 1 is the target's index 9, not its hotbar")
        assertEquals(Material.GOLD_INGOT, window().getItem(InvseeLayout.HOTBAR_FIRST)?.type)
    }

    @Test fun `a second invsee while one is open says so`() {
        run("invsee", "Holder")
        run("invsee", "Holder")
        assertEquals("invsee-busy", said.last().first)
    }

    @Test fun `the filler row shows a pane and cannot reach the bag`() {
        run("invsee", "Holder")
        val filler = 41
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, window().getItem(filler)?.type)
        val event = click(filler)
        assertTrue(event.isCancelled)
        assertEquals(emptyMap<Material, Int>(), bagOf(target), "nothing was written anywhere")
    }

    // ── taking ───────────────────────────────────────────────────────────────────────────────

    @Test fun `a click takes the stack out of the bag and onto the cursor`() {
        run("invsee", "Holder")
        // Put the stack in the bag **after** opening: the window is already showing an older
        // picture, and the click must still act on the real bag rather than on what was drawn.
        bag(0, Material.DIAMOND, 7)
        click(0)
        assertNull(target.inventory.getItem(9), "the stack left the real bag")
        assertEquals(7, cursorOf()?.amount, "and is on the cursor, not lost")
        assertEquals("invsee-took", said.last().first)
        assertNull(window().getItem(0), "the window was redrawn from the bag, which is now empty")
    }

    @Test fun `a shift-click takes the stack straight into the admin's own bag`() {
        target.inventory.setItem(9, ItemStack(Material.EMERALD, 12))
        run("invsee", "Holder")
        click(0, ClickType.SHIFT_LEFT)
        assertNull(target.inventory.getItem(9))
        assertEquals(12, stackOf(admin, Material.EMERALD))
        assertNull(cursorOf(), "the cursor is not used by a shift-click")
    }

    @Test fun `a click on armour moves the armour, not something in storage`() {
        // PlayerInventory index 39 is the helmet: equipment is stored feet first.
        target.inventory.setItem(39, ItemStack(Material.IRON_HELMET))
        run("invsee", "Holder")
        assertEquals(Material.IRON_HELMET, window().getItem(36)?.type, "window slot 36 shows the helmet")
        click(36)
        assertNull(target.inventory.getItem(39), "the helmet left the real armour slot")
        assertEquals(1, cursorOf()?.amount, "and it is in the admin's hand, not in a bag slot")
        assertEquals(Material.IRON_HELMET, cursorOf()?.type)
        assertNull(target.inventory.getItem(9), "storage row 1 was never touched")
    }

    /**
     * The armour guard must not bite the other direction.
     *
     * An administrator is very often holding something else at the moment they take a helmet, and
     * refusing that would make armour impossible to lift with a full hand.
     */
    @Test fun `a shift-click takes armour even while the admin holds something else`() {
        target.inventory.setItem(39, ItemStack(Material.IRON_HELMET))
        run("invsee", "Holder")
        admin.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 5))
        // The cursor is empty in MockBukkit's event, so the hand's contents are simulated by the
        // plan itself: what matters here is that a take is never blocked by the equipment check.
        click(36, ClickType.SHIFT_LEFT)
        assertNull(target.inventory.getItem(39))
        assertEquals(1, stackOf(admin, Material.IRON_HELMET))
        assertEquals(5, stackOf(admin, Material.COBBLESTONE), "their own stack is untouched")
    }

    // ── giving and exchanging ────────────────────────────────────────────────────────────────

    @Test fun `a shift-click in the admin's own bag hands the stack over`() {
        run("invsee", "Holder")
        admin.inventory.setItem(0, ItemStack(Material.DIAMOND, 3))
        val slot = InvseeLayout.SIZE + 0
        click(slot, ClickType.SHIFT_LEFT)
        assertNull(admin.inventory.getItem(0), "the stack left the admin")
        assertEquals(3, stackOf(target, Material.DIAMOND), "and arrived in the target's real bag")
        assertEquals(3, window().getItem(InvseeLayout.HOTBAR_FIRST)?.amount)
    }

    @Test fun `an ordinary click in the admin's own bag is left to vanilla`() {
        run("invsee", "Holder")
        admin.inventory.setItem(0, ItemStack(Material.DIAMOND, 3))
        val event = click(InvseeLayout.SIZE + 0)
        assertFalse(event.isCancelled, "their own items are not ours to take")
        assertEquals(3, stackOf(admin, Material.DIAMOND))
    }

    @Test fun `a click outside the window is cancelled so nothing is dropped`() {
        run("invsee", "Holder")
        val event = InventoryClickEvent(
            admin.openInventory, InventoryType.SlotType.OUTSIDE, 0, ClickType.LEFT, InventoryAction.NOTHING,
        )
        server.pluginManager.callEvent(event)
        assertTrue(event.isCancelled, "the cursor must not end up on the floor")
    }

    /** The exchange, as an arithmetic identity: nothing is created and nothing is lost. */
    @Test fun `every transfer conserves the number of items on both sides`() {
        target.inventory.setItem(9, ItemStack(Material.DIAMOND, 4))
        target.inventory.setItem(10, ItemStack(Material.EMERALD, 9))
        admin.inventory.setItem(0, ItemStack(Material.REDSTONE, 16))
        val before = carried()
        run("invsee", "Holder")

        click(0, ClickType.SHIFT_LEFT)                       // take the diamonds
        click(InvseeLayout.SIZE + 0, ClickType.SHIFT_LEFT)   // hand the redstone over
        click(InvseeLayout.SIZE + 0)                        // and rearrange the admin's own bag

        assertEquals(before, carried(), "an item moved, it did not multiply")
        assertEquals(4, stackOf(admin, Material.DIAMOND))
        assertEquals(16, stackOf(target, Material.REDSTONE))
        assertEquals(9, stackOf(target, Material.EMERALD))
        assertNull(stackOf(target, Material.DIAMOND), "the taken stack stayed taken")
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────

    @Test fun `two administrators may watch the same player`() {
        val second = server.addPlayer("SecondInspector")
        second.setLocale(Locale.SIMPLIFIED_CHINESE)
        second.addAttachment(plugin, "meowo.admin.invsee", true)
        target.inventory.setItem(9, ItemStack(Material.DIAMOND, 4))
        run("invsee", "Holder")
        router.onCommand(second, INVSEE_COMMAND, "invsee", arrayOf("invsee", "Holder"))

        // The target moves something: **both** windows have to follow, or the older one is a stale
        // picture an operator would act on.
        target.inventory.setItem(9, ItemStack(Material.EMERALD, 2))
        server.pluginManager.callEvent(InventoryClickEvent(
            ownInventoryView(target), InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL,
        ))
        assertEquals(Material.EMERALD, window().getItem(0)?.type)
        assertEquals(Material.EMERALD, second.openInventory.topInventory.getItem(0)?.type)
    }

    @Test fun `a drag into the window is refused rather than replayed`() {
        target.inventory.setItem(9, ItemStack(Material.DIAMOND, 4))
        run("invsee", "Holder")
        val dragged = ItemStack(Material.DIAMOND, 4)
        // (view, oldCursor, newCursor, right-handed, slots the drag would fill)
        val event = InventoryDragEvent(admin.openInventory, dragged, dragged.clone(), true, mapOf(2 to dragged))
        server.pluginManager.callEvent(event)
        assertTrue(event.isCancelled)
        assertEquals("invsee-no-drag", said.last().first)
        assertEquals(4, stackOf(target, Material.DIAMOND), "the bag is untouched by a refused drag")
    }

    @Test fun `an unsupported click is cancelled and says which clicks work`() {
        run("invsee", "Holder")
        val event = click(0, ClickType.DROP)
        assertTrue(event.isCancelled)
        assertEquals("invsee-unsupported", said.last().first)
    }

    @Test fun `losing the permission closes the window`() {
        run("invsee", "Holder")
        admin.addAttachment(plugin, "meowo.admin.invsee", false)
        click(0)
        server.scheduler.performTicks(2)
        assertEquals("invsee-no-longer", said.last().first)
        assertEquals(InventoryType.CRAFTING, admin.openInventory.type)
    }

    // ── endings ──────────────────────────────────────────────────────────────────────────────

    @Test fun `the watched player leaving closes the window`() {
        run("invsee", "Holder")
        server.pluginManager.callEvent(PlayerQuitEvent(target, "afk"))
        assertEquals(InventoryType.CRAFTING, admin.openInventory.type)
    }

    @Test fun `closing the context closes every window`() {
        run("invsee", "Holder")
        context.close()
        assertEquals(InventoryType.CRAFTING, admin.openInventory.type)
    }

    @Test fun `tab completion offers players and nothing else`() {
        run("invsee", "Holder")
        assertEquals(listOf("Holder"), router.onTabComplete(admin, INVSEE_COMMAND, "invsee", arrayOf("invsee", "Ho")))
    }

    @Test fun `the target changing their own bag redraws the window`() {
        target.inventory.setItem(9, ItemStack(Material.DIAMOND, 4))
        run("invsee", "Holder")
        assertEquals(4, window().getItem(0)?.amount)
        // The target changes their bag while the window is open; nothing is taken, only the picture
        // is brought back in step.
        target.inventory.setItem(9, ItemStack(Material.EMERALD, 2))
        server.pluginManager.callEvent(InventoryClickEvent(
            ownInventoryView(target), InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL,
        ))
        assertEquals(Material.EMERALD, window().getItem(0)?.type)
        assertNotNull(window().getItem(0))
    }

    /**
     * A stand-in for the screen a player sees after pressing E.
     *
     * `player.openInventory` cannot be used when nothing is open: MockBukkit hands out a
     * `SimpleInventoryViewMock` whose top is **null**, and `InventoryClickEvent`'s constructor blows
     * up on it — which JUnit reports as a *skipped* test, so the case would sit here green without
     * ever having run. See MeowoRuntimeTest, which documents the same trap.
     */
    private fun ownInventoryView(player: PlayerMock): InventoryView =
        PlayerInventoryViewMock(player, org.bukkit.Bukkit.createInventory(null, 9, "crafting")).apply {
            type = InventoryType.CRAFTING
        }

    /** Every item on the admin and the target together: the pool this feature only ever moves. */
    private fun carried(): Int = listOf(admin, target).sumOf { player ->
        (0 until player.inventory.size).sumOf { player.inventory.getItem(it)?.amount ?: 0 }
    }
}