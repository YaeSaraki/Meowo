package com.meowo.webapi.infrastructure

import com.meowo.webapi.WebApiSettings
import com.meowo.webapi.domain.SnapshotProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The HTTP surface.
 *
 * Read-only by construction: `GET` is the only method it answers, and the only thing it can do
 * is hand out the last snapshot.
 *
 * Two gates stand in front of everything except `/health`:
 * 1. the shared secret (`X-Meow-Secret`), compared in constant time;
 * 2. an optional address allow list.
 *
 * The port is bound to `127.0.0.1` by default: the way in is a Cloudflare Tunnel running on this
 * machine, not a socket the internet can reach.
 */
class WebApiServer(
    private val provider: SnapshotProvider,
    private val settings: WebApiSettings,
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    private var server: HttpServer? = null
    /**
     * The worker pool.
     *
     * It has to be a field, because `stop()` closes the server but not the pool: the pool is the
     * server's, and the server does not shut it down. A pool that is created in [start] and dropped
     * on the floor keeps two daemon threads alive for as long as the JVM lives — so every
     * `/meowo reload` that toggles the web API on and off leaked two of them.
     */
    private var executor: ExecutorService? = null
    private val codec = JsonCodec()
    private val running = AtomicBoolean(false)

    /** The port actually bound. `0` in the settings asks the OS for a free one. */
    @Volatile
    var boundPort: Int = settings.port
        private set

    fun start() {
        check(settings.secret.isNotBlank()) { "webapi.secret is empty; refusing to open the port" }
        val http = HttpServer.create(InetSocketAddress(settings.bind, settings.port), 0)
        val pool = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "meowo-webapi").apply { isDaemon = true }
        }
        http.executor = pool
        executor = pool
        http.createContext("/") { exchange -> handle(exchange) }
        http.start()
        server = http
        running.set(true)
        boundPort = http.address.port
        log("HTTP API listening on ${settings.bind}:${boundPort}")
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try {
            server?.stop(0)
        } catch (e: Throwable) {
            log("failed to stop the HTTP server: ${e.message}")
        }
        server = null
        // The pool is ours, so we are the ones who close it. `stop(0)` only closes the server.
        executor?.shutdown()
        executor = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                respond(exchange, 405, """{"error":"method not allowed"}""")
                return
            }
            val path = exchange.requestURI.path
            if (path == "/health") {
                respond(exchange, 200, """{"status":"ok"}""")
                return
            }
            // The secret first, the address second.
            //
            // The other order answers "is my address on the list?" to anybody who asks, because 403
            // and 401 say different things: with the allow list checked first, a caller with no
            // secret at all learns whether its address is allowed. So the secret is checked first,
            // and a caller who does not have it always gets the same answer — 401 — whatever its
            // address is.
            if (!authenticated(exchange)) {
                respond(exchange, 401, """{"error":"unauthorized"}""")
                return
            }
            if (!allowed(exchange)) {
                respond(exchange, 403, """{"error":"forbidden"}""")
                return
            }
            route(exchange, path)
        } catch (e: Throwable) {
            log("request failed: ${e.message}")
            respond(exchange, 500, """{"error":"internal"}""")
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange, path: String) {
        val snapshot = provider.current()
        when {
            path == "/api/v1/snapshot" -> respond(exchange, 200, codec.encode(snapshot))
            path == "/api/v1/players" -> respond(exchange, 200, codec.encode(snapshot.players))
            path == "/api/v1/server/status" -> respond(exchange, 200, codec.encode(snapshot.server))
            path.startsWith("/api/v1/statistics/") -> {
                val uuid = path.removePrefix("/api/v1/statistics/")
                val player = snapshot.players.firstOrNull { it.uuid == uuid }
                if (player == null) {
                    respond(exchange, 404, """{"error":"player not found"}""")
                } else {
                    respond(exchange, 200, codec.encode(mapOf("statistics" to player.statistics)))
                }
            }
            path.startsWith("/api/v1/advancements/") -> {
                val uuid = path.removePrefix("/api/v1/advancements/")
                val player = snapshot.players.firstOrNull { it.uuid == uuid }
                if (player == null) {
                    respond(exchange, 404, """{"error":"player not found"}""")
                } else {
                    respond(exchange, 200, codec.encode(mapOf("advancements" to player.advancements)))
                }
            }
            path.startsWith("/api/v1/skins/") -> {
                val uuid = path.removePrefix("/api/v1/skins/")
                val player = snapshot.players.firstOrNull { it.uuid == uuid }
                if (player == null) {
                    respond(exchange, 404, """{"error":"player not found"}""")
                } else {
                    respond(exchange, 200, codec.encode(player.skin))
                }
            }
            else -> respond(exchange, 404, """{"error":"not found"}""")
        }
    }

    private fun authenticated(exchange: HttpExchange): Boolean {
        val provided = exchange.requestHeaders.getFirst("X-Meow-Secret")
            ?: exchange.requestHeaders.getFirst("X-MeovO-Secret")
            ?: return false
        return constantTimeEquals(provided, settings.secret)
    }

    /**
     * Compares two secrets without leaking *where* they differ.
     *
     * It does **not** hide the length: a secret of a different size is rejected before the
     * comparison, so the time it takes says whether the length was right. That is a deliberate
     * trade — the length of a shared secret is not the secret — and the point of the comparison is
     * that a wrong secret of the *right* length is not rejected after a single differing character.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun allowed(exchange: HttpExchange): Boolean {
        if (settings.allowedIps.isEmpty()) return true
        val remote = exchange.remoteAddress?.address?.hostAddress ?: return false
        return settings.allowedIps.contains(remote)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: Throwable) {
            log("failed to answer: ${e.message}")
        }
    }
}
