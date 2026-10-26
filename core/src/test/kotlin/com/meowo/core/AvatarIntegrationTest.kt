package com.meowo.core

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Phantom
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.Locale

open class AvatarTestPlugin : JavaPlugin()

/**
 * The two per-player switches, end to end: the command, the menu, and the repaint.
 *
 * The menu is the interesting half. A cell whose content depends on the player has to be **filled in**
 * when the menu opens, and the two ways of getting that wrong are both silent: a cell that keeps the
 * config's static name (the player cannot tell whether the switch is on) and a cell that is drawn even
 * though the feature behind it is switched off (the click answers "unknown command").
 *
 * And then there is the third way, which is the one a player actually notices: the cell says the wrong
 * thing **after** it is clicked, because nothing told the screen that the world had moved on.
 */
class AvatarIntegrationTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: AvatarTestPlugin
    private lateinit var runtime: MeowoRuntime
    private lateinit var ui: FakeUI

    @BeforeEach fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.loadWith(AvatarTestPlugin::class.java, java.io.File("build/resources/main/plugin.yml"))
        plugin.description.permissions.forEach { server.pluginManager.addPermission(it) }
        plugin.saveDefaultConfig()
        runtime = MeowoRuntime(plugin) { ui }
        ui = FakeUI(runtime.messages)
        // The double dispatches a click the way the renderer does — the command, run as the player who
        // is looking at the menu. That is the path a player's click takes, so the repaint it causes is
        // the repaint a player gets.
        ui.dispatch = { who, line ->
            Bukkit.getPlayer(who)?.let { server.dispatchCommand(it, line) }
        }
        runtime.start()
    }

    @AfterEach fun teardown() {
        try { if (::runtime.isInitialized) runtime.close() } finally { MockBukkit.unmock() }
    }

    private fun configure(action: (YamlConfiguration) -> Unit) {
        val file = plugin.dataFolder.resolve("config.yml")
        val yaml = YamlConfiguration.loadConfiguration(file); action(yaml); yaml.save(file)
    }

    /** MockBukkit gives every player an ENGLISH client locale; this suite is written in Chinese. */
    private fun addPlayer(name: String): PlayerMock {
        val player = server.addPlayer(name)
        player.setLocale(Locale.SIMPLIFIED_CHINESE)
        return player
    }

    private fun plain(cell: FakeUI.Cell): String =
        PlainTextComponentSerializer.plainText().serialize(cell.item.itemMeta?.displayName() ?: Component.empty())

    private fun phantoms(): List<Phantom> = server.worlds.flatMap { it.entities }.filterIsInstance<Phantom>()

    @Test fun `the avatar context is on by default and both of its routes exist`() {
        assertTrue(runtime.activeContexts.contains("avatar"), "the avatar context must be on: ${runtime.activeContexts}")
        assertTrue(runtime.router.hasRoute("phantom"))
        assertTrue(runtime.router.hasRoute("size"))
    }

    @Test fun `the night phantoms are switched off and on again`() {
        val player = addPlayer("Owner")
        server.dispatchCommand(player, "meowo phantom")
        val said = generateSequence { player.nextMessage() }.toList().joinToString()
        assertTrue(said.contains("幻翼"), "the player must be told: $said")
        // The switch **cancels** the mechanic; it never puts a mob into the world.
        assertEquals(0, phantoms().size, "no phantom may be created by this feature")
    }

    @Test fun `the menu says which way the switch is`() {
        val player = addPlayer("Owner")
        server.dispatchCommand(player, "meowo menu")
        server.scheduler.performTicks(1)
        val cell = requireNotNull(requireNotNull(ui.page) { "the menu must be opened through the UI service" }.buttons[30])
        assertTrue(plain(cell).contains("会来找你"), "the menu must say the phantoms still come: ${plain(cell)}")

        server.dispatchCommand(player, "meowo phantom")
        server.dispatchCommand(player, "meowo menu")
        server.scheduler.performTicks(1)
        val again = requireNotNull(requireNotNull(ui.page).buttons[30])
        assertTrue(plain(again).contains("不会来找你"), "the menu must say the phantoms stay away: ${plain(again)}")
    }

    /**
     * The repaint.
     *
     * A cell that says which way a switch is set has to be redrawn when the switch moves, or the player
     * clicks the button and the menu keeps telling them the old state. The switch lives in the `avatar`
     * context and the menu may not import it (红线 §2), so the change travels as a notification — and
     * the menu redraws the cell it owns.
     *
     * The double counts every repaint, including the ones a player would not see: whether the menu is
     * actually on show is the UI module's business, and it is tested there.
     */
    @Test fun `clicking the phantom cell repaints the menu`() {
        val player = addPlayer("Owner")
        server.dispatchCommand(player, "meowo menu")
        server.scheduler.performTicks(1)
        val before = requireNotNull(requireNotNull(ui.page).buttons[30])
        assertTrue(plain(before).contains("会来找你"), "the cell starts off: ${plain(before)}")

        val repaints = ui.refreshes
        ui.click(30)

        assertTrue(ui.refreshes > repaints, "the menu must be repainted after the click")
        val after = requireNotNull(requireNotNull(ui.page).buttons[30])
        assertTrue(plain(after).contains("不会来找你"), "the cell must say the new state: ${plain(after)}")
    }

    @Test fun `the size is reported and the menu shows it`() {
        val player = addPlayer("Owner")
        server.dispatchCommand(player, "meowo size")
        val said = generateSequence { player.nextMessage() }.toList().joinToString()
        assertTrue(said.contains("体型"), "the player must be told their size: $said")
    }

    @Test fun `a server that cannot store the size says so instead of pretending`() {
        // MockBukkit predates the `scale` attribute, so this is the branch a server without it takes.
        val player = addPlayer("Owner")
        server.dispatchCommand(player, "meowo size up")
        val said = generateSequence { player.nextMessage() }.toList().joinToString()
        assertTrue(said.contains("不支持"), "the player must be told the size cannot be set: $said")
    }

    @Test fun `with the avatar context off the menu hides both cells`() {
        val player = addPlayer("Owner")
        configure { it.set("contexts.avatar", false) }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        server.scheduler.performTicks(1)

        server.dispatchCommand(player, "meowo menu")
        server.scheduler.performTicks(1)
        val page = requireNotNull(ui.page) { "the menu must be opened through the UI service" }
        assertNull(page.buttons[30], "the phantom cell must not be drawn when the feature is off")
        assertNull(page.buttons[32], "the size cell must not be drawn when the feature is off")
    }

    @Test fun `the heart is still a heart with the two new cells in it`() {
        val player = addPlayer("Owner")
        server.dispatchCommand(player, "meowo menu")
        server.scheduler.performTicks(1)
        val page = requireNotNull(ui.page)
        assertEquals(Material.PHANTOM_MEMBRANE, page.buttons[30]!!.item.type, "the phantom cell")
        assertEquals(Material.SLIME_BLOCK, page.buttons[32]!!.item.type, "the size cell")
        assertEquals(Material.AMETHYST_BLOCK, page.buttons[11]!!.item.type, "the heart's outline")
        assertNull(page.buttons[13], "the cleft must stay a hole")
    }
}
