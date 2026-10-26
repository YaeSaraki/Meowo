package com.meowo.platform

import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * The help page hides a command when the reader cannot run it. The filtering itself lives in
 * [CommandRouter], so it is tested here without touching the language bundle; the rendered page is
 * covered end to end in `MeowoRuntimeTest`, where the real bundle and plugin descriptor exist.
 *
 * Before this change the help was one fixed block of text, so it advertised `/afk` and `/reload` to
 * everybody even though tab completion had been filtering by permission all along.
 */
class HelpPageTest {

    open class TestPlugin : JavaPlugin()

    private class TestRoute(override val permission: String) : Route {
        override fun execute(sender: org.bukkit.command.CommandSender, args: List<String>) = Unit
    }

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var router: CommandRouter

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        // Messages is never asked to load a bundle here: the router only reads permissions.
        router = CommandRouter(Messages(plugin))
    }

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    private fun register(name: String, permission: String) { router.register(name, TestRoute(permission)) }

    @Test fun `a command is hidden when the reader lacks its permission`() {
        register("afk", "meowo.nametag.afk")
        register("info", "meowo.use")
        val plain = server.addPlayer("Plain")
        plain.addAttachment(plugin, "meowo.use", true)
        plain.addAttachment(plugin, "meowo.nametag.afk", false)
        assertFalse(router.isVisible(plain, "afk"), "afk must be hidden from a plain player")
        assertTrue(router.isVisible(plain, "info"), "info is allowed and must stay listed")
    }

    @Test fun `an operator sees the command again`() {
        register("afk", "meowo.nametag.afk")
        val admin = server.addPlayer("Admin")
        admin.addAttachment(plugin, "meowo.nametag.afk", true)
        assertTrue(router.isVisible(admin, "afk"), "with the permission the line must be listed")
    }

    @Test fun `an unregistered route is reported as missing`() {
        register("afk", "meowo.nametag.afk")
        // HelpPage lists unknown routes on purpose: a typo in the language file must not silently
        // delete a command from the help.
        assertFalse(router.hasRoute("afkk"))
        assertTrue(router.hasRoute("afk"))
    }
}
