package com.meowo.pomodoro.domain

import java.util.UUID

/**
 * Where a player was when their session began. A snapshot rather than a `Location` so the value
 * can ride across the SQLite worker without dragging Bukkit's world reference along — same rule
 * `meowo-home.Home` follows.
 *
 * Used by the application layer to warn players who stray beyond `max-distance` from the start
 * point during a WORK session. We never move the player back; this is **detection only**.
 */
data class LocationSnapshot(
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {
    init {
        require(listOf(x, y, z).all { it.isFinite() }) { "coordinates must be finite" }
        require(yaw.isFinite() && pitch.isFinite()) { "rotation must be finite" }
    }
}
