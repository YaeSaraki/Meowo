package com.meowo.webapi.domain

/**
 * The read model the HTTP API serves.
 *
 * It is a **snapshot**: it is built on the main thread and then only read. The HTTP threads never
 * touch the Bukkit API, so nothing that is not thread safe ever crosses a thread boundary.
 */
data class ServerSnapshot(
    val onlinePlayers: Int,
    val maxPlayers: Int,
    /**
     * The server's ticks per second, or `null` when the server did not tell us.
     *
     * It used to be a `Double` that was filled with 20.0 whenever the reading failed. 20.0 is the
     * *ideal* TPS, so a server that could not be measured reported a perfect score — and a perfect
     * score is the one reading nobody checks. A missing reading is now a missing reading.
     */
    val tps: Double?,
    val heartbeatStatus: Boolean,
    val updateTime: Long,
)

data class StatisticEntry(
    val category: String,
    val key: String,
    val value: Long,
)

data class AdvancementEntry(
    val key: String,
    val done: Boolean,
    val completed: List<String>,
    val remaining: List<String>,
)

/**
 * A skin is a **URL**, not an image.
 *
 * Downloading it here would mean network I/O on the main thread; the consumer (the Wofuf Worker
 * on Cloudflare) fetches the bytes and stores them itself.
 */
data class SkinReference(
    val type: String,
    val url: String,
    val capeUrl: String,
)

data class PlayerSnapshot(
    val uuid: String,
    val name: String,
    val firstLogin: Long,
    val lastLogin: Long,
    val totalPlaytimeSeconds: Long,
    val statistics: Map<String, StatisticEntry>,
    val advancements: List<AdvancementEntry>,
    val skin: SkinReference,
)

data class WorldSnapshot(
    val server: ServerSnapshot,
    val players: List<PlayerSnapshot>,
    val generatedAt: Long,
)
