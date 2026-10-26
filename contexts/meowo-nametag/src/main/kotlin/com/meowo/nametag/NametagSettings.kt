package com.meowo.nametag

/**
 * Tunables for the nametag context. Read once in `start()`, so a reload restarts the context and
 * every tracked player is rebuilt with the new values.
 */
data class NametagSettings(
    /** Draw the marker after each player's name. Turning this off leaves the vanilla nametag. */
    val enabled: Boolean = true,
    /** Idle time in seconds before a player counts as away. */
    val afkAfterSeconds: Int = 180,
    /** Seconds between idle sweeps. One second is responsive enough and costs almost nothing. */
    val checkIntervalSeconds: Int = 1,
) {
    init {
        require(afkAfterSeconds in 1..3600) { "nametag.afk-after-seconds must be 1..3600" }
        require(checkIntervalSeconds in 1..60) { "nametag.check-interval-seconds must be 1..60" }
    }

    val afkAfterMillis: Long get() = afkAfterSeconds * 1000L
}
