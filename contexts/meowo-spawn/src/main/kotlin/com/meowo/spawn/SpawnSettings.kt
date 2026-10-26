package com.meowo.spawn

/**
 * @param world world whose spawn is used; empty means "the world the player is currently in".
 * @param warmupSeconds stand-still delay before the teleport; 0 teleports immediately.
 * @param cooldownSeconds minimum spacing between accepted requests.
 * @param invulnerabilitySeconds post-teleport damage immunity; restores the player's prior state.
 * @param safety whether to search for a standable spot instead of trusting the spawn point.
 * @param safeSearchRange how many blocks up and down the safety search may look.
 */
data class SpawnSettings(
    val world: String = "",
    val warmupSeconds: Int = 3,
    val cooldownSeconds: Int = 5,
    val invulnerabilitySeconds: Int = 4,
    val centerAlign: Boolean = true,
    val safety: Boolean = true,
    val safeSearchRange: Int = 16,
) {
    init {
        require(warmupSeconds in 0..30)
        require(cooldownSeconds in 0..300)
        require(invulnerabilitySeconds in 0..60)
        require(safeSearchRange in 0..64)
    }
}
