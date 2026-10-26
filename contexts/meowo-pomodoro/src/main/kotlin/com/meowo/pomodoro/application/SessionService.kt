package com.meowo.pomodoro.application

import com.meowo.pomodoro.domain.LocationSnapshot
import com.meowo.pomodoro.domain.PomodoroSession
import com.meowo.pomodoro.domain.Preset
import com.meowo.pomodoro.domain.SessionState
import com.meowo.pomodoro.domain.StateHandlers
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the live [PomodoroSession] for every player. Main-thread confined — Bukkit reads and the
 * visual side effects happen in the context layer, never on a worker.
 *
 * The service is the **only** place that mutates the state machine. State handlers are
 * pure-function strategies; the service drives them so `onExit`/`onEnter` fire in the right order
 * and every caller receives the [PomodoroEvent]s it should act on.
 *
 * ### Why every mutator returns a list
 *
 * A single transition can legitimately produce more than one thing to act on: leaving
 * `WORK_COMPLETED` both changes state *and* settles a completed focus round. Returning only one
 * event would force callers to re-derive the second, and the earlier single-event contract is
 * exactly how a focus round ended up being logged repeatedly from the tick path and credited to
 * the wrong round number. One call, one complete list, no re-derivation.
 */
class SessionService {

    private val sessions = ConcurrentHashMap<UUID, PomodoroSession>()

    fun all(): Collection<PomodoroSession> = sessions.values.toList()

    fun get(owner: UUID): PomodoroSession? = sessions[owner]

    fun isActive(owner: UUID): Boolean = sessions[owner]?.isActive == true

    /**
     * Drops every session without running transitions. Used when the context is disabled: a
     * re-enable must not inherit sessions whose state handlers belong to a closed context.
     */
    fun clear() = sessions.clear()

    /**
     * Starts a fresh session for [owner]. Any existing session is closed first, and a session that
     * was waiting in `WORK_COMPLETED` still settles its earned focus before being replaced.
     */
    fun start(owner: UUID, preset: Preset, startLocation: LocationSnapshot?): List<PomodoroEvent> {
        val events = mutableListOf<PomodoroEvent>()
        sessions.remove(owner)?.let { events += exitEvents(it) }
        val session = PomodoroSession(
            state = SessionState.WORK,
            previousState = null,
            preset = preset,
            workSession = 1,
            secondsLeft = preset.workSeconds,
            extraSeconds = 0,
            owner = owner,
            startLocation = startLocation,
        )
        sessions[owner] = session
        events += PomodoroEvent.Started(session)
        return events
    }

    /** Pauses a WORK / BREAK / LONG_BREAK session; empty when the state cannot be paused. */
    fun pause(owner: UUID): List<PomodoroEvent> {
        val current = sessions[owner] ?: return emptyList()
        if (current.state !in PAUSEABLE) return emptyList()
        return transition(owner, SessionState.PAUSED)
    }

    /** Resumes a paused session into whatever state was active before the pause. */
    fun resume(owner: UUID): List<PomodoroEvent> {
        val current = sessions[owner] ?: return emptyList()
        if (current.state != SessionState.PAUSED) return emptyList()
        return transition(owner, current.previousState ?: SessionState.WORK, isResuming = true)
    }

    /** Stops a session. Idempotent: a stopped session reports nothing. */
    fun stop(owner: UUID): List<PomodoroEvent> {
        val current = sessions[owner] ?: return emptyList()
        if (current.state == SessionState.STOPPED) return emptyList()
        return transition(owner, SessionState.STOPPED)
    }

    /**
     * From `WORK_COMPLETED`: advance to the next break, choosing a long break when the finished
     * round was the last one in the preset. Empty for every other state.
     */
    fun next(owner: UUID): List<PomodoroEvent> {
        val current = sessions[owner] ?: return emptyList()
        if (current.state != SessionState.WORK_COMPLETED) return emptyList()
        val target = if (current.workSession >= current.preset.sessions) SessionState.LONG_BREAK else SessionState.BREAK
        // Settle the completed round against the *outgoing* snapshot, before the counter moves, so
        // the focus log names the round that was actually finished. Advancing first credited the
        // next round's number to the previous round's time.
        val events = exitEvents(current).toMutableList()
        val advanced = if (target == SessionState.BREAK) current.copy(workSession = current.workSession + 1) else current
        val next = StateHandlers[target].onEnter(
            advanced.copy(state = target, previousState = current.state),
            current.state,
        )
        sessions[owner] = next
        events += PomodoroEvent.StateChanged(current, next, isResuming = false)
        return events
    }

