package com.meowo.agents.domain

import java.util.Random

/**
 * Every rate and loop rule the runtime must obey, as pure functions.
 *
 * Nothing here touches a clock, a network or a thread pool: the caller passes `now` and a seeded
 * [Random]. That is what lets the quota split, the cooldown window and the 5% roll be asserted
 * exactly rather than statistically — a sampling test would be flaky, and this codebase has
 * already been bitten by tests that silently skipped (08 §19).
 *
 * ## Profile visibility is **not** decided here
 *
 * This object used to carry `mayReadProfile`, a second copy of the rule that
 * [Redaction.verdict] enforces, and it was **dead** — nothing in the repository called it, tests
 * included. Worse, the two disagreed:
 *
 * - self: `Redaction` refuses when the subject opted out, the copy allowed it via a
 *   `subjectProfiling || true` that is true whatever the flag says;
 * - admin: `Redaction` grants `FULL`, the copy never returned `askerIsAdmin` at all;
 * - the copy's `else` branch was unreachable and its `CONSENT` branch was already forced true by
 *   an earlier check.
 *
 * **The live rule is [Redaction.verdict].** Keep it that way: a second copy of a privacy rule is
 * not redundancy, it is a second answer waiting for someone to wire it up.
 */
object Guard {

    /** How many cats deep a delegation may go. */
    const val MAX_DELEGATIONS = 2

    /** Share of the daily budget reserved for questions; proactive chatter may never eat it. */
    const val PROACTIVE_SHARE = 0.20

    /**
     * True when a proactive message may be sent.
     *
     * Order matters: the cheap, deterministic gates run before the roll so an idle check that
     * fails never consumes randomness and never touches the quota.
     */
    fun proactive(
        roll: Double,
        idle: Boolean,
        inCooldown: Boolean,
        proactiveQuotaLeft: Boolean,
        quiet: Boolean,
        enabled: Boolean,
        public: Boolean,
        othersOnline: Int,
    ): Boolean {
        if (!enabled) return false
        if (!idle) return false              // never compete with a real question
        if (quiet) return false              // the player opted out
        if (inCooldown) return false
        if (!proactiveQuotaLeft) return false // exhausted -> stay silent, never a fallback message
        if (public && othersOnline == 0) return false
        return roll < PROACTIVE_ROLL
    }

    /** 5% — the roll decides *whether*, a cooldown decides *how often*. */
    const val PROACTIVE_ROLL = 0.05

    /** Splits the daily budget. Rounding is on the question side so chatter can never round up. */
    fun quotaSplit(dailyCap: Int): Pair<Int, Int> {
        require(dailyCap >= 0) { "dailyCap must not be negative" }
        val proactive = (dailyCap * PROACTIVE_SHARE).toInt()
        return proactive to (dailyCap - proactive)
    }

    /**
     * Decides how far a delegation may proceed, in one step.
     *
     * Returns the cat to hand off to, or null when the chain is finished (hit the depth cap) or
     * loops back on a cat already visited. A loop must terminate here rather than spin.
     */
    fun nextDelegate(
        current: CatProfile,
        requested: String?,
        visited: Set<String>,
        random: Random,
    ): String? {
        if (visited.size > MAX_DELEGATIONS) return null
        val target = requested?.trim()?.lowercase()
        if (!target.isNullOrEmpty()) {
            val resolved = CatRegistry.resolve(target) ?: return null
            if (resolved.id == current.id) return null   // delegate to yourself is not a delegation
            if (resolved.id in visited) return null       // would loop
            return resolved.id
        }
        val options = current.delegatesTo.filter { it !in visited }
        return if (options.isEmpty()) null else options[random.nextInt(options.size)]
    }

    enum class Sharing { CONSENT, OPEN, OFF }

    /** Milliseconds left before this player may ask again, or 0. */
    fun cooldownLeft(lastAt: Long, now: Long, cooldownMillis: Long): Long {
        if (lastAt <= 0L) return 0L
        val left = lastAt + cooldownMillis - now
        return if (left > 0) left else 0L
    }
}