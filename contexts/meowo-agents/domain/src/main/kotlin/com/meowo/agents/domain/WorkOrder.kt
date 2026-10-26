package com.meowo.agents.domain

import java.time.Duration
import java.time.Instant

/**
 * The five-rung ladder a review work order climbs, and the gates on each rung.
 *
 * ## Deterrence needs an audience
 *
 * The head-count gate exists because acting while the accused is the only person online has **no
 * social cost** — so it buys resentment without buying behaviour change. That is also the easiest
 * moment to stage. The gate therefore protects *whether acting now means anything*, not whether
 * the system is allowed to act.
 *
 * ## Nothing here punishes
 *
 * There is no rung that applies a penalty, and no function that returns one. `UPHELD` means "a human
 * should look at this", which is why the terminal states are the whole design: the ladder stops at
 * handing a person a problem.
 */
enum class WorkOrderLevel {
    /** Recorded only. Nobody is disturbed. */
    RECORDED,

    /** The subject was told, with reasons and how to appeal. */
    NOTIFIED,

    /** A moderator's queue. */
    QUEUED,

    /** A temporary, self-healing restriction. The last rung the system may apply itself. */
    RESTRICTED,
}

enum class WorkOrderState {
    OPEN,
    NOTIFIED,
    DISMISSED,
    UPHELD,
    EXPIRED,
    SELF_RESOLVED,

    /**
     * The subject used `/meo forget`.
     *
     * A separate state rather than a deleted row, because a work order is the moderation side's
     * record of what was alleged about somebody, not the subject's own memory — see the note on
     * `PlayerProfileStore.forgetAll`. The row stays so the history is not silently rewritten, and
     * leaves the ladder, because a ladder cannot climb towards someone who has said they do not
     * want to be here.
     */
    FORGOTTEN,
}

data class WorkOrder(
    val subject: String,
    val level: WorkOrderLevel,
    val state: WorkOrderState,
    val createdAt: Instant,
    val trustedNegativeAuthors: Int,
)

/** Inputs the reaper evaluates once per tick-cycle. */
data class ReapInputs(
    val order: WorkOrder,
    val onlinePlayers: Int,
    val adminOnline: Boolean,
    val factsCorroborate: Boolean,
    val now: Instant,
    val lastRestrictedAt: Instant?,
    val quiet: Boolean,
)

object WorkOrderLadder {

    /** People who must be online before a restriction has any social meaning. */
    const val MIN_AUDIENCE = 5

    /** A restriction must wait this long after the reports, so an angry reaction cannot trigger it. */
    val RESTRICTION_DELAY: Duration = Duration.ofHours(24)

    /** And it may not be applied more often than this to the same player. */
    val RESTRICTION_REPEAT: Duration = Duration.ofDays(7)

    /**
     * States a ladder may never climb out of.
     *
     * `OPEN` and `NOTIFIED` are absent on purpose — they are the two that escalate. `SELF_RESOLVED`
     * is here rather than being left out: it is a name that means "this finished", and a guard that
     * forgets it is a guard that works only as long as nobody uses it.
     *
     * **Nothing writes `SELF_RESOLVED` today.** It is in the set anyway. A guard built only from the
     * states currently in use breaks the first time someone introduces one, which is exactly the
     * moment nobody is looking at the guard.
     */
    private val TERMINAL = setOf(
        WorkOrderState.DISMISSED,
        WorkOrderState.UPHELD,
        WorkOrderState.EXPIRED,
        WorkOrderState.SELF_RESOLVED,
        WorkOrderState.FORGOTTEN,
    )

    /**
     * Next step, or null to stay put.
     *
     * Strictly upward: a work order never de-escalates by itself. The gates are checked in the
     * order they can block, and the cheap ones come first.
     */
    fun next(inputs: ReapInputs): WorkOrderLevel? {
        val o = inputs.order
        // Terminal states, listed so a closed case cannot be climbed. `SELF_RESOLVED` is in [TERMINAL]
        // — it used to be left out, and writing it would then have escalated straight out of a case
        // whose name says it finished. That is why `FORGOTTEN` got its own entry rather than reusing
        // it: the point of the guard is that adding a state forces you to say where it goes, and a
        // plausible-looking neighbour is worse than none.
        //
        // Nothing writes `SELF_RESOLVED` yet. It stays in the set anyway, deliberately: a guard that
        // only knows the states in use today is a guard that breaks the first time one is used.
        if (o.state in TERMINAL) return null

        return when (o.level) {
            WorkOrderLevel.RECORDED ->
                // Three independent, trusted accusations are worth a conversation, not a notice
                // thrown at someone for one bad minute.
                if (o.trustedNegativeAuthors >= 3) WorkOrderLevel.NOTIFIED else null

            WorkOrderLevel.NOTIFIED ->
                if (isAudible(inputs.onlinePlayers, inputs.adminOnline)) WorkOrderLevel.QUEUED else null

            WorkOrderLevel.QUEUED -> {
                // Three gates. Pure opinion fails the first one, and that is the point.
                if (!hasAudience(inputs.onlinePlayers, inputs.adminOnline)) null
                else if (!inputs.factsCorroborate) null
                else if (!elapsed(o.createdAt, inputs.now, RESTRICTION_DELAY)) null
                else if (!cooledDown(inputs.lastRestrictedAt, inputs.now)) null
                else WorkOrderLevel.RESTRICTED
            }

            WorkOrderLevel.RESTRICTED -> null
        }
    }

    /**
     * Whether anything can be said out loud.
     *
     * A quiet player is never addressed unprompted, even if their work order has climbed.
     */
    fun maySpeak(level: WorkOrderLevel, quiet: Boolean): Boolean = !quiet && level != WorkOrderLevel.RECORDED

    /** A restriction needs witnesses; a notice to a moderator does not. */
    fun hasAudience(online: Int, adminOnline: Boolean): Boolean = adminOnline || online >= MIN_AUDIENCE

    private fun isAudible(online: Int, adminOnline: Boolean): Boolean =
        hasAudience(online, adminOnline) || online >= 2

    private fun elapsed(since: Instant, now: Instant, need: Duration): Boolean =
        Duration.between(since, now) >= need

    private fun cooledDown(last: Instant?, now: Instant): Boolean =
        last == null || Duration.between(last, now) >= RESTRICTION_REPEAT

    /**
     * The message a restriction expires with.
     *
     * Returns null when nothing is due — the temporary limit heals on its own and leaves no
     * permanent mark, which is the only behaviour the evidence supports.
     */
    fun expiry(now: Instant, restrictUntil: Instant): Boolean = !now.isBefore(restrictUntil)
}