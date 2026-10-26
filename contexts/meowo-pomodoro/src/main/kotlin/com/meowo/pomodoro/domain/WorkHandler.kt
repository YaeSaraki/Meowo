package com.meowo.pomodoro.domain

/**
 * A WORK round. Counts down from `workSeconds`. When it hits zero the session flips to
 * `WORK_COMPLETED`; when it gets within `5` seconds of zero a "wrap up" warning fires (the
 * application layer reads [warningPending]).
 *
 * Re-entering from `PAUSED` does not reset the timer — that is the whole point of pausing —
 * so `onEnter` only resets the duration when entering fresh.
 */
class WorkHandler : StateHandler {
    override val state = SessionState.WORK

    override fun onEnter(session: PomodoroSession, previousState: SessionState?): PomodoroSession {
        // Resume: keep secondsLeft, do not reset.
        if (previousState == SessionState.PAUSED) return session
        return session.copy(secondsLeft = session.preset.workSeconds)
    }

    override fun tick(session: PomodoroSession): PomodoroSession {
        val next = session.copy(secondsLeft = session.secondsLeft - 1)
        return if (next.secondsLeft <= 0) next.copy(state = SessionState.WORK_COMPLETED, secondsLeft = 0)
        else next
    }
}
