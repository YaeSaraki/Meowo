package com.meowo.pomodoro.domain

/**
 * Terminal state. The session is over and no further transitions are valid. `tick` and `onEnter`
 * are no-ops so a stale tick fired after `stop` cannot resurrect the session.
 */
class StoppedHandler : StateHandler {
    override val state = SessionState.STOPPED

    override fun onEnter(session: PomodoroSession, previousState: SessionState?): PomodoroSession = session

    override fun tick(session: PomodoroSession): PomodoroSession = session
}
