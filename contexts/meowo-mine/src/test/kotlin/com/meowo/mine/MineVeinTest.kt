package com.meowo.mine

import org.bukkit.Material
import org.bukkit.util.BlockVector
import org.mockbukkit.mockbukkit.ServerMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

/**
 * Vein detection. A vein is the run of same-ore blocks still connected around the hole the player
 * just dug, so breaking one block in a run must report the whole run rather than "× 1".
 */
class MineVeinTest {

    private lateinit var server: ServerMock
    private lateinit var world: org.bukkit.World

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("veins")
        // The production guard skips unloaded chunks on purpose, so the test world must actually
        // have one loaded — a real player always stands inside a loaded chunk.
        world.getChunkAt(0, 0)
    }

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    /**
     * A straight run of diamond ore along x at y=64. [mined] is the block the player is digging —
     * it is still in the world, because BlockBreakEvent fires before the block becomes air. The
     * scan must exclude it and the caller adds it back, so the reported vein size is the run length.
     */
    private fun vein(length: Int, mined: Int = -1) {
        for (i in 0 until length) world.getBlockAt(i, 64, 0).type = Material.DIAMOND_ORE
    }

    @Test fun `a lone ore is a find of one`() {
        world.getBlockAt(0, 64, 0).type = Material.DIAMOND_ORE
        assertEquals(0, connected(0, 64, 0).size, "nothing is left around a single block")
    }

    @Test fun `a horizontal vein is counted as one run`() {
        vein(6, mined = 0)
        assertEquals(5, connected(0, 64, 0).size, "the whole run to the right is still there")
    }

    @Test fun `a vein is found from any block inside it`() {
        vein(6, mined = 3)
        // Mining the middle: both sides stay connected, so five of the six remain.
        assertEquals(5, connected(3, 64, 0).size, "plus the mined block is a vein of six")
    }

    @Test fun `counting the same vein twice does not credit it twice`() {
        // A six-ore vein: the player breaks each block in turn. Only the first break may count,
        // otherwise one vein is announced six times.
        vein(6)
        val counted = LinkedHashSet<BlockVector>()
        val reported = mutableListOf<Int>()

        // Break from the left, exactly as a player would.
        for (x in 0..5) {
            // Breaking a block the player has already been credited for is not a new find.
            if (BlockVector(x.toDouble(), 64.0, 0.0) in counted) continue
            val fresh = connected(x, 64, 0, counted)
            reported += fresh.size + 1
            counted.addAll(fresh)
            counted.add(BlockVector(x.toDouble(), 64.0, 0.0))   // the mined block itself
        }
        assertEquals(listOf(6), reported,
            "one vein is announced once, for its whole size; the rest of it is already discovered")
    }

    @Test fun `a cap of zero switches the scan off entirely`() {
        // The guard that used to sit in a private method nothing could reach, and that the test's
        // own copy of the scan did not have: a zero cap means "do not look for a vein at all".
        vein(4)
        assertTrue(
            MineContext.scanVein(
                world, BlockVector(0.0, 64.0, 0.0), Material.DIAMOND_ORE, 0, emptySet(),
            ).isEmpty(),
            "a zero cap must find nothing",
        )
    }

    @Test fun `a different player is credited for the same vein`() {
        vein(6)
        val first = connected(0, 64, 0, LinkedHashSet())
        val second = connected(0, 64, 0, LinkedHashSet())
        assertEquals(5, first.size)
        assertEquals(5, second.size, "credit is per player: someone else walking in still gets a find")
    }

    @Test fun `the count is bounded so an artificial pile cannot walk the chunk`() {
        // A 6x6 blob (36 blocks) sits entirely inside one chunk, so the only thing that can stop the
        // scan before the end is the cap.
        for (x in 0..5) for (z in 0..5) world.getBlockAt(x, 64, z).type = Material.DIAMOND_ORE
        assertEquals(32, connected(0, 64, 0).size, "the scan stops at maxVeinSize rather than counting all 35")
    }

    @Test fun `the scan never leaves a loaded chunk`() {
        // A long run crosses a chunk border; only the loaded part may be counted, because walking
        // into an unloaded chunk would generate it and stall the tick.
        vein(40)
        val found = connected(0, 64, 0).size
        assertTrue(found <= 15, "chunk 0 holds x=0..15, so at most fifteen may be counted, was $found")
        assertTrue(found > 0, "the part inside the loaded chunk must still be found")
    }

    @Test fun `a different ore is not counted into the vein`() {
        vein(4, mined = 0)
        world.getBlockAt(4, 64, 0).type = Material.EMERALD_ORE
        assertEquals(3, connected(0, 64, 0).size, "emerald breaks the diamond run of four")
    }

    @Test fun `diagonal contact does not join a vein`() {
        // Ore veins connect by face only; a diagonal neighbour is a different vein.
        world.getBlockAt(0, 64, 0).type = Material.DIAMOND_ORE
        world.getBlockAt(1, 65, 1).type = Material.DIAMOND_ORE
        assertEquals(0, connected(0, 64, 0).size, "a diagonal block is not connected")
    }

    @Test fun `a vertical vein is found`() {
        for (y in 64..69) world.getBlockAt(0, y, 0).type = Material.ANCIENT_DEBRIS
        assertEquals(5, connected(0, 64, 0).size)
    }

    /**
     * The real scan, not a copy of it.
     *
     * This file used to carry its own breadth-first search — a second implementation of the one in
     * `MineContext`, with its own hard-coded cap of 32 — and never called the production code at
     * all. It could not have failed when the production code changed: the cap could have been 64
     * in the plugin and 32 in the test, and the suite would still have been green. Now it drives
     * [MineContext.scanVein] with the cap the plugin actually uses.
     */
    private fun connected(x: Int, y: Int, z: Int, already: Set<BlockVector> = emptySet()): List<BlockVector> =
        MineContext.scanVein(
            world = world,
            origin = BlockVector(x.toDouble(), y.toDouble(), z.toDouble()),
            type = world.getBlockAt(x, y, z).type,
            limit = MineSettings().maxVeinSize,
            already = already,
        )
}
