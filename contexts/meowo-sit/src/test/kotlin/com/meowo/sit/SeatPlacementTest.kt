package com.meowo.sit

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Stairs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.math.hypot

/**
 * The seat placement maths, tested against the real [seatAnchor] so a refactor cannot leave a
 * green suite behind stale geometry. A [Location] only needs a world reference, hence MockBukkit;
 * no context, entities or server state are involved.
 */
class SeatPlacementTest {

    private lateinit var server: ServerMock
    private val raised = SeatSpot(0.1, 0.35)

    @BeforeEach fun setUp() { server = MockBukkit.mock() }
    @AfterEach fun tearDown() { MockBukkit.unmock() }

    private fun at(x: Int, y: Int, z: Int) = Location(server.addSimpleWorld("w"), x.toDouble(), y.toDouble(), z.toDouble())

    @Test fun `the seat is raised above the block centre`() {
        val seat = seatAnchor(at(10, 64, 20), 0f, raised)
        assertEquals(64.6, seat.y, 1e-9, "0.5 centre + 0.1 offset")
        assertTrue(seat.y > 64.5, "raising the offset must lift the seat above the centre")
    }

    @Test fun `the seat is pushed towards the player's facing`() {
        // Minecraft's forward vector is (-sin, 0, cos), so each yaw pushes a different axis.
        assertTrue(seatAnchor(at(0, 0, 0), 0f, raised).z > 0.5, "yaw 0 faces +Z, so z must grow")
        assertTrue(seatAnchor(at(0, 0, 0), 180f, raised).z < 0.5, "yaw 180 faces -Z")
        assertTrue(seatAnchor(at(0, 0, 0), -90f, raised).x > 0.5, "yaw -90 faces +X, so x must grow")
        assertTrue(seatAnchor(at(0, 0, 0), 90f, raised).x < 0.5, "yaw 90 faces -X")
    }

    @Test fun `a zero forward offset keeps the seat in the block centre`() {
        val seat = seatAnchor(at(4, 70, -9), 37f, SeatSpot(0.1, 0.0))
        assertEquals(4.5, seat.x, 1e-9)
        assertEquals(-8.5, seat.z, 1e-9)
    }

    @Test fun `the forward push never exceeds the configured distance at any facing`() {
        val block = at(4, 70, -9)
        for (yaw in 0..359 step 15) {
            val seat = seatAnchor(block, yaw.toFloat(), raised)
            assertEquals(0.35, hypot(seat.x - 4.5, seat.z + 8.5), 1e-9, "yaw $yaw pushed the seat too far")
        }
    }

    @Test fun `a stair pushes the seat towards its own step, not the player's looking direction`() {
        // Minecraft auto-orients a stair with its step towards the player, so a player standing on
        // the step side is looking AWAY from the seat. The block's facing must win over the player's.
        val world = server.addSimpleWorld("stairs")
        val block = world.getBlockAt(0, 64, 0)
        block.type = Material.OAK_STAIRS
        val stairs = block.blockData as Stairs
        stairs.facing = BlockFace.NORTH   // tall side looks north, so the step is south
        stairs.half = Bisected.Half.BOTTOM
        block.blockData = stairs

        val spot = SeatSpot(0.1, 0.35, (block.blockData as Stairs).facing.oppositeFace)
        assertEquals(BlockFace.SOUTH, spot.direction, "the step is the side opposite to the tall face")
        // Yaw 180 means the player faces -Z (north), yet the seat still goes to +Z (the step).
        val seat = seatAnchor(block.location, 180f, spot)
        assertTrue(seat.z > 0.5, "the seat must follow the stair's step (south), was ${seat.z}")
    }

    @Test fun `an explicit direction overrides the player yaw`() {
        val block = at(0, 0, 0)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val spot = SeatSpot(0.0, 0.5, face)
            val seat = seatAnchor(block, 0f, spot)
            val vector = face.direction
            assertEquals(vector.x * 0.5, seat.x - 0.5, 1e-9, "$face x")
            assertEquals(vector.z * 0.5, seat.z - 0.5, 1e-9, "$face z")
        }
    }

    @Test fun `the reach limit keeps a distant click from teleporting a chair into place`() {
        val settings = SitSettings()
        // Vanilla reach is about 4.5, so 4.0 still covers a normal click while leaving headroom
        // for a plugin that hands out absurd reach.
        assertTrue(settings.maxDistance in 1.0..4.5, "the default must not exceed normal interaction reach")
        assertThrows(IllegalArgumentException::class.java) { SitSettings(maxDistance = 0.5) }
        assertThrows(IllegalArgumentException::class.java) { SitSettings(maxDistance = 50.0) }
    }

    @Test fun `a config value that would strand players is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { SitSettings(stairOffsetY = 5.0) }
        assertThrows(IllegalArgumentException::class.java) { SitSettings(stairForward = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { SitSettings(targetRange = 0) }
    }

    @Test fun `each sittable kind gets its own height, with the carpet lowest`() {
        val settings = SitSettings()
        val heights = listOf(
            settings.stairOffsetY to "stairs",
            settings.slabOffsetY to "slabs",
            settings.carpetOffsetY to "carpet",
        )
        assertEquals(3, heights.map { it.first }.toSet().size, "the three kinds must not share a height: $heights")
        // A carpet is 1/16 of a block and lies on the floor, so its seat must be the lowest of the
        // three even though the offset is measured from the block centre rather than the surface.
        assertTrue(
            settings.carpetOffsetY < settings.slabOffsetY,
            "carpet (${settings.carpetOffsetY}) must sit below slabs (${settings.slabOffsetY})",
        )
        assertTrue(
            settings.slabOffsetY < settings.stairOffsetY,
            "a slab (${settings.slabOffsetY}) is a thinner shelf than a stair step (${settings.stairOffsetY})",
        )
    }

    @Test fun `a slab is pushed back further than a stair and a carpet is not pushed at all`() {
        val settings = SitSettings()
        assertTrue(
            settings.slabForward < settings.stairForward,
            "a slab is only half a block deep, so it should be pushed back less (${settings.slabForward})",
        )
        assertEquals(0.0, settings.carpetForward, 0.0, "a carpet is flush with the floor and needs no push")
    }

    @Test fun `the anchor does not mutate the block location it was given`() {
        val block = at(1, 2, 3)
        seatAnchor(block, 90f, raised)
        assertEquals(1.0, block.x, 1e-9)
        assertEquals(2.0, block.y, 1e-9)
        assertEquals(3.0, block.z, 1e-9)
    }

}
