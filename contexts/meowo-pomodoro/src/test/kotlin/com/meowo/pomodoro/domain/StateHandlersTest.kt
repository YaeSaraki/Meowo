package com.meowo.pomodoro.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StateHandlersTest {
    private val preset = Preset("standard", "Standard", "CLOCK", false,
        workSeconds = 60, breakSeconds = 60, longBreakSeconds = 120, sessions = 2, builtin = true)
    private val owner = java.util.UUID.randomUUID()

    private fun session(
        state: SessionState,
        previousState: SessionState? = null,
        workSession: Int = 1,
        secondsLeft: Int = 60,
        extraSeconds: Int = 0,
    ) = PomodoroSession(state, previousState, preset, workSession, secondsLeft, extraSeconds, owner, null)

    @Test fun `work handler resets secondsLeft on fresh entry`() {
        val after = StateHandlers[SessionState.WORK].onEnter(session(SessionState.WORK, secondsLeft = 999), null)
        assertEquals(60, after.secondsLeft)
    }

    @Test fun `work handler keeps secondsLeft when resuming from paused`() {
        val after = StateHandlers[SessionState.WORK].onEnter(session(SessionState.WORK, secondsLeft = 20), SessionState.PAUSED)
        assertEquals(20, after.secondsLeft)
    }

    @Test fun `work tick decrements and crosses to work-completed at zero`() {
        val ticked = StateHandlers[SessionState.WORK].tick(session(SessionState.WORK, secondsLeft = 2))
        assertEquals(1, ticked.secondsLeft)
        assertEquals(SessionState.WORK, ticked.state)
        val zero = StateHandlers[SessionState.WORK].tick(session(SessionState.WORK, secondsLeft = 1))
        assertEquals(SessionState.WORK_COMPLETED, zero.state)
    }

    @Test fun `break tick flips back to work at zero`() {
        val ticked = StateHandlers[SessionState.BREAK].tick(session(SessionState.BREAK, secondsLeft = 1))
        assertEquals(SessionState.WORK, ticked.state)
        assertEquals(preset.workSeconds, ticked.secondsLeft)
    }

    @Test fun `long break tick resets work session counter to one`() {
        val ticked = StateHandlers[SessionState.LONG_BREAK].tick(
            session(SessionState.LONG_BREAK, workSession = 2, secondsLeft = 1)
        )
        assertEquals(SessionState.WORK, ticked.state)
        assertEquals(1, ticked.workSession)
    }

    @Test fun `long break entry resets work session`() {
        val after = StateHandlers[SessionState.LONG_BREAK].onEnter(
            session(SessionState.LONG_BREAK, workSession = 2), null
        )
        assertEquals(1, after.workSession)
        assertEquals(preset.longBreakSeconds, after.secondsLeft)
    }

    @Test fun `work-completed tick increments extra seconds`() {
        val first = StateHandlers[SessionState.WORK_COMPLETED].tick(session(SessionState.WORK_COMPLETED, secondsLeft = 0, extraSeconds = 5))
        assertEquals(6, first.extraSeconds)
        assertEquals(SessionState.WORK_COMPLETED, first.state)
    }

    @Test fun `paused tick does nothing`() {
        val ticked = StateHandlers[SessionState.PAUSED].tick(session(SessionState.PAUSED, secondsLeft = 7))
        assertEquals(7, ticked.secondsLeft)
    }

    @Test fun `stopped tick does nothing`() {
        val ticked = StateHandlers[SessionState.STOPPED].tick(session(SessionState.STOPPED))
        assertEquals(SessionState.STOPPED, ticked.state)
    }

    @Test fun `state handlers map every state`() {
        SessionState.entries.forEach { assertNotNull(StateHandlers[it]) }
    }
}
