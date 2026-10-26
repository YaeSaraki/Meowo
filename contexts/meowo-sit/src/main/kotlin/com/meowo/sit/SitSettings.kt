package com.meowo.sit

import org.bukkit.Location

/**
 * Tunables for `/sit` and `/ride`. Read once in `start()`, so a reload restarts the context and
 * every player is rebuilt with the new values.
 *
 * The per-kind offsets follow the mainstream plugin model (GSit, Simple Chairs): one tunable offset
 * per *block kind* rather than exact per-shape geometry. Vanilla stair-half and carpet-extent data
 * is not reachable through a stable API in Paper 26.2, and hard-coding a geometry table is exactly
 * what makes these plugins break on version bumps. If a seat looks low or high on your server,
 * change it here — no code change needed.
 */
data class SitSettings(
    /** Width of the player search area; legacy larger values are capped at three blocks. */
    val targetRange: Int = 3,
    /** Allow right-clicking stairs, slabs and carpets to sit on them. */
    val rightClickBlocks: Boolean = true,
    /**
     * How close the player must be to the seat. Another plugin can extend interaction reach far
     * past vanilla's ~4.5 blocks; a chair across the room would trap whoever takes it.
     */
    val maxDistance: Double = 4.0,
    /**
     * Y offset from the clicked block's centre, per kind. The three surfaces are genuinely
     * different heights, so they get three different numbers rather than one shared value:
     *
     *  - **stairs** sit on the lower step, the highest of the three, so it stays the reference;
     *  - **slabs** are a thinner shelf, so the seat drops below the stair one;
     *  - **carpets** are only 1/16 of a block and lie on the floor, so the seat hugs the ground.
     *
     * Anchors are relative to the block *centre* (y + 0.5), not to the surface, so a carpet needs a
     * clearly negative value. If a seat looks wrong on your server, change it here.
     */
    val stairOffsetY: Double = 0.1,
    val slabOffsetY: Double = -0.15,
    val carpetOffsetY: Double = -0.5,
    /**
     * How far in front of the block centre the seat is placed, along the player's facing. Pushing
     * the stand towards the player puts the visible seat at the near edge instead of burying it in
     * the middle of the block. Slabs are only half a block deep, so they are pushed back a little;
     * a carpet needs no push at all because it is already flush with the floor.
     */
    val stairForward: Double = 0.35,
    val slabForward: Double = 0.2,
    val carpetForward: Double = 0.0,
) {
    init {
        require(targetRange in 1..32) { "sit.target-range must be 1..32" }
        require(maxDistance in 1.0..8.0) { "sit.max-distance must be 1..8" }
        listOf(stairOffsetY, slabOffsetY, carpetOffsetY).forEach {
            require(it in -2.0..2.0) { "sit offsets must be -2..2" }
        }
        listOf(stairForward, slabForward, carpetForward).forEach {
            require(it in 0.0..2.0) { "sit forward offsets must be 0..2" }
        }
    }
}

/**
 * Vertical and forward placement for one block kind.
 *
 * [direction] overrides the player's own facing when the block knows better — stairs push the seat
 * towards their step instead of towards whichever way the player happened to be looking. Null means
 * "use the player's facing".
 */
internal data class SeatSpot(val y: Double, val forward: Double, val direction: org.bukkit.block.BlockFace? = null)

/**
 * Where a seat stand is placed for a given block, facing and [SeatSpot].
 *
 * Starts at the block centre, moves up by `spot.y` and towards [direction] (or the player's own
 * facing when [SeatSpot.direction] is null) by `spot.forward`. Minecraft's forward vector for a
 * yaw is `(-sin, 0, cos)`, so a yaw of 0 — facing +Z — increases z.
 *
 * Kept as a pure function so the geometry is testable without a server, a world or a context.
 */
internal fun seatAnchor(block: Location, yaw: Float, spot: SeatSpot): Location {
    val (ux, uz) = spot.direction?.let { face ->
        val vector = face.direction
        vector.x to vector.z
    } ?: run {
        val radians = Math.toRadians(yaw.toDouble())
        -kotlin.math.sin(radians) to kotlin.math.cos(radians)
    }
    return block.clone().add(
        0.5 + ux * spot.forward,
        0.5 + spot.y,
        0.5 + uz * spot.forward,
    )
}
