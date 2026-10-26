package com.meowo.avatar

import com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent
import com.meowo.platform.CommandRouter
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.shared.event.EventBus
import org.bukkit.attribute.Attribute
import org.bukkit.command.Command
import org.bukkit.event.entity.CreatureSpawnEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.attribute.AttributeInstanceMock
import org.mockbukkit.mockbukkit.entity.PhantomMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.Locale
import java.util.UUID

/** MockBukkit loads a plugin from a main class, and this module has no descriptor of its own. */
open class TestPlugin : org.bukkit.plugin.java.JavaPlugin()

/**
 * The two switches, driven through the real routes.
 *
 * The property worth pinning is not "a phantom did not appear" but the one that would make the feature
 * hostile: **the vanilla mechanic must stop for the player who asked, and for nobody else**. One
 * player's night must stay their own — a switch that silences the whole server is a different feature.
 */
class AvatarContextTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var router: CommandRouter
    private lateinit var context: AvatarContext
    private val said = mutableListOf<String>()

    /** The `scale` attribute of each player, as a live server would hold it. */
    private val attributes = mutableMapOf<UUID, AttributeInstanceMock>()

    @BeforeEach fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        val bus = EventBus { _, error -> throw error }
        val messages = Messages(plugin).apply { load("zh_CN") }
        router = CommandRouter(messages)
        val host = PlatformHost(plugin, bus, router, messages)
        // MockBukkit predates the `scale` attribute and cannot install one, so the context is handed
        // the attribute itself. That keeps the tests about the feature rather than about the mock.
        attributes.clear()
        context = AvatarContext(
            host,
            send = { _, key, _ -> said += key },
            scaleAttribute = { player -> attributes.getOrPut(player.uniqueId) { AttributeInstanceMock(Attribute.SCALE, 1.0) } },
        )
        context.start()
    }

    @AfterEach fun teardown() {
        try { context.close() } finally { MockBukkit.unmock() }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    /** Stands in for the descriptor's `Command`; the router reads the label and the arguments. */
    private val COMMAND = object : Command("meowo") {
        override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>) = true
    }

    private fun run(sender: org.bukkit.command.CommandSender, vararg args: String) =
        router.onCommand(sender, COMMAND, "meowo", args)

    /** A player with both permissions and a scale attribute, as a live server would have. */
    private fun player(name: String): PlayerMock {
        val player = server.addPlayer(name)
        player.setLocale(Locale.SIMPLIFIED_CHINESE)
        player.addAttachment(plugin, "meowo.phantom", true)
        player.addAttachment(plugin, "meowo.size", true)
        attributes[player.uniqueId] = AttributeInstanceMock(Attribute.SCALE, 1.0)
        return player
    }

    private fun scaleOf(player: PlayerMock): Double =
        requireNotNull(attributes[player.uniqueId]) { "the scale attribute must be there" }.baseValue

    /**
     * The event the server fires when it is about to spawn a phantom for [player].
     *
     * Built by hand rather than waited for: the real one waits an hour of game time, and what is being
     * tested is what the context does with it.
     */
    private fun preSpawn(player: PlayerMock): PhantomPreSpawnEvent {
        val event = PhantomPreSpawnEvent(player.location, player, CreatureSpawnEvent.SpawnReason.NATURAL)
        server.pluginManager.callEvent(event)
        return event
    }

    /** The same spawn, seen once the phantom already exists — the backup path. */
    private fun spawnEvent(phantom: PhantomMock): CreatureSpawnEvent {
        val event = CreatureSpawnEvent(phantom, CreatureSpawnEvent.SpawnReason.NATURAL)
        server.pluginManager.callEvent(event)
        return event
    }

    private fun phantomFor(player: PlayerMock): PhantomMock =
        PhantomMock(server, UUID.randomUUID()).also { it.spawningEntity = player.uniqueId }

    // ── the switch ────────────────────────────────────────────────────────────────────────────

    @Test fun `the night phantoms are switched off and on again`() {
        val player = player("Owner")
        assertFalse(context.phantomsBlocked(player.uniqueId), "the mechanic starts switched on")

        run(player, "phantom")
        assertTrue(context.phantomsBlocked(player.uniqueId), "the request must be remembered")
        assertEquals("avatar-phantom-on", said.last())

        run(player, "phantom")
        assertFalse(context.phantomsBlocked(player.uniqueId), "the request must be withdrawn")
        assertEquals("avatar-phantom-off", said.last())
    }

    @Test fun `the words on and off say which way it goes`() {
        val player = player("Owner")
        run(player, "phantom", "on")
        assertTrue(context.phantomsBlocked(player.uniqueId))
        run(player, "phantom", "off")
        assertFalse(context.phantomsBlocked(player.uniqueId))
    }

    @Test fun `a word that is neither is refused, and nothing changes`() {
        val player = player("Owner")
        run(player, "phantom", "maybe")
        assertFalse(context.phantomsBlocked(player.uniqueId))
        assertEquals("avatar-phantom-usage", said.last())
    }

    @Test fun `the switch belongs to one player only`() {
        val one = player("One")
        val other = player("Two")
        run(one, "phantom")
        assertTrue(context.phantomsBlocked(one.uniqueId))
        assertFalse(context.phantomsBlocked(other.uniqueId), "one player's night is nobody else's")
    }

    // ── the cancel ───────────────────────────────────────────────────────────────────────────

    @Test fun `the phantom the server is about to spawn is cancelled`() {
        val player = player("Owner")
        run(player, "phantom")

        val event = preSpawn(player)
        assertTrue(event.isCancelled, "the phantom must not be spawned")
        assertTrue(event.shouldAbortSpawn(), "and the server must stop trying for this player")
    }

    @Test fun `a player who did not ask keeps their phantoms`() {
        val player = player("Owner")
        val event = preSpawn(player)
        assertFalse(event.isCancelled, "the vanilla mechanic must still be the server's own")
        assertFalse(event.shouldAbortSpawn())
    }

    @Test fun `one player's switch does not silence the server for the others`() {
        val shielded = player("Shielded")
        val other = player("Other")
        run(shielded, "phantom")

        assertTrue(preSpawn(shielded).isCancelled, "the player who asked is left alone")
        assertFalse(preSpawn(other).isCancelled, "and everybody else keeps the vanilla night")
    }

    @Test fun `the backup path cancels a phantom that already exists`() {
        val player = player("Owner")
        run(player, "phantom")

        val event = spawnEvent(phantomFor(player))
        assertTrue(event.isCancelled, "the phantom must not survive either")
    }

    @Test fun `a phantom nobody owns is left alone`() {
        val player = player("Owner")
        run(player, "phantom")

        // A phantom that was not spawned for anybody — a structure, a command — is nobody's business.
        val orphan = PhantomMock(server, UUID.randomUUID())
        assertFalse(spawnEvent(orphan).isCancelled)
    }

    // ── the size ──────────────────────────────────────────────────────────────────────────────

    @Test fun `the size moves one step at a time`() {
        val player = player("Owner")
        run(player, "size", "up")
        assertEquals(1.25, scaleOf(player), 1e-9)
        run(player, "size", "up")
        assertEquals(1.5, scaleOf(player), 1e-9)
        run(player, "size", "down")
        assertEquals(1.25, scaleOf(player), 1e-9)
    }

    @Test fun `the size can be reset`() {
        val player = player("Owner")
        run(player, "size", "up")
        run(player, "size", "reset")
        assertEquals(1.0, scaleOf(player), 1e-9)
    }

    @Test fun `the size is reported when it is asked for`() {
        val player = player("Owner")
        run(player, "size")
        assertEquals("avatar-size-current", said.last())
    }

    @Test fun `a number the grid does not contain is snapped onto it`() {
        val player = player("Owner")
        run(player, "size", "1.3")
        assertEquals(1.25, scaleOf(player), 1e-9, "a size off the grid could not be reached again")
    }

    @Test fun `the console has no body to resize`() {
        run(server.consoleSender, "size", "up")
        assertTrue(said.isEmpty() || said.last() != "avatar-size-changed", "the console has no body to resize")
    }

    // ── closing ───────────────────────────────────────────────────────────────────────────────

    @Test fun `closing the context puts every player back to normal`() {
        val player = player("Owner")
        run(player, "size", "up")
        context.close()
        assertEquals(1.0, scaleOf(player), 1e-9, "a player must not be left a quarter bigger")
        assertFalse(context.phantomsBlocked(player.uniqueId))
    }

    @Test fun `the api reports what the player chose`() {
        val player = player("Owner")
        assertFalse(context.phantomsBlocked(player.uniqueId))
        assertEquals(1.0, context.scaleOf(player.uniqueId), 1e-9)
        run(player, "phantom")
        run(player, "size", "up")
        assertTrue(context.phantomsBlocked(player.uniqueId))
        assertEquals(1.25, context.scaleOf(player.uniqueId), 1e-9)
    }
}
