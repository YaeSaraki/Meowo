package com.meowo.agents.application

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small priority scheduler for agent work.
 *
 * ## Two lanes, one direction of sacrifice
 *
 * [Lane.QUESTION] work is admitted whenever the pool's bounded queue has room, and waits there
 * while every worker is busy. [Lane.PROACTIVE] work is admitted **only when nothing is waiting**,
 * and is dropped rather than queued otherwise. That asymmetry is the whole design: a cat greeting
 * somebody costs money and a worker, while a player waiting on an answer is neither.
 *
 * ## The pool's queue *is* the backlog
 *
 * There is no second queue to keep in step with the first. The executor's queue is bounded by
 * [maxBacklog], so the bound is enforced by the thing that does the queueing rather than by a
 * counter somewhere else that can disagree with it. A submission that finds it full is rejected by
 * the pool itself.
 *
 * ## Threads
 *
 * Every thread here belongs to the agents module. The economy has its own single-threaded worker
 * and the server has its tick thread; neither is borrowed, because a blocking model call on
 * either would be a stall of somebody else's system (红线 03). Workers run a notch below normal
 * priority so a round trip never competes with the server for CPU.
 */
class AgentScheduler(
    private val workers: Int = 2,
    private val maxBacklog: Int = 32,
) : AutoCloseable {
    init {
        require(workers > 0) { "workers must be positive" }
        require(maxBacklog > 0) { "maxBacklog must be positive" }
    }

    private val pool = ThreadPoolExecutor(
        workers, workers,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(maxBacklog),
        ThreadFactory { r -> Thread(r, "meowo-agents").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /**
     * Cumulative counts, for the diagnostics report and for the tests that pin the policy.
     *
     * [admitted] counts work handed to the pool — which includes work the pool immediately queued
     * behind a busy worker. [suspended] is the subset of that which was *already waiting* when it
     * was handed over, so `suspended <= admitted` always. An earlier version incremented both
     * unconditionally, which double-counted every backlogged item and made the report claim more
     * calls than the server had ever accepted.
     */
    val admitted = AtomicInteger()
    val suspended = AtomicInteger()
    val droppedProactive = AtomicInteger()
    val droppedQuestion = AtomicInteger()

    fun backlogDepth(): Int = pool.queue.size

    fun capacity(): Int = workers

    /**
     * Offers work and reports what was done with it.
     *
     * The caller learns the outcome synchronously so it can refund budget and tell the player
     * something true. A scheduler that dropped work silently would leave the caller believing a
     * question had been accepted, and the player would wait for an answer that was never coming.
     */
    fun submit(lane: Lane, work: Runnable): Admitted {
        val decision = AgentQueue.admit(
            lane = lane,
            inFlight = pool.activeCount,
            capacity = workers,
            backlog = pool.queue.size,
            maxBacklog = maxBacklog,
            closed = pool.isShutdown,
        )
        if (decision is Admitted.Rejected) {
            if (lane == Lane.PROACTIVE) droppedProactive.incrementAndGet() else droppedQuestion.incrementAndGet()
            return decision
        }

        return try {
            pool.execute(work)
            admitted.incrementAndGet()
            if (decision == Admitted.Suspended) suspended.incrementAndGet()
            // The decision is reported as decided, not re-derived from the queue afterwards: the
            // only thing the caller acts on is whether a refund is owed, and a submission that was
            // admitted never is.
            decision
        } catch (rejected: RejectedExecutionException) {
            // The pool's own bound is the backstop, and it is the authority on whether there is room.
            if (lane == Lane.PROACTIVE) droppedProactive.incrementAndGet() else droppedQuestion.incrementAndGet()
            if (lane == Lane.PROACTIVE) Admitted.Rejected(Busy.SATURATED)
            else Admitted.Rejected(Busy.BACKLOG_FULL)
        }
    }

    override fun close() {
        pool.shutdownNow()
        pool.awaitTermination(1, TimeUnit.SECONDS)
    }
}
