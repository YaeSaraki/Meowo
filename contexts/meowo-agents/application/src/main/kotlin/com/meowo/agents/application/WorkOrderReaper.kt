package com.meowo.agents.application

import com.meowo.agents.domain.WorkOrderLadder
import com.meowo.agents.domain.WorkOrderLevel
import com.meowo.agents.domain.WorkOrderState
import com.meowo.agents.infrastructure.PlayerProfileStore
import com.meowo.agents.infrastructure.StoredWorkOrder
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The periodic re-evaluation the plan is built around.
 *
 * ## Why a reaper and not a reaction
 *
 * A work order is **written** the moment the evidence appears and **acted on** by this, later,
 * when the conditions hold. That is what answers "what if the only person online is the one being
 * accused": nothing happens at three in the morning, and the order moves on its own at the next
 * pass when somebody is actually there to be deterred by it.
 *
 * ## What it can and cannot do
 *
 * It advances the ladder. It **cannot punish**: [WorkOrderLevel] has no rung that applies a
 * sanction, and [WorkOrderLadder.next] returns null there. `UPHELD` means "a person should look
 * at this", and that person is a human. There is no code path from an order to a punishment,
 * which is the property the whole design exists to hold.
 *
 * ## Decided before it is reported
 *
 * Every rung is decided here and delivered as a [Verdict]. Nothing runs on a tick thread, and
 * nothing here talks to a player — a reaper that both decides and speaks is a reaper that can
 * block the server on a database read.
 */
class WorkOrderReaper(
    private val store: PlayerProfileStore,
    private val onlinePlayers: () -> Int = { 0 },
    private val adminOnline: () -> Boolean = { false },
    private val corroborates: (String) -> Boolean = { false },
    private val clock: () -> Instant = Instant::now,
) : AutoCloseable {

    private val running = AtomicBoolean(false)

    /** What happened to one order on one pass. Delivered, not acted upon. */
    sealed interface Verdict {
        data class Advanced(val id: String, val to: WorkOrderLevel) : Verdict
        data class Held(val id: String, val at: WorkOrderLevel) : Verdict
        data class Expired(val id: String) : Verdict

        /** Terminal by design: the ladder ends here and a person takes it from here. */
        data class HandedToHuman(val id: String) : Verdict
    }

    /**
     * One pass. Returns what it decided so the caller can report it, and writes only the level
     * changes — a reaper with opinions about people would be the thing this design forbids.
     */
    fun run(): List<Verdict> {
        if (!running.compareAndSet(false, true)) return emptyList()
        return try {
            val at = clock()
            val millis = at.toEpochMilli()
            store.openWorkOrders(millis).mapNotNull { reap(it, at, millis) }
        } finally {
            running.set(false)
        }
    }

    private fun reap(order: StoredWorkOrder, at: Instant, millis: Long): Verdict? {
        // Expiry is checked before the ladder, so a stale order is closed rather than escalated.
        if (order.expiresAt <= millis) {
            store.setWorkOrderState(order.id, WorkOrderState.EXPIRED, millis)
            return Verdict.Expired(order.id)
        }
        val next = WorkOrderLadder.next(
            com.meowo.agents.domain.ReapInputs(
                order = com.meowo.agents.domain.WorkOrder(
                    subject = order.subject,
                    level = order.level,
                    state = order.state,
                    createdAt = Instant.ofEpochMilli(order.createdAt),
                    trustedNegativeAuthors = order.authors,
                ),
                onlinePlayers = onlinePlayers(),
                adminOnline = adminOnline(),
                // The third gate. A claim no fact corroborates may still be told to the subject and
                // an admin may still hear it, but it cannot reach a restriction on opinion alone.
                factsCorroborate = corroborates(order.subject),
                now = at,
                lastRestrictedAt = order.lastLevelAt?.let(Instant::ofEpochMilli),
                quiet = false,
            ),
        ) ?: return Verdict.Held(order.id, order.level)

        store.advanceWorkOrder(order.id, next, millis)
        return if (next == WorkOrderLevel.QUEUED) Verdict.HandedToHuman(order.id) else Verdict.Advanced(order.id, next)
    }

    /** Opens an order. Creation is not action: nothing is said to anybody by this. */
    fun open(subject: String, authors: Int, reason: String?): String {
        val at = clock()
        val millis = at.toEpochMilli()
        val id = "wo-$subject-$millis"
        store.createWorkOrder(
            StoredWorkOrder(
                id = id,
                subject = subject,
                level = WorkOrderLevel.RECORDED,
                state = WorkOrderState.OPEN,
                createdAt = millis,
                updatedAt = millis,
                expiresAt = com.meowo.agents.domain.ReportGuard.workOrderExpiresAt(at).toEpochMilli(),
                lastLevelAt = null,
                authors = authors,
                reason = reason,
            ),
        )
        return id
    }

    override fun close() {
        running.set(false)
    }
}
