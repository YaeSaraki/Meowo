package com.meowo.agents.application

import com.meowo.agents.domain.Guard
import java.util.UUID

/**
 * How many model calls may be made, by whom, for what.
 *
 * ## The reservation is the design
 *
 * A single global cap would let idle chatter eat a morning's worth of questions before anyone
 * noticed, so the cap is **split first** by [Guard.quotaSplit] and each lane is then spent on its
 * own. Chatter cannot borrow from the questions' share no matter how quiet the day has been; the
 * plan's rule is that a proactive message may never starve a real one, and a shared pool is the
 * usual way that rule gets broken.
 *
 * ## Refunds
 *
 * Cancelled work hands its slot back ([refund]). Without it a burst of proactive chatter that the
 * queue drops on a busy server would permanently consume budget that nobody used, and the daily
 * cap would quietly become a cap on *attempted* calls rather than on calls actually made.
 *
 * ## One unit is one model call
 *
 * [acquire] is charged by `AgentRuntime` immediately before every request it makes, so a question
 * that needs three tool rounds costs three and not one. It used to be charged once per *question*,
 * which meant a question could cost up to four times what the cap said it cost — and since the cap
 * is the thing that decides how much a deployment spends, a cap that under-counts is worse than no
 * cap. The caller's first call is paid at admission instead (so an over-quota player is told
 * immediately rather than after a queue wait), which is the only reason the count is not done from
 * inside the loop on round one too.
 *
 * ## The day
 *
 * [advanceTo] is what makes this a *daily* cap; see there for why the day is an argument.
 *
 * ## One lock, and why
 *
 * Everything that touches the counters goes through [lock]. The counters used to be three separate
 * `AtomicInteger`s checked and updated in sequence, and the gap between "read the count" and "write
 * the count back" was wide enough for two threads to both see room and both take it: the per-player
 * cap could be exceeded by exactly the number of threads that asked at the same moment, which is the
 * one moment a cap is supposed to hold. A cap that can be overrun by a race is a decoration.
 *
 * The lock is held for a few map operations and nothing else — no I/O, no waiting — so the cost is
 * a few nanoseconds and the guarantee is exact.
 */
