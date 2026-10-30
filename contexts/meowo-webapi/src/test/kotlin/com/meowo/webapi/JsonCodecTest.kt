package com.meowo.webapi

import com.meowo.webapi.domain.AdvancementEntry
import com.meowo.webapi.domain.PlayerSnapshot
import com.meowo.webapi.domain.ServerSnapshot
import com.meowo.webapi.domain.SkinReference
import com.meowo.webapi.domain.StatisticEntry
import com.meowo.webapi.domain.WorldSnapshot
import com.meowo.webapi.infrastructure.JsonCodec
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The field names are the contract with the Worker; renaming one silently breaks it. */
class JsonCodecTest {

    private val codec = JsonCodec()

    private val snapshot = WorldSnapshot(
        server = ServerSnapshot(1, 20, 20.0, true, 5L),
        players = listOf(
            PlayerSnapshot(
                uuid = "uuid-1",
                name = "Steve",
                firstLogin = 1L,
                lastLogin = 2L,
                totalPlaytimeSeconds = 3L,
                statistics = mapOf("BOAT_ONE_CM" to StatisticEntry("UNTYPED", "BOAT_ONE_CM", 42L)),
                advancements = listOf(AdvancementEntry("story/root", true, listOf("a"), emptyList())),
                skin = SkinReference("classic", "https://example.test/skin.png", "https://example.test/cape.png"),
            ),
        ),
        generatedAt = 1234L,
    )

    /** `field` becomes `"field"`, so the assertions do not have to carry escapes. */
    private fun quoted(field: String): String = "\"" + field + "\""

    @Test
    fun `the snapshot keeps the field names the consumer expects`() {
        val json = codec.encode(snapshot)
        listOf(
            "generatedAt", "server", "players", "onlinePlayers", "maxPlayers", "tps",
            "heartbeatStatus", "updateTime", "uuid", "name", "firstLogin", "lastLogin",
            "totalPlaytimeSeconds", "statistics", "advancements", "skin", "type", "url", "capeUrl",
        ).forEach { field ->
            assertTrue(json.contains(quoted(field)), "missing field: " + field)
        }
    }

    @Test
    fun `a statistic keeps its category`() {
        val json = codec.encode(snapshot.players.first().statistics)
        assertTrue(json.contains(quoted("category") + ":" + quoted("UNTYPED")))
        assertTrue(json.contains(quoted("value") + ":42"))
    }
}