    /**
     * One tick across every session that is still counting. Returns one [TickResult] per player so
     * the caller can act on the events, refresh visuals and settle any focus round.
     */
    fun tick(): List<TickResult> = sessions.mapNotNull { (owner, session) ->
        if (session.state == SessionState.STOPPED) return@mapNotNull null
        val ticked = StateHandlers[session.state].tick(session)
        if (ticked.state != session.state) {
            // A tick drove the transition (WORK ran out → WORK_COMPLETED). Exit the old state,
            // enter the new one, and report both, so a completed focus round is still settled
            // exactly once when the transition happens here rather than through `next`.
            val events = exitEvents(session).toMutableList()
            val entered = StateHandlers[ticked.state].onEnter(ticked, session.state)
            sessions[owner] = entered
            events += PomodoroEvent.StateChanged(session, entered, isResuming = false)
            TickResult(owner, entered, events, crossedBoundary = true)
        } else {
            sessions[owner] = ticked
            TickResult(owner, ticked, emptyList(), crossedBoundary = false)
        }
    }

    private fun transition(owner: UUID, target: SessionState, isResuming: Boolean = false): List<PomodoroEvent> {
        val current = sessions[owner] ?: return emptyList()
        val events = exitEvents(current).toMutableList()
        val next = StateHandlers[target].onEnter(
            current.copy(
                state = target,
                previousState = if (isResuming) current.previousState else current.state,
            ),
            current.state,
        )
        sessions[owner] = next
        events += if (target == SessionState.PAUSED) PomodoroEvent.Paused(next)
        else PomodoroEvent.StateChanged(current, next, isResuming)
        return events
    }

    /**
     * Runs the current state's `onExit` and reports the settled focus round, if any.
     *
     * `FocusEarned` fires **only** when leaving `WORK_COMPLETED`, which is the single moment a
     * round's real duration is known (the preset duration plus however long the player lingered).
     * Firing it here rather than from the tick loop is what keeps the credit from being written
     * once per second, and keeps it attached to the round that actually earned it.
     */
    private fun exitEvents(session: PomodoroSession): List<PomodoroEvent> {
        StateHandlers[session.state].onExit(session)
        return if (session.state == SessionState.WORK_COMPLETED) listOf(PomodoroEvent.FocusEarned(session))
        else emptyList()
    }

    private companion object {
        val PAUSEABLE = setOf(SessionState.WORK, SessionState.BREAK, SessionState.LONG_BREAK)
    }
}

/**
 * Something the context layer should act on after a state change. Events carry immutable session
 * snapshots only — no Bukkit objects cross this boundary.
 */
sealed interface PomodoroEvent {
    val session: PomodoroSession

    /** A fresh session was created. */
    data class Started(override val session: PomodoroSession) : PomodoroEvent

    /** The session entered [SessionState.PAUSED]. */
    data class Paused(override val session: PomodoroSession) : PomodoroEvent

    /** Any other transition. [before] is the outgoing snapshot, [session] the incoming one. */
    data class StateChanged(
        val before: PomodoroSession,
        override val session: PomodoroSession,
        val isResuming: Boolean,
    ) : PomodoroEvent

    /**
     * A WORK round finished and its real duration is known. [session] is the outgoing
     * `WORK_COMPLETED` snapshot, so the credit is attached to the round that earned it.
     */
    data class FocusEarned(override val session: PomodoroSession) : PomodoroEvent {
        val owner: UUID get() = session.owner
        val durationSeconds: Int get() = session.preset.workSeconds + session.extraSeconds
    }
}

/** One player's tick: the resulting session plus anything the caller should act on. */
data class TickResult(
    val owner: UUID,
    val session: PomodoroSession,
    val events: List<PomodoroEvent>,
    val crossedBoundary: Boolean,
)
