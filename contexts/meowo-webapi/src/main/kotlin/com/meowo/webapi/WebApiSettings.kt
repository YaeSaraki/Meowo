package com.meowo.webapi

/**
 * Settings for the read-only HTTP API.
 *
 * The defaults live in `config.yml` and nowhere else — see the note in `GlobalConfig`.
 */
data class WebApiSettings(
    /** TCP port to listen on. `0` asks the OS for a free port (tests only). */
    val port: Int,
    /** Interface to bind. `127.0.0.1` keeps the port off the public network. */
    val bind: String,
    /**
     * The shared secret.
     *
     * Empty means the context refuses to start: a port nobody can authenticate against is worse
     * than no port at all.
     */
    val secret: String,
    /** How often the snapshot is rebuilt, in seconds. */
    val snapshotIntervalSeconds: Int,
    /** `full` also reads item/block/entity statistics (slow); `basic` reads the untyped ones only. */
    val statisticsMode: String,
    /** Upper bound on the players in one snapshot. */
    val maxPlayers: Int,
    /** When not empty, only these addresses may call the API. */
    val allowedIps: Set<String>,
) {
    val statisticsFull: Boolean get() = statisticsMode.equals("full", ignoreCase = true)
}
