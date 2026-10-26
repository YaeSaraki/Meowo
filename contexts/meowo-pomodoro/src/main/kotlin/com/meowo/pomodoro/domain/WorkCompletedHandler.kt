package com.meowo.pomodoro.domain

/**
 * The waiting state between finishing a WORK round and the player actively starting the next
 * phase. `extraSeconds` ticks up so the focus log credits the **actual** time spent on the task,
 * not the round duration.
 *
 * Ticking does not change state. The player has to explicitly `next` (→ break / long break) or
 * `stop` (→ STOPPED) to leave this state.
 */
class WorkCompletedHandler : StateHandler {
    override val state = SessionState.WORK_COMPLETED

    override fun onEnter(session: PomodoroSession, previousState: SessionState?): PomodoroSession {
        if (previousState == SessionState.PAUSED) return session
        return session.copy(secondsLeft = 0, extraSeconds = 0)
    }

    override fun tick(session: PomodoroSession): PomodoroSession =
        session.copy(extraSeconds = session.extraSeconds + 1)

    override fun onExit(session: PomodoroSession): PomodoroSession = session
}
