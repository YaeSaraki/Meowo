package com.meowo.core

import com.meowo.api.AvatarAPI
import com.meowo.api.event.AvatarChanged
import com.meowo.menu.MenuContext
import com.meowo.platform.CommandRouter
import com.meowo.platform.MenuChrome
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.shared.event.EventBus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock

/**
 * The main menu, as the shipped `ui.yml` declares it.
 *
 * The layout used to live in `config.yml`'s `menu.items` and this test read the rendered inventory. It now
 * reads the screen the menu asked the UI service for — the same cells, without needing a real server to draw
 * them. What stays worth pinning is the **shape**: the menu is a heart, and a heart that is one cell off
 * centre is still a heart to the code and a squashed one to the player.
 */
class MenuChromeOnMainMenuTest {

    open class TestPlugin : org.bukkit.plugin.java.JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var messages: Messages
    private lateinit var ui: FakeUI
    private lateinit var menu: MenuContext

    /** The bus the menu listens on, so a test can tell it that the player's avatar moved. */
    private lateinit var bus: EventBus

    /** The avatar service the menu reads the two cells from; a stand-in for the `avatar` context. */
    private lateinit var avatar: AvatarAPI

    /** What the avatar double reports, so a test can move it. */
    private var scale = 1.0
    private var phantomsBlocked = false

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.loadWith(TestPlugin::class.java, java.io.File("build/resources/main/plugin.yml"))
        plugin.description.permissions.forEach { server.pluginManager.addPermission(it) }
        messages = Messages(plugin).apply { load("zh_CN") }
        bus = EventBus { _, error -> throw error }
        val host = PlatformHost(plugin, bus, CommandRouter(messages), messages)
        ui = FakeUI(messages)
        menu = MenuContext(host)
        menu.start()
        // The UI service is what the context talks to; the double stands in for it.
        Bukkit.getServicesManager().register(com.meowo.api.UIAPI::class.java, ui, plugin, ServicePriority.Normal)
        // The menu asks the avatar context how big the player is and whether the night phantoms are
        // looking for them. Without the service it hides both cells, so the double answers "an ordinary
        // body, and the phantoms still come" here.
        scale = 1.0
        phantomsBlocked = false
        avatar = object : AvatarAPI {
            override fun phantomsBlocked(player: java.util.UUID) = phantomsBlocked
            override fun scaleOf(player: java.util.UUID) = scale
        }
        Bukkit.getServicesManager().register(AvatarAPI::class.java, avatar, plugin, ServicePriority.Normal)
    }

    @AfterEach fun tearDown() {
        menu.close()
        MockBukkit.unmock()
    }

    /** Opens the main menu the way shift+F does, and returns the drawn page. */
    private fun openMainMenu(): FakeUI.Page {
        val player = server.addPlayer("MenuUser")
        // MockBukkit hands every player an ENGLISH client locale; this suite reads the Chinese wording.
        player.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        viewer = player
        player.isSneaking = true
        server.pluginManager.callEvent(PlayerSwapHandItemsEvent(player, ItemStack(Material.AIR), ItemStack(Material.AIR)))
        return requireNotNull(ui.page) { "shift+F must open the menu" }
    }

    /** Whoever is looking at the menu; the repaint is addressed to them. */
    private lateinit var viewer: PlayerMock

    private fun name(cell: FakeUI.Cell): String =
        PlainTextComponentSerializer.plainText().serialize(cell.item.itemMeta?.displayName() ?: Component.empty())

    /** The mirror of a slot about the centre column of its row. */
    private fun mirror(slot: Int): Int = (slot / 9) * 9 + (8 - slot % 9)

    @Test fun `the shipped menu draws a heart`() {
        val page = openMainMenu()
        assertEquals(6, page.rows)

        // The outline: the two lobes, the widest row's sides, the tapering sides and the tip.
        for (slot in listOf(11, 12, 14, 15, 19, 25, 29, 33, 39, 40, 41)) {
            val cell = requireNotNull(page.buttons[slot]) { "slot $slot must be drawn" }
            assertEquals(Material.AMETHYST_BLOCK, cell.item.type, "slot $slot is part of the heart's outline")
        }

        // The cleft in the middle of the top row is what makes it a heart and not a blob.
        assertNull(page.buttons[13], "the cleft must stay a hole")

        // The four functions, in the heart.
        assertEquals(Material.WHITE_BED, page.buttons[20]!!.item.type, "homes")
        assertEquals(Material.AMETHYST_CLUSTER, page.buttons[21]!!.item.type, "pomodoro")
        assertEquals(Material.IRON_INGOT, page.buttons[23]!!.item.type, "shop")
        assertEquals(Material.GOLD_NUGGET, page.buttons[24]!!.item.type, "wallet")

        // The rail the whole plugin shares.
        assertEquals(Material.BARRIER, page.buttons[49]!!.item.type, "close")
        assertEquals(Material.BEDROCK, page.buttons[45]!!.item.type, "the bedrock floor")
        assertEquals(Material.PURPLE_STAINED_GLASS_PANE, page.buttons[46]!!.item.type, "the glass rail")
        assertNotNull(page.buttons[46]!!.item.itemMeta?.displayName(), "a rail cell must be named")
        assertTrue(
            (page.buttons[46]!!.item.itemMeta?.lore() ?: emptyList()).isNotEmpty(),
            "a rail cell must explain what clicking it does",
        )

        // The chrome frames it, and content the menu never claimed stays empty.
        assertEquals(MenuChrome.frame(6)[0]?.type, page.buttons[0]?.item?.type, "the corner is the chrome's")
        // Row 4 (slots 36..44) holds only the heart's tip: the cells beside it are nobody's.
        assertNull(page.buttons[37], "an unclaimed content slot must stay empty")
    }

    @Test fun `the heart is mirror-symmetric about its centre column`() {
        val page = openMainMenu()

        // Every cell the heart draws must have its mirror drawn too: one cell off centre is a squashed
        // heart, and it is exactly the kind of mistake a config edit makes.
        val drawn = page.buttons.keys.filter { it in 9..44 }
        for (slot in drawn) {
            assertTrue(mirror(slot) in drawn, "slot $slot is drawn but its mirror ${mirror(slot)} is not")
        }

        // The outline is one material all the way round, so the shape reads as one shape.
        val outline = listOf(11, 12, 14, 15, 19, 25, 29, 33, 39, 40, 41)
        for (slot in outline) {
            val cell = requireNotNull(page.buttons[slot]) { "slot $slot must be drawn" }
            assertEquals(Material.AMETHYST_BLOCK, cell.item.type, "slot $slot is outline")
            assertEquals("@none", cell.action, "slot $slot is decoration and must not act")
            assertTrue(
                (cell.item.itemMeta?.lore() ?: emptyList()).isNotEmpty(),
                "slot $slot must explain that clicking it does nothing",
            )
        }
    }

    @Test fun `the heart is not a colonnade`() {
        val page = openMainMenu()

        // A function on the head row normally stands on a house pillar. This screen draws a shape
        // instead, so the shafts would cut the heart in half — the cells under the two lobes are the
        // heart's own cells, not pillars.
        assertEquals(Material.WHITE_BED, page.buttons[20]!!.item.type, "slot 20 is the heart's, not a shaft")
        assertEquals(Material.AMETHYST_CLUSTER, page.buttons[21]!!.item.type, "slot 21 is the heart's, not a shaft")
        assertEquals(Material.IRON_INGOT, page.buttons[23]!!.item.type, "slot 23 is the heart's, not a shaft")
        assertEquals(Material.GOLD_NUGGET, page.buttons[24]!!.item.type, "slot 24 is the heart's, not a shaft")
    }

    @Test fun `the centre says it is decoration`() {
        val page = openMainMenu()
        val cell = requireNotNull(page.buttons[31]) { "slot 31 must be drawn" }
        assertEquals("@none", cell.action, "the core is decoration and must not act")
        assertTrue(
            (cell.item.itemMeta?.lore() ?: emptyList()).isNotEmpty(),
            "the core must explain that clicking it does nothing",
        )
    }

    @Test fun `every function on the main menu still runs its command`() {
        val page = openMainMenu()
        assertEquals("homes", page.buttons[20]!!.action)
        assertEquals("pomodoro gui", page.buttons[21]!!.action)
        assertEquals("sell gui", page.buttons[23]!!.action)
        assertEquals("eco balance", page.buttons[24]!!.action)
        assertEquals("@close", page.buttons[49]!!.action)
    }

    @Test fun `the two new features sit in the heart and offer their three gestures`() {
        val page = openMainMenu()

        // The phantom is a switch: one click, on or off.
        assertEquals(Material.PHANTOM_MEMBRANE, page.buttons[30]!!.item.type, "the phantom cell")
        // The full route, not the root command: `size` is a name another plugin may own, and a cell
        // that quietly runs somebody else's command is worse than one that runs nothing.
        assertEquals("meowo phantom", page.buttons[30]!!.action)

        // The size is three gestures: bigger, smaller, and back to normal.
        assertEquals(Material.SLIME_BLOCK, page.buttons[32]!!.item.type, "the size cell")
        assertEquals("meowo size up", page.buttons[32]!!.action)
        assertEquals("meowo size down", page.buttons[32]!!.right)
        assertEquals("meowo size reset", page.buttons[32]!!.shift)
    }

    /**
     * Both features are for everybody.
     *
     * The cells name a permission, so a player without it would not see them — which is only safe
     * because the permission is granted to everyone by default. That is a claim about `plugin.yml`,
     * and this is the test that keeps it honest.
     */
    @Test fun `both new cells are drawn for a player with no permissions of their own`() {
        val page = openMainMenu()
        assertNotNull(page.buttons[30], "the phantom cell is for everybody")
        assertNotNull(page.buttons[32], "the size cell is for everybody")
    }

    /**
     * Without the `avatar` context there is nobody to ask, so the two cells are hidden.
     *
     * Hidden rather than blank: a cell that is drawn but answers "unknown command" teaches a player
     * that the menu lies, and the slot should go back to the chrome instead.
     */
    @Test fun `the two cells disappear when the avatar context is not running`() {
        Bukkit.getServicesManager().unregister(AvatarAPI::class.java, avatar)
        val page = openMainMenu()
        assertNull(page.buttons[30], "without the context the phantom cell is not drawn")
        assertNull(page.buttons[32], "without the context the size cell is not drawn")
    }

    /**
     * The repaint.
     *
     * The two cells say what the player's **own** avatar is, so they have to be redrawn when it moves —
     * otherwise the player clicks the button and the menu keeps telling them the old state. The change
     * arrives as a notification, because the menu may not import the avatar context (红线 §2).
     */
    @Test fun `the menu repaints its two cells when the avatar changes`() {
        openMainMenu()
        val before = requireNotNull(requireNotNull(ui.page).buttons[30])
        assertTrue(name(before).contains("会来找你"), "the cell starts off: ${name(before)}")
        val repaints = ui.refreshes

        // The avatar context moves the switch, and says so.
        phantomsBlocked = true
        bus.publish(AvatarChanged(viewer.uniqueId))

        assertTrue(ui.refreshes > repaints, "the menu must be repainted")
        val after = requireNotNull(requireNotNull(ui.page).buttons[30])
        assertTrue(name(after).contains("不会来找你"), "the cell must say the new state: ${name(after)}")
    }

    @Test fun `the size cell shows the new size after a repaint`() {
        openMainMenu()
        scale = 1.25
        bus.publish(AvatarChanged(viewer.uniqueId))
        val lore = requireNotNull(requireNotNull(ui.page).buttons[32]).item.itemMeta?.lore()?.joinToString(" ") ?: ""
        assertTrue(lore.contains("1.25"), "the cell must show the new size: $lore")
    }
}