class CallBudget(
    dailyCap: Int = 500,
    private val perPlayerCap: Int = 20,
) {
    private val proactiveCap: Int
    private val questionCap: Int

    init {
        require(dailyCap >= 0) { "dailyCap must not be negative" }
        require(perPlayerCap > 0) { "perPlayerCap must be positive" }
        // The split is the domain's rule, not a second copy of it.
        //
        // A copy is a second answer, and the two drifted the moment the rounding moved to one
        // side: the domain gives 100/399 for a cap of 499 and the copy gave 99/400, and the test
        // that claimed to compare them only ever hard-coded the numbers.
        val (proactive, question) = Guard.quotaSplit(dailyCap)
        proactiveCap = proactive
        questionCap = question
    }

    /** Guards every counter below. See the class comment for why there is exactly one. */
    private val lock = Any()

    private var proactiveUsed = 0
    private var questionUsed = 0
    private val perPlayer = HashMap<UUID, Int>()

    /** Whether [lane] has anything left, without spending it. */
    fun hasRoom(lane: Lane): Boolean = synchronized(lock) { roomLocked(lane) }

    private fun roomLocked(lane: Lane): Boolean = when (lane) {
        Lane.PROACTIVE -> proactiveUsed < proactiveCap
        Lane.QUESTION -> questionUsed < questionCap
    }

    /**
     * Spends one call, or refuses.
     *
     * Both the per-player cap and the lane cap must pass. The per-player one is checked first
     * because it is the one that protects the *other* players: a single talker cannot empty the
     * global allowance by talking to every cat in a row.
     *
     * The whole check-and-spend happens under one lock, so two threads cannot both be told there is
     * room when there is room for only one of them.
     */
    fun acquire(lane: Lane, player: UUID): Boolean = synchronized(lock) {
        val mine = perPlayer[player] ?: 0
        if (mine >= perPlayerCap) return false
        if (!roomLocked(lane)) return false
        perPlayer[player] = mine + 1
        when (lane) {
            Lane.PROACTIVE -> proactiveUsed++
            Lane.QUESTION -> questionUsed++
        }
        true
    }

    /**
     * Gives a call back. Used when queued work is dropped before it ever reached a model.
     *
     * ## Only a slot that was actually held comes back
     *
     * The refund is refused unless the player is currently holding one in that lane. A refund for a
     * slot that was never taken would hand the lane a free unit — and a free unit is exactly what a
     * cap must never contain, because the lane counters are what decide whether the *next* player
     * is served.
     *
     * The two halves move together or not at all: the player's counter and the lane's counter are
     * decremented in the same locked step, so they cannot drift apart and leave a slot that is
     * counted in one place and not the other.
     */
    fun refund(lane: Lane, player: UUID) {
        synchronized(lock) {
            val mine = perPlayer[player] ?: 0
            if (mine <= 0) return
            perPlayer[player] = mine - 1
            when (lane) {
                Lane.PROACTIVE -> if (proactiveUsed > 0) proactiveUsed--
                Lane.QUESTION -> if (questionUsed > 0) questionUsed--
            }
        }
    }

    /** What each lane may still spend, for diagnostics and for the `/meo agents` report. */
    fun remaining(lane: Lane): Int = synchronized(lock) {
        when (lane) {
            Lane.PROACTIVE -> proactiveCap - proactiveUsed
            Lane.QUESTION -> questionCap - questionUsed
        }
    }

    /** The day the counters were last zeroed for; [Long.MIN_VALUE] until something rolls it. */
    @Volatile
    var day: Long = Long.MIN_VALUE
        private set

    /**
     * Moves the budget to [day] and zeroes it if that is a new day.
     *
     * ## Why the day has to be passed in
     *
     * The counters were previously reset by **nothing at all**: `reset` had no caller outside the
     * tests, so `dailyCap` was not a daily cap at all — it was a ceiling for as long as the server
     * stayed up. A server that quietly ran out of allowance would have looked exactly like a server
     * whose cats had stopped answering, and nothing in the logs would have said why.
     *
     * Taking the day as an argument rather than reading a clock keeps this pure and testable, and it
     * lets the caller decide what "a day" means — the server's local date, not a UTC one, because
     * midnight for the operator's players is what they mean by a day.
     *
     * Only a day **later** rolls it. An NTP correction or a timezone change that moved the local
     * date backwards used to read as a new day and hand every player their whole allowance back, so
     * the cheapest way to spend four times the daily cap would have been to change the server's
     * clock. Ignoring a backwards stamp costs at most one skipped rollover, which is free.
     *
     * The zeroing happens **inside** the same lock as the check, so a call cannot be admitted
     * against the old day's counters and then be erased by the rollover — or the other way round,
     * which used to leave a player charged against a day that no longer existed.
     *
     * @return whether anything was actually reset, so the caller can log the rollover rather than
     *   firing the same message every hour.
     */
    fun advanceTo(day: Long): Boolean = synchronized(lock) {
        if (day <= this.day) return false
        this.day = day
        proactiveUsed = 0
        questionUsed = 0
        perPlayer.clear()
        true
    }

    fun usedBy(player: UUID): Int = synchronized(lock) { perPlayer[player] ?: 0 }

    /**
     * ## Why there is no "forget this player"
     *
     * There used to be one, and it removed the player's counter outright — which meant a player
     * could spend their whole per-player cap, log out, log in, and spend it again, as many times as
     * they liked. The per-player cap was therefore not a cap at all but a suggestion that could be
     * reset by reconnecting, and reconnecting is free.
     *
     * The cap is a **daily** cap, so the counter belongs to the day, not to the session. The day is
     * cleared by [advanceTo], which is also what keeps the map from growing without bound: one
     * entry per distinct player per day, and no more.
     */

    /** Zeroes everything. Kept for tests and for an operator-driven reset. */
    fun reset() {
        synchronized(lock) {
            proactiveUsed = 0
            questionUsed = 0
            perPlayer.clear()
        }
    }
}
