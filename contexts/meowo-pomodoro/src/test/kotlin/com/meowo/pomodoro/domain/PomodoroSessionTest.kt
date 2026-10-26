package com.meowo.pomodoro.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PomodoroSessionTest {
    private fun preset(
        id: String = "standard",
        work: Int = 1500,
        brk: Int = 300,
        longBrk: Int = 900,
        sessions: Int = 4,
    ) = Preset(id, "Standard", "CLOCK", false, work, brk, longBrk, sessions, true)

    private fun session(
        state: SessionState = SessionState.WORK,
        preset: Preset = preset(),
        workSession: Int = 1,
        secondsLeft: Int = 1500,
        extraSeconds: Int = 0,
    ) = PomodoroSession(state, previousState = null, preset = preset, workSession = workSession,
        secondsLeft = secondsLeft, extraSeconds = extraSeconds, owner = UUID.randomUUID(), startLocation = null)

    @Test fun `progress reflects remaining seconds`() {
        val s = session(secondsLeft = 750)
        assertEquals(0.5, s.progress(), 1e-9)
    }

    @Test fun `progress is full when work is completed`() {
        val s = session(state = SessionState.WORK_COMPLETED, secondsLeft = 0, extraSeconds = 12)
        assertEquals(1.0, s.progress(), 1e-9)
    }

    @Test fun `paused progress shows the underlying phase`() {
        val s = session(state = SessionState.PAUSED, secondsLeft = 750).copy(previousState = SessionState.WORK)
        assertEquals(0.5, s.progress(), 1e-9)
    }

    @Test fun `time label formats mm ss`() {
        assertEquals("25:00", session(secondsLeft = 1500).timeLabel())
        assertEquals("00:05", session(secondsLeft = 5).timeLabel())
        assertEquals("00:00", session(secondsLeft = 0).timeLabel())
    }

    @Test fun `work session cannot exceed preset sessions`() {
        assertThrows(IllegalArgumentException::class.java) { session(workSession = 5) }
    }

    @Test fun `extraSeconds is non-negative`() {
        assertThrows(IllegalArgumentException::class.java) { session(extraSeconds = -1) }
    }

    @Test fun `isActive is false when stopped`() {
        assertTrue(session().isActive)
        assertFalse(session(state = SessionState.STOPPED).isActive)
    }

    @Test fun `phase total picks the matching duration`() {
        val p = preset()
        assertEquals(p.workSeconds, session(state = SessionState.WORK).phaseTotalSeconds)
        assertEquals(p.breakSeconds, session(state = SessionState.BREAK).phaseTotalSeconds)
        assertEquals(p.longBreakSeconds, session(state = SessionState.LONG_BREAK).phaseTotalSeconds)
    }
}
