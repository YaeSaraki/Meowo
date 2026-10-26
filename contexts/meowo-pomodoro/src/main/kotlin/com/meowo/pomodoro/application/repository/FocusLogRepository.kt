package com.meowo.pomodoro.application.repository

import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Records one completed WORK round — how long the player actually focused (work duration plus
 * any extra seconds they lingered in `WORK_COMPLETED`).
 *
 * The application layer commits a row whenever the player leaves `WORK_COMPLETED` (by either
 * `next` to a break or `stop`). It is the only consumer.
 */
interface FocusLogRepository : AutoCloseable {
    /** Records one completed WORK round for [owner]. */
    fun logWork(owner: UUID, presetId: String, presetName: String, workSession: Int, durationSeconds: Int): CompletableFuture<Unit>

    /** Total focus time in seconds for [owner] since the dawn of time. Used for stats. */
    fun totalSeconds(owner: UUID): CompletableFuture<Long>

    /** Focus time in seconds for [owner] for a given ISO date `YYYY-MM-DD` (player-local). */
    fun secondsOnDate(owner: UUID, date: String): CompletableFuture<Long>
}
