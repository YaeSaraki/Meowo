package com.meowo.nametag.domain

/**
 * When a player last moved, and whether that counts as being away.
 *
 * This is the "is this player idle" variable the nametag reads. It is deliberately pure: no
 * Bukkit, no clock, no scheduler. The caller supplies movement and the current timestamp, which is
 * what makes the idle rule testable without a server.
 *
 * @param lastMovedAtMillis timestamp of the last real movement; movement means changing block, not
 *   turning on the spot, so a player idling while looking around still counts as idle.
 * @param afkAfterMillis idle time after which the player counts as away.
 * @param manualAway set by the player through `/afk`; independent of idle time and cleared by moving.
 */
data class IdleState(
    val lastMovedAtMillis: Long,
    val afkAfterMillis: Long,
    val manualAway: Boolean = false,
) {
    init {
        require(afkAfterMillis > 0) { "afkAfterMillis must be positive" }
    }

    /** How long the player has been still at [nowMillis]. */
    fun idleMillis(nowMillis: Long): Long = (nowMillis - lastMovedAtMillis).coerceAtLeast(0L)

    /** True when the player is away, either by idling or by asking for it. */
    fun isAfk(nowMillis: Long): Boolean = manualAway || idleMillis(nowMillis) >= afkAfterMillis

    /** Records a real movement, which clears both the idle run and a manual `/afk`. */
    fun moved(nowMillis: Long): IdleState = copy(lastMovedAtMillis = nowMillis, manualAway = false)

    /** Toggles the manual flag, used by `/afk`. */
    fun toggledManual(nowMillis: Long): IdleState =
        if (manualAway) moved(nowMillis) else copy(lastMovedAtMillis = nowMillis, manualAway = true)
}
