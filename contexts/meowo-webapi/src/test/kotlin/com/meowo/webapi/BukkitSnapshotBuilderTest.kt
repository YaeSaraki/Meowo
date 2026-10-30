package com.meowo.webapi

import com.meowo.webapi.infrastructure.BukkitSnapshotBuilder
import org.bukkit.Material
import org.bukkit.Statistic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * The snapshot builder, against a server that answers.
 *
 * MockBukkit cannot do everything Paper can — it has no advancement tree and no TPS — and that is
 * exactly why this test is worth having: a reading the server cannot answer has to come back as
 * "not known" instead of taking the rest of the snapshot with it.
 */
class BukkitSnapshotBuilderTest {

    private lateinit var server: ServerMock

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `the snapshot holds the players that are online`() {
        server.addPlayer("Steve")
        server.addPlayer("Alex")

        val snapshot = BukkitSnapshotBuilder(statisticsFull = false, maxPlayers = 100).build()

        assertEquals(2, snapshot.server.onlinePlayers)
        assertEquals(2, snapshot.players.size)
        assertEquals(listOf("Steve", "Alex"), snapshot.players.map { it.name })
    }

    @Test
    fun `the snapshot stops at the player limit`() {
        server.addPlayer("Steve")
        server.addPlayer("Alex")
        server.addPlayer("Herobrine")

        val snapshot = BukkitSnapshotBuilder(statisticsFull = false, maxPlayers = 2).build()

        assertEquals(3, snapshot.server.onlinePlayers)
        assertEquals(2, snapshot.players.size)
    }

    @Test
    fun `a server that cannot be measured reports no TPS at all`() {
        // MockBukkit has no `getTPS`, so there is no TPS to report. The old code answered 20.0 —
        // the ideal value — so a server that could not be measured reported a perfect score.
        server.addPlayer("Steve")

        val snapshot = BukkitSnapshotBuilder(statisticsFull = false, maxPlayers = 100).build()

        assertNull(snapshot.server.tps)
    }

    @Test
    fun `statistics are keyed by their short name`() {
        val player = server.addPlayer("Steve")
        player.setStatistic(Statistic.DEATHS, 3)

        val snapshot = BukkitSnapshotBuilder(statisticsFull = false, maxPlayers = 100).build()

        val entry = snapshot.players.single().statistics["DEATHS"]
        assertEquals(3L, entry?.value)
        assertEquals("UNTYPED", entry?.category)
    }

    @Test
    fun `a registry that cannot answer costs the item statistics and nothing else`() {
        val player = server.addPlayer("Steve")
        player.incrementStatistic(Statistic.CRAFT_ITEM, Material.STONE, 5)
        player.setStatistic(Statistic.DEATHS, 3)
        val reported = mutableListOf<String>()

        // MockBukkit cannot turn a `Material` into an item type, so it cannot say which materials
        // are items. That costs the item statistics. It must not cost the player, and it must not
        // cost the statistics that could be read.
        val snapshot = BukkitSnapshotBuilder(statisticsFull = true, maxPlayers = 100) { reported += it }.build()

        val stats = snapshot.players.single().statistics
        assertEquals(3L, stats["DEATHS"]?.value)
        assertFalse(stats.containsKey("STONE"))
        assertTrue(reported.any { it.contains("item materials") })
    }

    @Test
    fun `basic mode never asks the registry which materials are items`() {
        val player = server.addPlayer("Steve")
        player.incrementStatistic(Statistic.CRAFT_ITEM, Material.STONE, 5)
        val reported = mutableListOf<String>()

        val snapshot = BukkitSnapshotBuilder(statisticsFull = false, maxPlayers = 100) { reported += it }.build()

        assertFalse(snapshot.players.single().statistics.containsKey("STONE"))
        // The material list is only worked out when it is needed. In `basic` mode it is not needed,
        // so the registry is not asked at all — and a server whose registry cannot answer is not
        // affected by it.
        assertTrue(reported.none { it.contains("materials") })
    }

    @Test
    fun `a server without an advancement tree still gets a snapshot`() {
        server.addPlayer("Steve")
        val reported = mutableListOf<String>()

        // MockBukkit's `advancementIterator` throws. The players, the statistics and the TPS are
        // separate readings, and one of them failing is not a reason to report an empty server.
        val snapshot = BukkitSnapshotBuilder(statisticsFull = false, maxPlayers = 100) { reported += it }.build()

        assertEquals(1, snapshot.players.size)
        assertTrue(snapshot.players.single().advancements.isEmpty())
        assertTrue(reported.any { it.contains("advancements") })
    }
}
