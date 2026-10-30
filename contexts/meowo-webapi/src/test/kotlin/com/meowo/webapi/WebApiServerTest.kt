package com.meowo.webapi

import com.meowo.webapi.domain.AdvancementEntry
import com.meowo.webapi.domain.PlayerSnapshot
import com.meowo.webapi.domain.ServerSnapshot
import com.meowo.webapi.domain.SkinReference
import com.meowo.webapi.domain.SnapshotProvider
import com.meowo.webapi.domain.StatisticEntry
import com.meowo.webapi.domain.WorldSnapshot
import com.meowo.webapi.infrastructure.WebApiServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * The HTTP surface is the only thing the internet can reach, so its gates are tested here:
 * the secret, the allow list, the method whitelist and the paths.
 */
class WebApiServerTest {

    private val snapshot = WorldSnapshot(
        server = ServerSnapshot(2, 20, 19.5, true, 1000L),
        players = listOf(
            PlayerSnapshot(
                uuid = "uuid-1",
                name = "Steve",
                firstLogin = 1L,
                lastLogin = 2L,
                totalPlaytimeSeconds = 3L,
                statistics = mapOf("BOAT_ONE_CM" to StatisticEntry("UNTYPED", "BOAT_ONE_CM", 42L)),
                advancements = listOf(AdvancementEntry("story/root", true, listOf("a"), emptyList())),
                skin = SkinReference("classic", "https://example.test/skin.png", ""),
            ),
        ),
        generatedAt = 1234L,
    )

    private val provider = object : SnapshotProvider {
        override fun current(): WorldSnapshot = snapshot
    }

    private fun settings(
        secret: String = "s3cret",
        allowedIps: Set<String> = emptySet(),
    ) = WebApiSettings(
        port = 0,
        bind = "127.0.0.1",
        secret = secret,
        snapshotIntervalSeconds = 60,
        statisticsMode = "basic",
        maxPlayers = 100,
        allowedIps = allowedIps,
    )

    private fun <T> withServer(config: WebApiSettings, block: (WebApiServer) -> T): T {
        val server = WebApiServer(provider, config)
        server.start()
        return try {
            block(server)
        } finally {
            server.close()
        }
    }

    private fun call(
        port: Int,
        path: String,
        secret: String? = null,
        method: String = "GET",
    ): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (secret != null) connection.setRequestProperty("X-Meow-Secret", secret)
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        return status to body
    }

    @Test
    fun `health needs no secret`() {
        withServer(settings()) { server ->
            val (status, body) = call(server.boundPort, "/health")
            assertEquals(200, status)
            assertTrue(body.contains("ok"))
        }
    }

    @Test
    fun `a request without the secret is refused`() {
        withServer(settings()) { server ->
            val (status, _) = call(server.boundPort, "/api/v1/snapshot")
            assertEquals(401, status)
        }
    }

    @Test
    fun `a request with the wrong secret is refused`() {
        withServer(settings()) { server ->
            val (status, _) = call(server.boundPort, "/api/v1/snapshot", secret = "wrong")
            assertEquals(401, status)
        }
    }

    @Test
    fun `the snapshot carries the players`() {
        withServer(settings()) { server ->
            val (status, body) = call(server.boundPort, "/api/v1/snapshot", secret = "s3cret")
            assertEquals(200, status)
            assertTrue(body.contains("Steve"))
            assertTrue(body.contains("BOAT_ONE_CM"))
            assertTrue(body.contains("story/root"))
            assertTrue(body.contains("generatedAt"))
        }
    }

    @Test
    fun `an address outside the allow list is refused`() {
        withServer(settings(allowedIps = setOf("10.0.0.1"))) { server ->
            val (status, _) = call(server.boundPort, "/api/v1/snapshot", secret = "s3cret")
            assertEquals(403, status)
        }
    }

    @Test
    fun `an unknown path is not found`() {
        withServer(settings()) { server ->
            val (status, _) = call(server.boundPort, "/api/v1/nothing", secret = "s3cret")
            assertEquals(404, status)
        }
    }

    @Test
    fun `only reading is allowed`() {
        withServer(settings()) { server ->
            val (status, _) = call(server.boundPort, "/api/v1/snapshot", secret = "s3cret", method = "POST")
            assertEquals(405, status)
        }
    }

    @Test
    fun `statistics are addressed by uuid`() {
        withServer(settings()) { server ->
            val (found, body) = call(server.boundPort, "/api/v1/statistics/uuid-1", secret = "s3cret")
            assertEquals(200, found)
            assertTrue(body.contains("BOAT_ONE_CM"))

            val (missing, _) = call(server.boundPort, "/api/v1/statistics/nobody", secret = "s3cret")
            assertEquals(404, missing)
        }
    }

    @Test
    fun `a server without a secret does not open its port`() {
        val failed = try {
            withServer(settings(secret = "")) { server -> server.boundPort }
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun `closing the server shuts its worker pool down`() {
        // The pool's threads are named, so they can be counted. The pool used to be created in
        // `start()` and dropped on the floor: `stop()` closed the server, the two daemon threads
        // stayed, and every reload that toggled the web API leaked two more.
        fun workers(): Int = Thread.getAllStackTraces().keys.count { it.name == "meowo-webapi" && it.isAlive }

        // A pool from an earlier test may still be finishing. Wait for it, so the count below is
        // about this test's own pool and nobody else's.
        val settle = System.currentTimeMillis() + 2_000
        while (workers() > 0 && System.currentTimeMillis() < settle) Thread.sleep(10)
        assertEquals(0, workers(), "a worker pool from an earlier test is still alive")

        withServer(settings()) { server ->
            call(server.boundPort, "/health")
            assertTrue(workers() > 0, "the pool did not start its threads")
        }

        // `shutdown()` lets the running task finish and then the threads end. The wait is bounded
        // and the assertion is that they end — not that they end at once.
        val end = System.currentTimeMillis() + 2_000
        while (workers() > 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(0, workers(), "the worker threads outlived the server")
    }
}
