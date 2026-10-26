package com.meowo.pomodoro.domain

/**
 * The short break between two work rounds. Counts down and flips back to `WORK`. The work-session
 * counter is left alone — it was advanced when the player chose to take a break, and now we are
 * about to start the next round of the same cycle.
 *
 * Long breaks reset the counter; short breaks do not.
 */
class BreakHandler : StateHandler {
    override val state = SessionState.BREAK

    override fun onEnter(session: PomodoroSession, previousState: SessionState?): PomodoroSession {
        if (previousState == SessionState.PAUSED) return session
        return session.copy(secondsLeft = session.preset.breakSeconds)
    }

    override fun tick(session: PomodoroSession): PomodoroSession {
        val next = session.copy(secondsLeft = session.secondsLeft - 1)
        return if (next.secondsLeft <= 0) next.copy(state = SessionState.WORK, secondsLeft = next.preset.workSeconds)
        else next
    }
}
