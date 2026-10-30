package com.meowo.core

import com.meowo.core.config.ConfigVersionMerger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.io.File

/**
 * The configuration version is **global**: one number governs every file the operator can edit.
 *
 * ### What was broken
 *
 * The version used to live in `config.yml` and govern `config.yml` alone. The plugin ships a second
 * file the operator can edit — `ui.yml`, the menu layouts — and it was outside the version's reach.
 *
 * That mattered because of how the loader works: a screen the operator has customised wins
 * **outright**, so the shipped copy does not fill in what is missing inside it. A release that added a
 * button to a screen therefore changed the plugin's configuration *without changing the version that
 * governs it*, and the button never arrived on a server with its own `ui.yml`. Nothing failed, nothing
 * was logged, and the layout was quietly one release behind.
 *
 * The language bundles are deliberately outside it too, for the opposite reason: they hold wording
 * rather than settings, the jar's copy already fills every key the operator's file lacks, and merging
 * them would freeze the shipped wording so a later fix could never reach that server.
 */
class ConfigSyncTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var runtime: MeowoRuntime
    private lateinit var ui: FakeUI

    @BeforeEach fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.loadWith(TestPlugin::class.java, File("build/resources/main/plugin.yml"))
        plugin.description.permissions.forEach { server.pluginManager.addPermission(it) }
    }

    @AfterEach fun teardown() {
        try { if (::runtime.isInitialized) runtime.close() } finally { MockBukkit.unmock() }
    }

    /** Starts the runtime the way production does: the plugin's own configuration first. */
    private fun start() {
        runtime = MeowoRuntime(plugin) { ui }
        ui = FakeUI(runtime.messages)
        runtime.start()
    }

    private fun yaml(file: File) = YamlConfiguration().apply { load(file) }

    private val operatorUi = """
        screens:
          menu:
            title: "我的菜单"
            rows: 6
            buttons:
              10: { icon: STONE, name: "矿脉", action: "mine" }
    """.trimIndent()

    @Test fun `the operator's layout file is brought up to the shipped version`() {
        val file = plugin.dataFolder.resolve("ui.yml")
        file.parentFile.mkdirs()
        file.writeText(operatorUi)

        start()

        val merged = yaml(file)
        // The operator's own screen survives untouched.
        assertEquals("我的菜单", merged.getString("screens.menu.title"), "the operator's title must survive")
        assertEquals("mine", merged.getString("screens.menu.buttons.10.action"))
        // And the shipped layout arrives around it.
        assertTrue(
            merged.getConfigurationSection("screens.pomodoro") != null,
            "a shipped screen the operator never had must reach their file: ${merged.getKeys(false)}",
        )
        assertEquals(
            ConfigVersionMerger.globalVersion(javaClass.getResourceAsStream("/config.yml")),
            merged.getInt(ConfigVersionMerger.VERSION_KEY),
            "the file records the global version, so the next release knows it is behind",
        )
    }

    @Test fun `a layout file the operator never made is not created`() {
        val file = plugin.dataFolder.resolve("ui.yml")

        start()

        assertFalse(file.exists(), "the plugin must not write a copy of the shipped layout: that would freeze it")
    }

    @Test fun `the shipped layout still applies to a screen the operator replaced`() {
        // The operator's whole screen wins, so their version of it is what is drawn — and the file
        // keeps the rest of the shipped screens as the fallback.
        val file = plugin.dataFolder.resolve("ui.yml")
        file.parentFile.mkdirs()
        file.writeText(operatorUi)

        start()

        val merged = yaml(file)
        assertTrue(
            merged.getConfigurationSection("screens.pomodoro") != null,
            "the shipped screens remain the fallback for the screens the operator did not write",
        )
    }
}
