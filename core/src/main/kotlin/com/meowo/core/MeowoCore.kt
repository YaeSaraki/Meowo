package com.meowo.core

import taboolib.common.env.RuntimeDependencies
import taboolib.common.env.RuntimeDependency
import taboolib.common.platform.Plugin
import taboolib.platform.BukkitPlugin

/**
 * One real TabooLib entrypoint; contexts use explicit lifecycle ownership inside this plugin.
 *
 * SQLite JDBC is fetched on first start rather than bundled. Its native payload covers six
 * platforms and twenty-four architectures — about 24 MB unpacked, of which a single server needs
 * one ~2 MB file — which dominated the jar. TabooLib already downloads its own runtime modules the
 * same way, so this adds no new constraint: the cache lives in the **server's own `libraries/`
 * directory** — the one next to `plugins/`, not inside the plugin's folder — and an air-gapped
 * server is prepared by copying that directory across, exactly as the README explains for TabooLib
 * itself.
 *
 * The contexts declare sqlite as `compileOnly`, so nothing here would break silently if the
 * download were skipped — `Class.forName("org.sqlite.JDBC")` would throw a clear error rather
 * than a `NoClassDefFoundError` deep inside a context.
 *
 * `transitive = true` pulls in SQLite's own dependency (SLF4J API); without it the driver loads
 * and then fails on a missing logger binding.
 */
@RuntimeDependencies(
    RuntimeDependency("org.xerial:sqlite-jdbc:3.50.3.0", transitive = true),
)
object MeowoCore : Plugin() {
    private var runtime: MeowoRuntime? = null
    override fun onEnable() {
        val plugin = BukkitPlugin.getInstance()
        val next = MeowoRuntime(plugin)
        runtime = next
        try { next.start() } catch (error: Exception) { next.close(); runtime = null; throw error }
    }
    override fun onDisable() { runtime?.close(); runtime = null }
}
