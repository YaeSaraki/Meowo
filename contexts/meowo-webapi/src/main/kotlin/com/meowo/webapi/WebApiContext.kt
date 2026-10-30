package com.meowo.webapi

import com.meowo.platform.PlatformHost
import com.meowo.shared.lifecycle.Context
import com.meowo.webapi.domain.ServerSnapshot
import com.meowo.webapi.domain.WorldSnapshot
import com.meowo.webapi.infrastructure.BukkitSnapshotBuilder
import com.meowo.webapi.infrastructure.WebApiServer
import org.bukkit.Bukkit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The read-only HTTP context.
 *
 * It publishes a **snapshot**: the Bukkit API is read on the main thread every
 * `snapshot-interval-seconds`, and the HTTP threads only ever read the published value. Nothing
 * but an immutable value crosses a thread boundary.
 *
 * The old `yawebapi` module answered each request by reading Bukkit from the request thread; that
 * is a data race waiting for a slow tick, and this is the same data without the race.
 */
class WebApiContext(
    private val host: PlatformHost,
    private val settings: WebApiSettings,
) : Context {

    override val id = "webapi"

    private val active = AtomicBoolean(false)
    private val builder = BukkitSnapshotBuilder(settings.statisticsFull, settings.maxPlayers) {
        host.plugin.logger.warning("webapi: $it")
    }
    private val empty = WorldSnapshot(
        server = ServerSnapshot(0, 0, null, false, 0L),
        players = emptyList(),
        generatedAt = 0L,
    )

    @Volatile
    private var snapshot: WorldSnapshot = empty

    private var server: WebApiServer? = null
    private var taskId: Int? = null

    override fun start() {
        check(settings.secret.isNotBlank()) {
            "webapi.secret is not configured; the HTTP API stays off"
        }
        refresh()
        val api = WebApiServer({ snapshot }, settings) { message -> host.plugin.logger.info(message) }
        api.start()
        server = api
        val period = settings.snapshotIntervalSeconds.coerceAtLeast(1) * 20L
        taskId = Bukkit.getScheduler().runTaskTimer(host.plugin, Runnable { refresh() }, period, period).taskId
        active.set(true)
        host.plugin.logger.info(
            "webapi: snapshot every ${settings.snapshotIntervalSeconds}s, port ${api.boundPort}, statistics=${settings.statisticsMode}"
        )
    }

    private fun refresh() {
        try {
            snapshot = builder.build()
        } catch (e: Throwable) {
            host.plugin.logger.warning("webapi: snapshot failed: ${e.message}")
        }
    }

    override fun close() {
        active.set(false)
        taskId?.let { Bukkit.getScheduler().cancelTask(it) }
        taskId = null
        server?.close()
        server = null
    }
}
