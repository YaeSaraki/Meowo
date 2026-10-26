package com.meowo.pomodoro.domain

/**
 * Pure-function state machine for one [SessionState].
 *
 * The handlers are intentionally **stateless**: every method takes a session and returns a new
 * one. Side effects (sounds, chat, BossBar updates) live in the application layer, where they are
 * observed by ticking the state handler and diffing the resulting session. That split keeps the
 * state machine unit-testable without a Bukkit instance.
 *
 * Each handler owns three transitions:
 *  - [onEnter] when the state becomes current; receives `previousState` so a state can reset
 *    differently when entering from PAUSED than when entering for the first time.
 *  - [tick] every second the session is in this state; returns the next session (typically the
 *    same with secondsLeft decremented, or a new state when the timer runs out).
 *  - [onExit] fires when the state stops being current; the only consumer is `WORK_COMPLETED`,
 *    which logs the total focus duration when the player finally moves on.
 */
interface StateHandler {
    val state: SessionState

    fun onEnter(session: PomodoroSession, previousState: SessionState?): PomodoroSession

    fun tick(session: PomodoroSession): PomodoroSession

    fun onExit(session: PomodoroSession): PomodoroSession = session
}
