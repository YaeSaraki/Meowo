package com.meowo.pomodoro.domain

import java.util.UUID

/**
 * One player's running Pomodoro session — immutable, so every state change returns a new copy and
 * the previous value can be diffed against the new one for analytics and tests.
 *
 * `state` is the current lifecycle state; `previousState` is set when entering [SessionState.PAUSED]
 * so a `resume` knows where to go back to.
 *
 * `workSession` is the round counter (1..preset.sessions). It advances when the player moves from
 * `WORK_COMPLETED` into a short `BREAK`, and resets to 1 when they enter `LONG_BREAK` or restart
 * the cycle.
 *
 * `extraSeconds` ticks up only while the player lingers in `WORK_COMPLETED` past their work
 * duration; it is recorded in the focus log when they finally move on, so a player who actually
 * focuses longer than the timer set for gets credited for the real time spent.
 */
data class PomodoroSession(
    val state: SessionState,
    val previousState: SessionState?,
    val preset: Preset,
    val workSession: Int,
    val secondsLeft: Int,
    val extraSeconds: Int,
    val owner: UUID,
    val startLocation: LocationSnapshot?,
) {
    init {
        require(workSession in 1..preset.sessions) {
            "workSession $workSession is out of range 1..${preset.sessions}"
        }
        require(extraSeconds >= 0) { "extraSeconds must be non-negative" }
    }

    /** True when the timer is currently being driven by `tick()`. PAUSED is technically
     *  the same session but the timer is held, so callers branch on this rather than state. */
    val isActive: Boolean get() = state != SessionState.STOPPED

    /** The total seconds the current state would take from full to empty. */
    val phaseTotalSeconds: Int get() = when (state) {
        SessionState.WORK -> preset.workSeconds
        SessionState.BREAK -> preset.breakSeconds
        SessionState.LONG_BREAK -> preset.longBreakSeconds
        SessionState.WORK_COMPLETED -> preset.workSeconds + extraSeconds
        SessionState.PAUSED, SessionState.STOPPED -> preset.workSeconds
    }

    /** 0..1 progress: 1.0 = full, 0.0 = empty. PAUSED shows the previous phase's progress. */
    fun progress(): Double {
        val displayState = if (state == SessionState.PAUSED) previousState ?: state else state
        val total = when (displayState) {
            SessionState.WORK -> preset.workSeconds
            SessionState.BREAK -> preset.breakSeconds
            SessionState.LONG_BREAK -> preset.longBreakSeconds
            SessionState.WORK_COMPLETED -> preset.workSeconds
            SessionState.PAUSED, SessionState.STOPPED -> preset.workSeconds
        }
        if (total <= 0) return 0.0
        val ratio = when (displayState) {
            SessionState.WORK_COMPLETED -> 1.0
            else -> secondsLeft.toDouble() / total
        }
        return ratio.coerceIn(0.0, 1.0)
    }

    /** `MM:SS` formatting for the display layer. Always two digits per field. */
    fun timeLabel(): String {
        val totalSeconds = when (state) {
            SessionState.WORK_COMPLETED -> preset.workSeconds + extraSeconds
            else -> secondsLeft
        }.coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
