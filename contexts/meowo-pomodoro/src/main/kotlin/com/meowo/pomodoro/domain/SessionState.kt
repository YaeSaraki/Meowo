package com.meowo.pomodoro.domain

/**
 * The six discrete states a Pomodoro session moves through.
 *
 * The transitions are deliberate and minimal:
 * ```
 *  WORK  ──tick to 0──▶  WORK_COMPLETED  ──next──▶  BREAK
 *                                       ──long──▶  LONG_BREAK
 *  BREAK  ──tick to 0──▶  WORK
 *  LONG_BREAK ──tick to 0──▶  WORK (round counter reset to 1)
 *  any   ──pause──▶  PAUSED  ──resume──▶  previous
 *  any   ──stop───▶  STOPPED
 * ```
 * `WORK_COMPLETED` is a **waiting** state: the player has finished their work block but hasn't yet
 * chosen to start the break. They can `next` to break / long break or `stop` to call it a day.
 *
 * Each state owns a [StateHandler] that knows what happens on entry, on exit and on each tick.
 */
enum class SessionState(val messageKey: String) {
    WORK("status-work"),
    WORK_COMPLETED("status-work-completed"),
    BREAK("status-break"),
    LONG_BREAK("status-long-break"),
    PAUSED("status-paused"),
    STOPPED("status-stopped");

    /** Whether the timer should be counting down in this state. */
    val isCounting: Boolean get() = this == WORK || this == BREAK || this == LONG_BREAK || this == WORK_COMPLETED
}
