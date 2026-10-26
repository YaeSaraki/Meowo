package com.meowo.agents.application

import com.meowo.agents.domain.WorkOrderLevel
import com.meowo.agents.infrastructure.PlayerProfileStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fills the chat gate's risk score, out of band.
 *
 * ## Why this is not on the chat path
 *
 * `ChatModerator.inspect` runs for **every message** with a sub-millisecond budget and an explicit
 * ban on blocking IO. A model call cannot honour that. So the discipline cat's judgement travels the
 * other way round: it is computed here, on a timer, and pushed in through `setRisk`, which the gate
 * reads with one map lookup (docs/minimax/15-chat §8).
 *
 * ## Recomputed, never accumulated
 *
 * The score is a **function of current state**, not a running total. That is the whole safety
 * property: a player who was reported and has since stopped being reported sees their score fall
 * back on the next pass, without anybody deciding to forgive them. A latching counter would make
 * every accusation permanent, which is exactly the failure mode a discipline system must not have.
 *
 * ## It only ever adds caution
 *
 * Nothing here blocks, mutes or punishes. The score is an input to a filter that can redact; the
 * most this can do is make a message be looked at twice.
 */
class RiskScorer(
    private val store: PlayerProfileStore,
    private val online: () -> Collection<UUID> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Players this scorer has pushed a score for, so a full recompute stays bounded.
     *
     * **Who is watched is decided by an accusation, not by presence.** A player nobody has ever
     * complained about has no score worth computing, and computing one for everybody would make
     * the pass unbounded. So the set is filled by [watch] at the moment a complaint is filed — which
     * is also what gives the discipline ladder something to work with: it opens work orders for
     * the watched, and an unwatched player can never reach the threshold.
     */
    val watched: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    private val pushed = ConcurrentHashMap<UUID, Int>()

    /**
     * Starts watching a player, because somebody complained about them.
     *
     * Called when a report is filed. Before this existed the method had no caller at all, so the
     * set stayed empty, the pass had nothing to iterate, and the 风纪 side of the plugin ran on
     * every tick and decided nothing.
     */
    fun watch(player: UUID) {
        watched.add(player)
    }

    /** Every player the loop knows about. Bounded by who has a profile, not by the table size. */

    fun forget(player: UUID) {
        watched.remove(player)
        pushed.remove(player)
    }

    /**
     * One pass over the watched players. Returns what it pushed, so a diagnostics command can
     * show it without a separate probe.
     */
    fun recompute(push: (UUID, Int) -> Unit): Map<UUID, Int> {
        val at = now()
        val result = mutableMapOf<UUID, Int>()
        for (player in watched) {
            val score = scoreOf(player, at)
            pushed[player] = score
            result[player] = score
            push(player, score)
        }
        return result
    }

    fun lastPushed(player: UUID): Int? = pushed[player]

    /**
     * The score, from scratch.
     *
     * [soft_hits] is the dominant term because it is the only one a person cannot shed by simply
     * being quiet for a moment — every accusation that survived the guard counted once. The open
     * work order's level adds a smaller, situational term that disappears when the order does.
     *
     * Both are capped so a pile of reports cannot push a player past the point where the gate
     * treats them differently in kind; past [CEILING] the response is "look harder", not "different
     * kind of person".
     *
     * ## The counter is refreshed, not trusted
     *
     * `soft_hits` is read back from the store after [PlayerProfileStore.recomputeSoftHits] has
     * brought it back in line with the evidence on file. That is what makes the score a function of
     * the current state rather than a running total: a complaint that has expired, or that the
     * subject asked to have forgotten, stops counting at the next pass — not because anybody
     * forgave them, but because it is no longer there.
     */
    private fun scoreOf(player: UUID, at: Long): Int {
        val id = player.toString()
        val hits = store.recomputeSoftHits(id, at)
        // Read once: the work orders used to be queried twice per player, and the two queries could
        // disagree — one before the prune, one after.
        val orders = store.openWorkOrders(at).filter { it.subject == id }
        if (hits <= 0 && orders.isEmpty()) return 0

        var score = (hits * SOFT_HIT_WEIGHT).toInt()
        val level = orders.maxByOrNull { it.level.ordinal }?.level
        score += when (level) {
            WorkOrderLevel.RECORDED -> 0
            WorkOrderLevel.NOTIFIED -> 5
            WorkOrderLevel.QUEUED -> 10
            WorkOrderLevel.RESTRICTED -> 20
            null -> 0
        }
        return score.coerceIn(0, CEILING)
    }

    companion object {
        /** Matches `chat.warn-at` (40): above this a message is redacted rather than shown. */
        const val SOFT_HIT_WEIGHT = 8

        /** Below `chat.block-at` (80) on purpose. This must never be able to block by itself. */
        const val CEILING = 79
    }
}
