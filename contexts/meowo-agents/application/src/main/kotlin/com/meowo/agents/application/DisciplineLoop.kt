package com.meowo.agents.application

import com.meowo.agents.domain.Sentiment
import com.meowo.agents.infrastructure.PlayerProfileStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One timer for the four things that happen away from the chat path.
 *
 * ## Why they share a pass
 *
 * Pruning, risk and the ladder all read the same rows, and running them separately would let them
 * disagree: a report pruned at 12:00 and a risk recomputed at 12:01 would see different evidence.
 * A single ordered pass means risk is always computed from a table that has already been pruned,
 * and the ladder always sees the reports the risk did.
 *
 * Order matters and is the reason this is an object rather than four callables:
 *
 * 1. **prune** — expired evidence goes, so nothing downstream counts it;
 * 2. **impressions** — also expired, but nothing downstream reads them, so this one only shares the
 *    pass because it shares the clock and the log;
 * 3. **risk** — recomputed from what survived, and pushed to the gate in O(1) reads;
 * 4. **reap** — the ladder advances, and only then can a new level change the risk next pass.
 *
 * Nothing here talks to a player. Each pass returns what it decided and the caller reports it,
 * because a discipline loop that both decides and speaks is a loop that can block a server.
 */
class DisciplineLoop(
    private val store: PlayerProfileStore,
    private val risk: RiskScorer,
    private val reaper: WorkOrderReaper,
    private val reports: ReportService? = null,
    private val pushRisk: (UUID, Int) -> Unit = { _, _ -> },
    /**
     * Days without a login before a cat forgets what it thought of a player.
     *
     * The same window the chat log keeps words for, on purpose: an impression is a compression of
     * those words, and a compressed copy outliving its source is how a retention promise stops being
     * one. See [PlayerProfileStore.pruneImpressions].
     */
    private val impressionDays: Int = 15,
    /**
     * When a player last logged in, or null for unknown.
     *
     * Supplied by the context because the chat log owns that history and no context may read
     * another's file. Default null means "unknown", so with nothing wired in nothing is deleted —
     * the safe direction, and the reason the default is not a clock of its own.
     */
    private val lastSeen: (String) -> Long? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val onOrders: (List<WorkOrderReaper.Verdict>) -> Unit = { },
) {
    private val running = AtomicBoolean(false)

    data class Pass(
        val pruned: Int,
        val impressionsPruned: Int,
        val risk: Map<UUID, Int>,
        val orders: List<WorkOrderReaper.Verdict>,
        val opened: List<String>,
    )

    fun run(): Pass? {
        if (!running.compareAndSet(false, true)) return null
        return try {
            val at = now()
            val pruned = store.prune(at)
            val impressions = pruneImpressions(at)
            val scores = risk.recompute(pushRisk)
            val opened = openOrders()
            val orders = reaper.run()
            onOrders(orders)
            Pass(pruned = pruned, impressionsPruned = impressions, risk = scores, orders = orders, opened = opened)
        } finally {
            running.set(false)
        }
    }

    private fun pruneImpressions(at: Long): Int {
        if (impressionDays <= 0) return 0
        val cutoff = at - java.util.concurrent.TimeUnit.DAYS.toMillis(impressionDays.toLong())
        return runCatching { store.pruneImpressions(cutoff, lastSeen) }.getOrDefault(0)
    }

    /**
     * Opens an order for anybody who has crossed the review threshold, once.
     *
     * "Once" is enforced by looking for an existing open order: without it every pass after the
     * fifth accusation would add another, and the ladder would be re-entered from the top each time
     * rather than continuing.
     */
    private fun openOrders(): List<String> {
        val service = reports ?: return emptyList()
        val at = now()
        val already = store.openWorkOrders(at).map { it.subject }.toSet()
        val opened = mutableListOf<String>()
        for (player in risk.watched) {
            val id = player.toString()
            if (id in already) continue
            if (!service.worthAnOrder(player)) continue
            opened += reaper.open(id, service.independentAccusations(player), reason = null)
        }
        return opened
    }

    /**
     * Convenience for the command layer: a player files a report and is told what happened.
     *
     * ## The watch list is filled here, and this is why
     *
     * An accusation is the only thing that puts a player on [RiskScorer]'s watch list. The risk
     * score is computed over the watched, the work orders are opened for the watched, and the score
     * is only pushed into the chat gate for the watched — so a player nobody has ever complained
     * about is never scored, and a player somebody *has* complained about is scored from the next
     * pass onwards.
     *
     * That connection did not exist: `watch` had no caller anywhere in the plugin, so the set was
     * empty, the pass iterated nothing, and the discipline side ran on every tick and decided
     * nothing at all. It looked alive because every part of it executed.
     */
    fun report(
        author: UUID,
        subject: UUID,
        sentiment: Sentiment,
        reason: String,
    ): ReportService.Outcome? {
        val outcome = reports?.submit(author, subject, sentiment, reason) ?: return null
        // A refused complaint is not evidence of anything, so it does not put anybody on the list.
        if (outcome is ReportService.Outcome.Stored) risk.watch(subject)
        return outcome
    }
}
