package com.meowo.pomodoro.domain

/**
 * The longer break that follows every Nth work round. Counts down, then the cycle starts again
 * from `WORK` with `workSession` reset to 1.
 */
class LongBreakHandler : StateHandler {
    override val state = SessionState.LONG_BREAK

    override fun onEnter(session: PomodoroSession, previousState: SessionState?): PomodoroSession {
        if (previousState == SessionState.PAUSED) return session
        return session.copy(
            secondsLeft = session.preset.longBreakSeconds,
            workSession = 1,
        )
    }

    override fun tick(session: PomodoroSession): PomodoroSession {
        val next = session.copy(secondsLeft = session.secondsLeft - 1)
        return if (next.secondsLeft <= 0) next.copy(
            state = SessionState.WORK,
            secondsLeft = next.preset.workSeconds,
            workSession = 1,
        ) else next
    }
}
