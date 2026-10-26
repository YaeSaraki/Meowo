package com.meowo.platform

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World

/**
 * Finds a standable spot near a wanted teleport target.
 *
 * Deliberately conservative, because a bad destination scan is the classic way to lag or trap players:
 *  - **never generates chunks synchronously.** [find] is the blocking form and refuses an unloaded
 *    target; [findAsync] is the form callers should use, and it loads the chunk off the main thread
 *    first. Between them there is no path that generates a chunk on a tick.
 *  - **bounded in both directions.** It walks at most [range] blocks up and down, so a void world or
 *    an all-air column terminates instead of spinning until it hits the bottom of the world.
 *  - **checks the whole body.** Feet and head must both be passable and free of lethal blocks, and the
 *    block under the feet must be solid — "first solid block from above" alone happily drops players
 *    inside lava or suffocates them in a 1x1 shaft.
 *  - **prefers the exact target**, then the nearest spot above and below, which matches how players
 *    read "teleport to the nearest safe block" without surprising them by landing on a roof.
 */
object SafeDestination {
    sealed interface Outcome {
        /** A standable spot was found. */
        class Found(val location: Location) : Outcome
        /** The target chunk is not loaded and must not be generated. Only [find] returns this. */
        data object ChunkNotLoaded : Outcome
        /** Nothing standable within the search range. */
        data object Unsafe : Outcome
    }

    /** Blocks a player must never be left standing in or on. */
    private val LETHAL = setOf(
        Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.POWDER_SNOW,
    )

    /**
     * @param target where the player wanted to end up; its yaw/pitch are preserved.
     * @param range how many blocks to search up and down; 0 means "that exact block or nothing".
     * @param centre align horizontally to the middle of the destination block.
     */
    fun find(target: Location, range: Int, centre: Boolean): Outcome {
        val world = target.world ?: return Outcome.Unsafe
        val x = target.blockX
        val z = target.blockZ
        // Guard first: every getBlockAt below would otherwise force chunk generation on the tick thread.
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return Outcome.ChunkNotLoaded
        val origin = target.blockY
        // +1/-1 keep the ground lookup and head lookup inside the build limits.
        val bottom = maxOf(origin - range, world.minHeight + 1)
        val top = minOf(origin + range, world.maxHeight - 2)
        if (bottom > top) return Outcome.Unsafe
        for (offset in 0..(top - bottom)) {
            for (candidate in intArrayOf(origin + offset, origin - offset)) {
                if (candidate < bottom || candidate > top) continue
                if (offset == 0 && candidate != origin) continue
                if (standable(world, x, candidate, z)) return Outcome.Found(place(world, x, candidate, z, target, centre))
            }
        }
        return Outcome.Unsafe
    }

    /**
     * The form a teleport should actually use.
     *
     * A spawn point or a home is by definition somewhere the player may not have walked yet — a
     * first-time visitor, a setspawn from months ago, a base in another world. Refusing to go there
     * because the chunk is not loaded made `/meo spawn` and `/meo home` useless in exactly the
     * situation they exist for.
     *
     * So the chunk is loaded **asynchronously** first (`generate = true`, so a never-generated
     * column is created too) and only then scanned. The scan itself runs on whichever thread
     * completed the load, which for a real generation is a chunk worker, never a tick.
     *
     * The future always completes with [Outcome.Found] or [Outcome.Unsafe]; a world that vanished
     * mid-load becomes [Outcome.Unsafe] rather than an exception, because a teleport that cannot
     * happen should read as "nowhere safe" and not as a stack trace in the log.
     */
    fun findAsync(
        target: Location,
        range: Int,
        centre: Boolean,
        /**
         * How to obtain a missing chunk. Injected so a test server can load synchronously:
         * `getChunkAtAsync` is driven by a real server's chunk system and never completes in a
         * mock, which would make every "arrives somewhere unvisited" test hang rather than fail.
         */
        load: (World, Int, Int) -> java.util.concurrent.CompletionStage<Boolean> =
            { world, x, z -> world.getChunkAtAsync(x, z, true).thenApply { true } },
    ): java.util.concurrent.CompletableFuture<Outcome> {
        val world = target.world
            ?: return java.util.concurrent.CompletableFuture.completedFuture(Outcome.Unsafe)
        if (world.isChunkLoaded(target.blockX shr 4, target.blockZ shr 4)) {
            return java.util.concurrent.CompletableFuture.completedFuture(find(target, range, centre))
        }
        return runCatching {
            load(world, target.blockX shr 4, target.blockZ shr 4)
                .thenApply { find(target, range, centre) }
                .toCompletableFuture()
        }.getOrElse {
            java.util.concurrent.CompletableFuture.completedFuture(Outcome.Unsafe)
        }
    }

    /**
     * Uses `Material.isSolid()` rather than `Block.isPassable()`: both are correct on Paper, but
     * `isPassable` is an unimplemented stub in MockBukkit 4.116.1 and would make this untestable.
     * "Not a full solid block" is exactly the property we need for somewhere a player can stand.
     */
    private fun standable(world: World, x: Int, y: Int, z: Int): Boolean {
        val feet = world.getBlockAt(x, y, z).type
        val head = world.getBlockAt(x, y + 1, z).type
        if (feet.isSolid || head.isSolid) return false
        if (feet in LETHAL || head in LETHAL) return false
        val ground = world.getBlockAt(x, y - 1, z).type
        return ground.isSolid && ground !in LETHAL
    }

    private fun place(world: World, x: Int, y: Int, z: Int, look: Location, centre: Boolean): Location =
        Location(
            world,
            if (centre) x + 0.5 else look.x,
            y.toDouble(),
            if (centre) z + 0.5 else look.z,
        ).also { it.yaw = look.yaw; it.pitch = look.pitch }
}
