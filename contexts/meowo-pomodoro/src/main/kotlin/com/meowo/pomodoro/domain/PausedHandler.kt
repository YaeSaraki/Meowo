package com.meowo.pomodoro.domain

/**
 * Pausing holds the timer at whatever `secondsLeft` it had. `previousState` is preserved so a
 * resume knows whether to go back to `WORK`, `BREAK` or `LONG_BREAK`.
 *
 * Ticking here is a no-op: the whole point of pause is that secondsLeft doesn't move.
 */
class PausedHandler : StateHandler {
    override val state = SessionState.PAUSED

    override fun onEnter(session: PomodoroSession, previousState: SessionState?): PomodoroSession = session

    override fun tick(session: PomodoroSession): PomodoroSession = session
}
