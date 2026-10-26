package com.meowo.pomodoro.domain

/**
 * Maps each [SessionState] to its [StateHandler]. Kept in the domain layer because the mapping is
 * part of the state-machine contract; an enum-and-handler that disagree is a bug.
 */
object StateHandlers {
    private val map: Map<SessionState, StateHandler> = listOf(
        WorkHandler(),
        BreakHandler(),
        LongBreakHandler(),
        WorkCompletedHandler(),
        PausedHandler(),
        StoppedHandler(),
    ).associateBy { it.state }

    operator fun get(state: SessionState): StateHandler = map.getValue(state)
}
