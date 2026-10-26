package com.meowo.core

import com.meowo.api.HomeMenuAPI
import com.meowo.api.HomeMenuEntry
import com.meowo.api.MenuAPI
import com.meowo.api.UIAPI
import com.meowo.menu.MenuContext
import com.meowo.platform.CommandRouter
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.shared.event.EventBus
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID

/**
 * The menu is reachable by keybind, and its homes list pages by the player's capacity.
 *
 * Asserted against the screen the context asks the UI service for, which is the part this module owns; how
 * the window is drawn belongs to `ui` and needs a real server.
 */
class MenuNavigationTest {

    @Test fun `shift F opens menu and homes paginate through API capacity`() {
        val server = MockBukkit.mock()
        val plugin = MockBukkit.loadWith(TestPlugin::class.java, java.io.File("build/resources/main/plugin.yml"))
        plugin.description.permissions.forEach { server.pluginManager.addPermission(it) }
        val messages = Messages(plugin).apply { load("zh_CN") }
        val host = PlatformHost(plugin, EventBus { _, error -> throw error }, CommandRouter(messages), messages)
        val ui = FakeUI(messages)
        val menu = MenuContext(host)
        try {
            Bukkit.getServicesManager().register(HomeMenuAPI::class.java, object : HomeMenuAPI {
                override fun capacity(owner: UUID) = 65
                override fun entries(owner: UUID) = listOf(
                    HomeMenuEntry(65, "远方之家", 0xFFFFFF, "WHITE_BED", "world", 0.0, 64.0, 0.0, true, "bowtie"),
                )
            }, plugin, ServicePriority.Normal)
            Bukkit.getServicesManager().register(UIAPI::class.java, ui, plugin, ServicePriority.Normal)
            menu.start()
            val player = server.addPlayer("MenuUser")
            player.isSneaking = true
            val swap = PlayerSwapHandItemsEvent(player, ItemStack(Material.AIR), ItemStack(Material.AIR))
            server.pluginManager.callEvent(swap)

            assertTrue(swap.isCancelled, "shift+F is the menu's keybind")
            assertNotNull(ui.page, "the menu must open")
            // The homes entry sits in the heart, mirrored by the wallet at 24.
            assertEquals(Material.WHITE_BED, ui.page!!.buttons[20]!!.item.type)

            // The last of three pages: the home sits at slot 65, which the paging maths must land on.
            menu.openHomes(player.uniqueId, 99)
            val page = ui.page!!
            assertTrue(page.title.contains("3/3"), "expected the last page, was: ${page.title}")
            // The home occupies slot 65, i.e. the ninth cell of the third page — found rather than
            // restated, so the test does not encode the grid a second time.
            val bed = page.buttons.values.firstOrNull { it.item.type == Material.WHITE_BED }
            assertNotNull(bed, "the home must appear on the page it belongs to")

            menu.close()
            assertNull(Bukkit.getServicesManager().load(MenuAPI::class.java), "closing unregisters the service")
        } finally {
            menu.close()
            MockBukkit.unmock()
        }
    }
}
