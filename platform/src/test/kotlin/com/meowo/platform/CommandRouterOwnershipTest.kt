package com.meowo.platform

import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * Route ownership, added so a context can learn which commands are its own.
 *
 * The rule this file exists to protect: a route name is **not** a context id, so nothing may infer
 * one from the other. `eco` is `economy`, `tpa` is `teleport`, and a guess that is right most of
 * the time is worse than no guess at all.
 */
class CommandRouterOwnershipTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var router: CommandRouter

    private fun route(permission: String = "meowo.use") = object : Route {
        override val permission: String = permission
        override fun execute(sender: CommandSender, args: List<String>) = Unit
    }

    private fun sender() = server.addPlayer()

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        // Messages is never asked to load a bundle here: ownership is pure bookkeeping.
        router = CommandRouter(Messages(MockBukkit.load(TestPlugin::class.java)))
    }

    @AfterEach
    fun tearDown() = MockBukkit.unmock()

    @Test fun `a route registered with a context records its owner`() {
        router.register("eco", "economy", route())
        assertEquals("economy", router.ownerOf("eco"))
    }

    /** Nothing infers an owner. An unowned route answers null, not a guess. */
    @Test fun `the two-argument form registers a route with no owner at all`() {
        router.register("help", route())
        assertNull(router.ownerOf("help"), "a name must never be read as a context id")
    }

    @Test fun `an observer is told about a route registered after it`() {
        val seen = mutableListOf<String>()
        router.observe { contextId, name, _ -> seen += "$contextId:$name" }
        router.register("tpa", "teleport", route())
        assertEquals(listOf("teleport:tpa"), seen)
    }

    /**
     * Load order must not decide what a context knows. An observer that arrives late is replayed
     * the routes that already exist, so a context loaded last is not silently left with an empty map.
     */
    @Test fun `an observer registered late is caught up on what it missed`() {
        router.register("eco", "economy", route())
        router.register("tpa", "teleport", route())
        val seen = mutableListOf<String>()
        router.observe { contextId, name, _ -> seen += "$contextId:$name" }
        assertEquals(listOf("economy:eco", "teleport:tpa"), seen)
    }

    @Test fun `replay skips routes nobody claimed`() {
        router.register("help", route())
        router.register("eco", "economy", route())
        val seen = mutableListOf<String>()
        router.observe { _, name, _ -> seen += name }
        assertEquals(listOf("eco"), seen, "an unowned route has no context to attribute it to")
    }

    @Test fun `closing an observer stops it being told`() {
        val seen = mutableListOf<String>()
        val handle = router.observe { _, name, _ -> seen += name }
        router.register("a", "alpha", route())
        handle.close()
        router.register("b", "beta", route())
        assertEquals(listOf("a"), seen)
    }

    @Test fun `unregistering a route forgets its owner too`() {
        val handle = router.register("eco", "economy", route())
        handle.close()
        assertNull(router.ownerOf("eco"))
        assertTrue(router.routesOf("economy").isEmpty())
    }

    @Test fun `a context can ask which commands are its own`() {
        router.register("eco", "economy", route())
        router.register("daily", "economy", route())
        router.register("tpa", "teleport", route())
        assertEquals(listOf("eco", "daily"), router.routesOf("economy"))
        assertEquals(listOf("tpa"), router.routesOf("teleport"))
        assertTrue(router.routesOf("home").isEmpty())
    }

    /** An observer must not be able to break registration for everybody else. */
    @Test fun `a throwing observer does not stop the route or the other observers`() {
        router.observe { _, _, _ -> error("boom") }
        val seen = mutableListOf<String>()
        router.observe { _, name, _ -> seen += name }
        val handle = router.register("eco", "economy", route())
        assertEquals("economy", router.ownerOf("eco"), "the route must still be registered")
        assertEquals(listOf("eco"), seen, "the second observer must still be told")
        handle.close()
    }

    @Test fun `registering the same name twice is still refused`() {
        router.register("eco", "economy", route())
        val again = runCatching { router.register("eco", "teleport", route()) }
        assertTrue(again.isFailure)
        assertEquals("economy", router.ownerOf("eco"), "a refused registration must not reassign the owner")
    }

    /** A context that unloads and reloads must be able to take its command back, owner and all. */
    @Test fun `a name freed by an unregister can be taken again by a different context`() {
        val first = router.register("eco", "economy", route("meowo.a"))
        first.close()
        router.register("eco", "economy", route("meowo.b"))
        assertEquals("economy", router.ownerOf("eco"))
        assertEquals(1, router.routesOf("economy").size, "the reloaded route must not appear twice")
    }
}
