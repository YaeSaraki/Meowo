package com.meowo.api

import java.util.UUID

/**
 * Whether a player is running a focus session, published so other contexts can show it.
 *
 * This exists for the nametag: a player mid-pomodoro should read as **studying** to everyone else,
 * which the nametag cannot know on its own. It is a service rather than a module dependency on
 * purpose — `meowo-nametag` must not compile against `meowo-pomodoro`, and either context may be
 * disabled or enabled in any order by `config.yml`.
 *
 * Resolved lazily through the ServicesManager, so a nametag started before the pomodoro context
 * still picks the service up on its next sweep.
 *
 * ### Immutability
 *
 * [FocusStatus] is an immutable snapshot with no reference to the live session. Anything handed to
 * another context crosses out of the pomodoro context's own thread model that way.
 */
interface FocusAPI {
    /** The player's live session, or null when they are not in one. */
    fun statusOf(owner: UUID): FocusStatus?

    /**
     * True while the player is in a focus session of any kind — including a paused one.
     *
     * A pause is still a session: the player chose to be here and has not left, which is exactly
     * what "this person is studying" means to somebody looking at their nametag. Only ending the
     * session clears it.
     */
    fun isStudying(owner: UUID): Boolean = statusOf(owner) != null
}

/**
 * The phase a focus session is in. Mirrors the pomodoro state machine without depending on it.
 *
 * [WORK_COMPLETED] is kept distinct from [WORK] because the two answer different questions: work is
 * a running clock, work-completed is a finished round waiting for the player to choose a break. A
 * consumer that wants a label shows one thing, and a consumer that wants a live clock checks
 * [FocusStatus.counting], which is false for the waiting phase.
 */
enum class FocusState { WORK, WORK_COMPLETED, BREAK, LONG_BREAK }

/**
 * A frozen reading of one focus session.
 *
 * @param secondsLeft time remaining in the current phase.
 * @param totalSeconds the length of the current phase, so a consumer can draw a proportion without
 *   a second call.
 */
data class FocusStatus(
    val state: FocusState,
    val secondsLeft: Int,
    val totalSeconds: Int,
    val presetName: String,
    val workSession: Int,
    val sessions: Int,
) {
    /**
     * True while the clock is actually moving.
     *
     * False for a paused session and for [FocusState.WORK_COMPLETED], which is a round that has
     * finished and is waiting for the player to start a break — the clock is deliberately held there
     * until they do.
     */
    val counting: Boolean
        get() = state == FocusState.WORK || state == FocusState.BREAK || state == FocusState.LONG_BREAK
}
