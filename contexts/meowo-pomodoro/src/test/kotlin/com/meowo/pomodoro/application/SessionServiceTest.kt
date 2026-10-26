package com.meowo.pomodoro.application

import com.meowo.pomodoro.domain.Preset
import com.meowo.pomodoro.domain.SessionState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SessionServiceTest {
    private val owner: UUID = UUID.randomUUID()

    /** Two rounds, durations above the 60-second floor the domain enforces. */
    private val preset = Preset("standard", "Standard", "CLOCK", false,
        workSeconds = 60, breakSeconds = 60, longBreakSeconds = 120, sessions = 2, builtin = true)

    private fun service() = SessionService()

    /**
     * Ticks until the phase that is currently counting runs out, with a safety bound. Used instead
     * of a fixed tick count so the test does not encode the durations a second time.
     */
    private fun SessionService.drainPhase(owner: UUID): List<TickResult> {
        val initial = get(owner)?.state ?: return emptyList()
        val results = mutableListOf<TickResult>()
        var guard = 10_000
        while (guard-- > 0 && get(owner)?.state == initial) results += tick()
        check(guard > 0) { "session never left $initial" }
        return results
    }

    private fun List<PomodoroEvent>.focus(): List<PomodoroEvent.FocusEarned> =
        filterIsInstance<PomodoroEvent.FocusEarned>()

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────

    @Test fun `start creates a fresh work session and reports it`() {
        val service = service()
        val events = service.start(owner, preset, null)
        assertEquals(1, events.size)
        assertTrue(events.single() is PomodoroEvent.Started)
        assertEquals(SessionState.WORK, service.get(owner)?.state)
        assertEquals(preset.workSeconds, service.get(owner)?.secondsLeft)
        assertEquals(1, service.get(owner)?.workSession)
    }

    @Test fun `start replaces an existing session, keeping only one live session`() {
        val service = service()
        service.start(owner, preset, null)
        service.start(owner, preset, null)
        assertEquals(1, service.all().size)
        assertEquals(SessionState.WORK, service.get(owner)?.state)
    }

    @Test fun `pause records the previous state and freezes the clock`() {
        val service = service()
        service.start(owner, preset, null)
        service.tick()
        val before = service.get(owner)!!.secondsLeft
        val events = service.pause(owner)
        assertTrue(events.any { it is PomodoroEvent.Paused })
        assertEquals(SessionState.PAUSED, service.get(owner)?.state)
        assertEquals(SessionState.WORK, service.get(owner)?.previousState)
        service.tick()
        assertEquals(before, service.get(owner)?.secondsLeft)
    }

    @Test fun `pause refuses states that are not a running phase`() {
        val service = service()
        service.start(owner, preset, null)
        service.drainPhase(owner)
        assertEquals(SessionState.WORK_COMPLETED, service.get(owner)?.state)
        assertTrue(service.pause(owner).isEmpty())
        assertEquals(SessionState.WORK_COMPLETED, service.get(owner)?.state)
    }

    @Test fun `resume returns to the previous state and keeps the remaining seconds`() {
        val service = service()
        service.start(owner, preset, null)
        repeat(3) { service.tick() }
        val remaining = service.get(owner)!!.secondsLeft
        service.pause(owner)
        val events = service.resume(owner)
        val changed = events.filterIsInstance<PomodoroEvent.StateChanged>().single()
        assertTrue(changed.isResuming)
        assertEquals(SessionState.WORK, service.get(owner)?.state)
        assertEquals(remaining, service.get(owner)?.secondsLeft)
    }

    @Test fun `resume is refused when nothing is paused`() {
        val service = service()
        service.start(owner, preset, null)
        assertTrue(service.resume(owner).isEmpty())
    }

    @Test fun `stop is idempotent`() {
        val service = service()
        service.start(owner, preset, null)
        assertTrue(service.stop(owner).isNotEmpty())
        assertTrue(service.stop(owner).isEmpty())
        assertEquals(SessionState.STOPPED, service.get(owner)?.state)
    }

    @Test fun `tick does not report stopped sessions`() {
        val service = service()
        service.start(owner, preset, null)
        service.stop(owner)
        assertEquals(0, service.tick().size)
        assertEquals(SessionState.STOPPED, service.get(owner)?.state)
    }

    @Test fun `clear drops every session`() {
        val service = service()
        service.start(owner, preset, null)
        service.clear()
        assertNull(service.get(owner))
        assertEquals(0, service.tick().size)
    }

    // ── the phase cycle ──────────────────────────────────────────────────────────────────────

    @Test fun `tick drives work to work-completed without crediting a focus round yet`() {
        val service = service()
        service.start(owner, preset, null)
        val results = service.drainPhase(owner)
        assertEquals(SessionState.WORK_COMPLETED, service.get(owner)?.state)
        assertTrue(results.any { it.crossedBoundary })
        // The round is not finished until the player leaves the waiting state, so nothing is
        // credited on the transition itself.
        assertTrue(results.flatMap { it.events }.focus().isEmpty())
    }

    @Test fun `work-completed ticks accumulate the real extra focus seconds`() {
        val service = service()
        service.start(owner, preset, null)
        service.drainPhase(owner)
        repeat(7) { service.tick() }
        assertEquals(7, service.get(owner)?.extraSeconds)
    }

    @Test fun `next advances work-completed to a break and increments the round`() {
        val service = service()
        service.start(owner, preset, null)
        service.drainPhase(owner)
        val events = service.next(owner)
        assertTrue(events.any { it is PomodoroEvent.StateChanged })
        assertEquals(SessionState.BREAK, service.get(owner)?.state)
        assertEquals(2, service.get(owner)?.workSession)
    }

    @Test fun `next from the last round goes to a long break and resets the counter`() {
        val service = service()
        service.start(owner, preset, null)
        service.drainPhase(owner)          // round 1 finished
        service.next(owner)                // -> BREAK, round 2
        assertEquals(2, service.get(owner)?.workSession)
        service.drainPhase(owner)          // break ends, back to WORK round 2
        assertEquals(SessionState.WORK, service.get(owner)?.state)
        service.drainPhase(owner)          // round 2 finished
        service.next(owner)                // -> LONG_BREAK, counter reset by the handler
        assertEquals(SessionState.LONG_BREAK, service.get(owner)?.state)
        assertEquals(1, service.get(owner)?.workSession)
    }

    @Test fun `next outside work-completed is refused`() {
        val service = service()
        service.start(owner, preset, null)
        assertTrue(service.next(owner).isEmpty())
        assertEquals(SessionState.WORK, service.get(owner)?.state)
    }

    @Test fun `break completion starts the next work round`() {
        val service = service()
        service.start(owner, preset, null)
        service.drainPhase(owner)
        service.next(owner)
        service.drainPhase(owner)
        assertEquals(SessionState.WORK, service.get(owner)?.state)
        assertEquals(preset.workSeconds, service.get(owner)?.secondsLeft)
    }

    // ── focus credit ─────────────────────────────────────────────────────────────────────────

    @Test fun `leaving work-completed credits the round exactly once with its real duration`() {
        val service = service()
        service.start(owner, preset, null)
        service.drainPhase(owner)
        repeat(5) { service.tick() }   // 5 seconds of extra focus in the waiting state

        val credited = service.next(owner).focus().single()
        assertEquals(owner, credited.owner)
        assertEquals(preset.workSeconds + 5, credited.durationSeconds)
        // The round that finished is round 1, even though the session has moved on to round 2.
        assertEquals(1, credited.session.workSession)

        // Draining the break and the next round must not credit round 1 again.
        assertTrue(service.drainPhase(owner).flatMap { it.events }.focus().isEmpty())
    }

    @Test fun `stopping from work-completed still credits the finished round`() {
        val service = service()
        service.start(owner, preset, null)
        service.drainPhase(owner)
        repeat(2) { service.tick() }
        val credited = service.stop(owner).focus().single()
        assertEquals(preset.workSeconds + 2, credited.durationSeconds)
        assertEquals(1, credited.session.workSession)
    }

    @Test fun `starting a new session credits a round left waiting`() {
        val service = service()
        service.start(owner, preset, null)
        service.drainPhase(owner)
        repeat(3) { service.tick() }
        val credited = service.start(owner, preset, null).focus().single()
        assertEquals(preset.workSeconds + 3, credited.durationSeconds)
    }

    @Test fun `a whole round is credited exactly once across every tick`() {
        val service = service()
        service.start(owner, preset, null)
        val all = mutableListOf<PomodoroEvent>()
        all += service.drainPhase(owner).flatMap { it.events }
        repeat(4) { all += service.tick().flatMap { result -> result.events } }
        all += service.next(owner)
        assertEquals(1, all.focus().size, "a focus round must be credited once, not once per tick")
    }
}
