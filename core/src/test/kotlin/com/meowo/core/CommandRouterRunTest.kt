package com.meowo.core

import com.meowo.platform.CommandRouter
import com.meowo.platform.Messages
import com.meowo.platform.Route
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock

/**
 * Being told that a route **ran** — the signal the proactive cats hang off.
 *
 * The alternative is listening for `PlayerCommandPreprocessEvent` and re-deriving the route from the
 * command string. That is the second source of truth `CommandRouter` was built to avoid, and it
 * would have to re-check two more things this class already knows: whether the sender was allowed to
 * run the route at all, and which context owns it.
 *
 * So the contract pinned here is narrow and deliberate: **a runner hears about a route that ran, and
 * about nothing else.** A rejected command, a permission failure and an unknown name are all silent.
 *
 * It lives in `core` rather than beside `CommandRouter` because `Messages` reads its bundle out of
 * the plugin jar, and the rejected paths (`unknown-command`, `no-permission`) go through it. The
 * `platform` module's tests have no jar to read from.
 */
class CommandRouterRunTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var router: CommandRouter
    private val runs = mutableListOf<Pair<String, String>>()

    private fun route(permission: String = "meowo.use", body: () -> Unit = {}) = object : Route {
        override val permission: String = permission
        override fun execute(sender: CommandSender, args: List<String>) {
            ran += 1
            body()
        }
    }

    private var ran = 0

    private fun command() = object : Command("meowo") {
        override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true
    }

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        // A bundle **is** needed here, unlike in the ownership test: this one executes routes, and
        // the rejected paths (`unknown-command`, `no-permission`) go through `Messages.resolve`.
        router = CommandRouter(Messages(plugin).apply { load("zh_CN") })
        router.onRun { sender, route -> runs += route to sender.name }
    }

    @AfterEach fun tearDown() = MockBukkit.unmock()

    /**
     * A player who may actually run the routes under test.
     *
     * `CommandRouterOwnershipTest` never executes anything, so it never needed a permission — and
     * this class, which does, found out the hard way: with no attachment every command is refused and
     * `ran` stays at zero.
     */
    private fun player(name: String): PlayerMock =
        server.addPlayer(name).also { it.addAttachment(plugin, "meowo.use", true) }

    private fun run(player: PlayerMock, vararg args: String) =
        router.onCommand(player, command(), "meowo", args)

    @Test fun `a route that ran is reported once`() {
        router.register("home", route())
        val player = player("Alice")

        run(player, "home")

        assertEquals(listOf("home" to "Alice"), runs)
        assertEquals(1, ran, "the route itself must still have executed")
    }

    @Test fun `an unknown command is not reported`() {
        router.register("home", route())
        run(player("Alice"), "nosuch")

        assertTrue(runs.isEmpty(), "a command that never existed was reported as a run: $runs")
        assertEquals(0, ran)
    }

    /**
     * The one a listener would otherwise have to re-check.
     *
     * A route the player may not use did not run, and a cat reacting to it would be reacting to
     * something that never happened.
     */
    @Test fun `a command refused on permission is not reported`() {
        router.register("home", route(permission = "meowo.home"))
        // Deliberately **not** `player("Alice")`: this test is about the permission being refused.
        val player = server.addPlayer("Alice")

        run(player, "home")

        assertTrue(runs.isEmpty(), "a refused command was reported: $runs")
        assertEquals(0, ran)
    }

    @Test fun `a route that ran for somebody else is reported with that sender`() {
        router.register("home", route())
        val alice = player("Alice")
        val bob = player("Bob")

        run(alice, "home")
        run(bob, "home")

        assertEquals(listOf("home" to "Alice", "home" to "Bob"), runs)
    }

    /** Throwing in a listener must not take the command down with it — same rule as registration. */
    @Test fun `a runner that throws is isolated`() {
        router.register("home", route())
        router.onRun { _, _ -> throw IllegalStateException("boom") }
        val player = player("Alice")

        run(player, "home")

        assertEquals(1, ran, "a throwing runner stopped the route from running")
        assertEquals(listOf("home" to "Alice"), runs, "the other runner still heard about it")
    }

    /** Closing the subscription actually unsubscribes; otherwise a disabled context keeps listening. */
    @Test fun `closing the subscription stops the reports`() {
        router.register("home", route())
        val player = player("Alice")
        val second = mutableListOf<String>()
        val subscription = router.onRun { _, route -> second += route }

        subscription.close()
        run(player, "home")

        assertTrue(second.isEmpty(), "a closed subscription still fired: $second")
        assertEquals(listOf("home" to "Alice"), runs, "the first listener is unaffected")
    }
}