package com.meowo.agents.application

/**
 * Which kind of work an agent call is, and therefore what may be sacrificed for it.
 *
 * The ordering is the whole point. A player typed `/msg EconCat 我还有多少钱` and is standing there
 * waiting; a cat decided on its own to say something. The first is never cheaper than the second,
 * so everything in this file is arranged so that [PROACTIVE] is always the one that gives way.
 */
enum class Lane {
    /** The player asked. Suspended rather than dropped, and never refused for being late. */
    QUESTION,

    /** The cat started it. Nobody is waiting on a specific answer, so this is what gets dropped. */
    PROACTIVE,
}

/** Why a submission did not run. */
enum class Busy {
    /** The lanes that matter are full and this work has no backlog to wait in. */
    SATURATED,

    /** A question that could have waited, and did not have anywhere to wait. */
    BACKLOG_FULL,

    /** The call budget is spent. Distinct from saturation: more capacity would not help. */
    BUDGET,

    /** The scheduler is shutting down. */
    CLOSED,
}

/** What the scheduler decided to do with a submission. */
sealed interface Admitted {
    /** Handed to a worker now. */
    data object Running : Admitted

    /** Held in the backlog and run when a worker frees up. Only ever a [Lane.QUESTION]. */
    data object Suspended : Admitted

    data class Rejected(val reason: Busy) : Admitted
}

/**
 * Admission control, as a pure function of the current state.
 *
 * Separating the decision from the threading is what makes the policy testable at all: "a proactive
 * message is dropped when the server is busy" is a claim about behaviour, and a test for it that
 * needs real threads and a sleep is a test that will be flaky and will eventually be skipped
 * (docs/minimax/08 §19). Here it is arithmetic.
 */
object AgentQueue {

    /**
     * Decides whether a submission may run now, wait, or must be dropped.
     *
     * @param inFlight workers currently busy.
     * @param capacity workers in total.
     * @param backlog questions already waiting.
     * @param maxBacklog how many questions may wait.
     */
    fun admit(
        lane: Lane,
        inFlight: Int,
        capacity: Int,
        backlog: Int,
        maxBacklog: Int,
        closed: Boolean = false,
    ): Admitted {
        if (closed) return Admitted.Rejected(Busy.CLOSED)

        val hasRoom = inFlight < capacity
        return when (lane) {
            Lane.PROACTIVE -> when {
                // Questions are already waiting. A proactive message that jumped the queue would be
                // exactly the starvation the reserved quota exists to prevent.
                backlog > 0 -> Admitted.Rejected(Busy.SATURATED)
                hasRoom -> Admitted.Running
                else -> Admitted.Rejected(Busy.SATURATED)
            }
            Lane.QUESTION -> when {
                hasRoom -> Admitted.Running
                backlog < maxBacklog -> Admitted.Suspended
                // The backlog is the last line of defence. A player who is told the agent is busy
                // learns something true; a player whose question is dropped silently learns
                // nothing and waits.
                else -> Admitted.Rejected(Busy.BACKLOG_FULL)
            }
        }
    }
}
