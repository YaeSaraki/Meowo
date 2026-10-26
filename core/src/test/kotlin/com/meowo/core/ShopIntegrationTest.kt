package com.meowo.core

import com.meowo.api.EconomyAPI
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 喵达 ModaCat 商店, end to end.
 *
 * Its own class rather than another section of [MeowoRuntimeTest], and not only for tidiness:
 * that suite is **order-sensitive** in a way nobody intended. Its teleport particle test compares
 * `WorldMock#spawnedParticles` before and after a cancelled countdown, and it passes only when a
 * particular test happens to run before it — adding *any* method to that class (an empty one is
 * enough) moves the order and turns it red. The shop's ten cases would have taken it down with
 * them and buried the reason in an unrelated failure.
 *
 * The arithmetic itself is pinned without a server in `ShopMathTest`; this suite covers the parts
 * that only exist in a running plugin: the confirmation before selling everything, the money
 * leaving the wallet, the ore leaving the bag, and the two gestures on a price cell.
 */
class ShopIntegrationTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var runtime: MeowoRuntime
    private lateinit var ui: FakeUI

    @BeforeEach fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.loadWith(TestPlugin::class.java, java.io.File("build/resources/main/plugin.yml"))
        // MockBukkit does not register descriptor permissions the way the real Paper loader does.
        plugin.description.permissions.forEach { server.pluginManager.addPermission(it) }
        plugin.saveDefaultConfig()
        configure { it.set("contexts.render", false) }
        runtime = MeowoRuntime(plugin) { ui }
        ui = FakeUI(runtime.messages)
        runtime.start()
    }

    @AfterEach fun teardown() {
        try { if (::runtime.isInitialized) runtime.close() } finally { MockBukkit.unmock() }
    }

    private fun configure(action: (YamlConfiguration) -> Unit) {
        val file = plugin.dataFolder.resolve("config.yml")
        val yaml = YamlConfiguration.loadConfiguration(file); action(yaml); yaml.save(file)
    }

    /** Chinese by default, because this server's bundle is zh_CN and the assertions are written in it. */
    private fun addPlayer(name: String): PlayerMock {
        val player = server.addPlayer(name)
        player.setLocale(Locale.SIMPLIFIED_CHINESE)
        return player
    }

    private fun api() = requireNotNull(Bukkit.getServicesManager().load(EconomyAPI::class.java))

    /** The wallet, read the way the economy suite reads it: off the tick thread, then one tick. */
    private fun balance(player: PlayerMock): BigDecimal {
        val result = api().balance(player.uniqueId).toCompletableFuture().get(5, TimeUnit.SECONDS)
        server.scheduler.performTicks(1)
        return result
    }

    /** Both a player and the console are message targets; one reader serves them both. */
    private fun messagesOf(target: org.mockbukkit.mockbukkit.command.MessageTarget): String =
        generateSequence { target.nextMessage() }.toList().joinToString("\n")

    /** Routes a cell's action the way a player typing it would reach the same route. */
    private fun selling(player: PlayerMock) {
        // As the player who is looking at the page, which is who the renderer runs it as.
        ui.dispatch = { who, line -> server.dispatchCommand(Bukkit.getPlayer(who) ?: player, line) }
    }

    /**
     * The content slot an ore's cell was drawn into, so a click does not depend on list order.
     *
     * The first page's content slots are `ui.yml`'s `content-slots`, three rows of seven starting
     * at 10; every ore in the shipped table fits inside them, so no paging is involved here.
     */
    private fun slotOf(action: String): Int {
        val index = ui.elements.indexOfFirst { it.action == action }
        assertTrue(index >= 0, "no cell runs '$action': ${ui.elements.map { it.action }}")
        val row = index / 7
        assertTrue(row < 3, "the shipped table must fit on one page")
        return listOf(10, 19, 28)[row] + index % 7
    }

    @Test fun `the main menu offers the shop and it opens the price list`() {
        val player = addPlayer("Shopper")
        selling(player)
        server.dispatchCommand(player, "menu")
        server.scheduler.performTicks(1)
        // The shop sits in the heart, mirrored by the pomodoro entry at 21.
        val button = requireNotNull(ui.page!!.buttons[23]) { "the main menu has no cell at 23" }
        assertEquals("sell gui", button.action, "the main menu must lead to the price list, not to a sale")
        server.dispatchCommand(player, "sell gui")
        assertEquals("shop", ui.lastScreen)
        assertEquals(17, ui.elements.size, "one cell per ore the table prices")
        assertTrue(ui.page!!.title.contains("喵达"), "and it says whose shop it is: ${ui.page!!.title}")
    }

    @Test fun `selling one ore pays for it and leaves everything else in the bag`() {
        val player = addPlayer("Miner")
        player.inventory.setItem(5, ItemStack(Material.COAL_ORE, 10))
        player.inventory.setItem(9, ItemStack(Material.DIAMOND_ORE, 1))
        server.dispatchCommand(player, "sell all COAL_ORE")
        server.scheduler.performTicks(1)
        assertEquals(BigDecimal("30.00"), balance(player), "ten coal at three each")
        assertNull(player.inventory.getItem(5), "the coal is gone")
        assertEquals(Material.DIAMOND_ORE, player.inventory.getItem(9)?.type, "and nothing else is")
    }

    @Test fun `deepslate pays the same as the surface ore`() {
        val player = addPlayer("Digger")
        player.inventory.setItem(5, ItemStack(Material.DEEPSLATE_EMERALD_ORE, 2))
        player.inventory.setItem(6, ItemStack(Material.EMERALD_ORE, 2))
        server.dispatchCommand(player, "sell all DEEPSLATE_EMERALD_ORE")
        server.scheduler.performTicks(1)
        assertEquals(BigDecimal("2000.00"), balance(player))
        assertEquals(2, player.inventory.getItem(6)?.amount, "the surface ore was never sold")
    }

    @Test fun `sell hand sells the stack under the cursor and nothing else`() {
        val player = addPlayer("Handful")
        player.inventory.setItemInMainHand(ItemStack(Material.EMERALD_ORE, 2))
        player.inventory.setItem(20, ItemStack(Material.EMERALD_ORE, 5))
        server.dispatchCommand(player, "sell hand")
        server.scheduler.performTicks(1)
        assertEquals(BigDecimal("2000.00"), balance(player))
        assertEquals(5, player.inventory.getItem(20)?.amount, "the rest of the emerald stays in the bag")
    }

    @Test fun `selling everything asks first and pays only when the answer is yes`() {
        val player = addPlayer("Careful")
        player.inventory.setItem(3, ItemStack(Material.GOLD_ORE, 2))
        server.dispatchCommand(player, "sell")
        server.scheduler.performTicks(1)
        assertEquals(BigDecimal("0.00"), balance(player), "nothing is paid before the question is answered")
        assertEquals("confirm", ui.lastScreen, "and the question is the plugin's own confirmation page")
        assertEquals(2, player.inventory.getItem(3)?.amount, "the ore is still there while the question is open")

        ui.click(15) // the confirm screen's "yes" pillar
        server.scheduler.performTicks(1)
        assertEquals(BigDecimal("100.00"), balance(player), "two gold ore at fifty each")
        assertNull(player.inventory.getItem(3))
    }

    @Test fun `answering no leaves the ore where it was`() {
        val player = addPlayer("Unsure")
        player.inventory.setItem(3, ItemStack(Material.GOLD_ORE, 2))
        server.dispatchCommand(player, "sell")
        server.scheduler.performTicks(1)
        ui.click(11) // the confirm screen's "no" pillar
        server.scheduler.performTicks(1)
        assertEquals(BigDecimal("0.00"), balance(player))
        assertEquals(2, player.inventory.getItem(3)?.amount, "backing out is a refusal, not a sale")
    }

    @Test fun `a bag the shop cannot use is refused rather than emptied`() {
        val player = addPlayer("Dirt")
        player.inventory.setItem(4, ItemStack(Material.DIRT, 64))
        messagesOf(player)
        server.dispatchCommand(player, "sell")
        server.scheduler.performTicks(1)
        assertTrue(messagesOf(player).contains("没有能回收"), "and it says so in a sentence")
        assertEquals(64, player.inventory.getItem(4)?.amount, "dirt is not ore and stays where it is")
    }

    @Test fun `selling something the shop does not buy says what it did not buy`() {
        val player = addPlayer("Digging")
        player.inventory.setItemInMainHand(ItemStack(Material.DIRT, 1))
        messagesOf(player)
        server.dispatchCommand(player, "sell hand")
        val said = messagesOf(player)
        assertTrue(said.contains("不收"), "the hand was read and refused: $said")
    }

    @Test fun `a price cell sells all of that ore and a right click sells the main hand`() {
        val player = addPlayer("Clicker")
        selling(player)
        player.inventory.setItem(8, ItemStack(Material.COAL_ORE, 4))
        player.inventory.setItem(21, ItemStack(Material.COAL_ORE, 6))
        player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_ORE, 1))
        server.dispatchCommand(player, "sell gui")

        ui.click(slotOf("sell all COAL_ORE"))
        server.scheduler.performTicks(1)
        assertEquals(BigDecimal("30.00"), balance(player), "ten coal in two slots is one kind of ore")
        assertNull(player.inventory.getItem(8))
        assertNull(player.inventory.getItem(21))

        ui.click(slotOf("sell all DIAMOND_ORE"), right = true)
        server.scheduler.performTicks(1)
        assertEquals(BigDecimal("130.00"), balance(player), "the right click sold only the main hand")
    }

    @Test fun `an unknown ore name is refused rather than guessed at`() {
        val player = addPlayer("Guesser")
        player.inventory.setItem(3, ItemStack(Material.GOLD_ORE, 1))
        messagesOf(player)
        server.dispatchCommand(player, "sell all NOT_AN_ORE")
        assertTrue(messagesOf(player).contains("不认"), "a typo must not sell something else")
        assertEquals(1, player.inventory.getItem(3)?.amount)
        assertEquals(BigDecimal("0.00"), balance(player))
    }

    @Test fun `the console cannot sell and does not get a price list`() {
        messagesOf(server.consoleSender)
        server.dispatchCommand(server.consoleSender, "sell")
        server.dispatchCommand(server.consoleSender, "sell gui")
        val said = messagesOf(server.consoleSender)
        assertTrue(said.contains("只能由玩家执行"), "the shop is a player's bag, not a console's: $said")
    }

    @Test fun `the price table follows a configured change`() {
        configure { it.set("shop.prices.COAL_ORE", "7.00") }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        val player = addPlayer("Rebuilt")
        player.inventory.setItem(2, ItemStack(Material.COAL_ORE, 2))
        server.dispatchCommand(player, "sell all COAL_ORE")
        server.scheduler.performTicks(1)
        assertEquals(BigDecimal("14.00"), balance(player), "the operator's price wins, and coal stays in the table")
    }
}
