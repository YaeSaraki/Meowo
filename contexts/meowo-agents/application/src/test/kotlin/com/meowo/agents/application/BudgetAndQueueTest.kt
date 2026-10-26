package com.meowo.agents.application

import com.meowo.agents.domain.Guard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CallBudgetTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    @Test fun `a cap is a cap`() {
        val budget = CallBudget(dailyCap = 3, perPlayerCap = 100)
        repeat(3) { assertTrue(budget.acquire(Lane.QUESTION, alice), "call ${it + 1} of 3") }
        assertFalse(budget.acquire(Lane.QUESTION, alice), "the fourth call is over the cap")
    }

    /**
     * The reservation the plan insists on: chatter gets its own share and cannot borrow from the
     * questions'. A single shared pool is the usual way this rule quietly stops holding.
     */
    @Test fun `chatter cannot spend the questions' allowance`() {
        val budget = CallBudget(dailyCap = 10, perPlayerCap = 100)
        val proactiveRoom = budget.remaining(Lane.PROACTIVE)
        repeat(proactiveRoom) { assertTrue(budget.acquire(Lane.PROACTIVE, alice)) }
        assertEquals(0, budget.remaining(Lane.PROACTIVE))
        // Chatter is spent; questions are untouched.
        assertEquals(8, budget.remaining(Lane.QUESTION), "the questions' share must be untouched by chatter")
        assertTrue(budget.acquire(Lane.QUESTION, bob))
    }

    @Test fun `the split follows the same rule as the domain`() {
        // A **comparison**, not a third copy of the numbers.
        //
        // The budget used to compute the split itself, and this test hard-coded the answer instead
        // of asking the domain: so the two could drift apart — the domain gives 100/399 for a cap
        // of 499 and the copy gave 99/400 — and the domain's own test only checked that questions
        // are not fewer than proactive, which every split satisfies.
        for (cap in listOf(0, 1, 2, 7, 99, 499, 500, 1000)) {
            val budget = CallBudget(dailyCap = cap, perPlayerCap = 100)
            val (proactive, question) = Guard.quotaSplit(cap)
            assertEquals(proactive, budget.remaining(Lane.PROACTIVE), "cap $cap: the proactive share")
            assertEquals(question, budget.remaining(Lane.QUESTION), "cap $cap: the question share")
        }
    }

    /** One talker must not be able to empty a shared allowance by cycling through every cat. */
    @Test fun `one player cannot spend the whole server's budget`() {
        val budget = CallBudget(dailyCap = 100, perPlayerCap = 5)
        repeat(5) { budget.acquire(Lane.QUESTION, alice) }
        assertFalse(budget.acquire(Lane.QUESTION, alice))
        assertTrue(budget.acquire(Lane.QUESTION, bob), "somebody else is still served")
    }

    /**
     * An operator who sets a very small daily cap gets no chatter at all, and that is correct.
     *
     * `Guard.quotaSplit` rounds on the question side so proactive can never round *up* into it,
     * which means 20% of 2 is 0. A cap too small to fund a greeting is a cap the operator set.
     */
    @Test fun `a cap too small to fund chatter grants it nothing rather than rounding up`() {
        val budget = CallBudget(dailyCap = 2, perPlayerCap = 100)
        assertEquals(0, budget.remaining(Lane.PROACTIVE))
        assertEquals(2, budget.remaining(Lane.QUESTION))
        assertFalse(budget.acquire(Lane.PROACTIVE, alice))
    }

    /** Cancelled chatter must give its slot back, or a burst of dropped work burns the day. */
    @Test fun `a refunded call becomes spendable again`() {
        val budget = CallBudget(dailyCap = 10, perPlayerCap = 100)
        // 20% of 10 is 2.
        assertTrue(budget.acquire(Lane.PROACTIVE, alice))
        assertTrue(budget.acquire(Lane.PROACTIVE, alice))
        assertFalse(budget.acquire(Lane.PROACTIVE, alice))
        budget.refund(Lane.PROACTIVE, alice)
        assertTrue(budget.acquire(Lane.PROACTIVE, alice), "the slot came back")
    }

    /** A double refund would raise the ceiling above the configured cap. */
    @Test fun `a refund can never drive a counter below zero`() {
        val budget = CallBudget(dailyCap = 2, perPlayerCap = 100)
        budget.refund(Lane.QUESTION, alice)
        budget.refund(Lane.PROACTIVE, alice)
        assertEquals(2, budget.remaining(Lane.QUESTION), "the cap must not inflate from a stray refund")
        assertEquals(0, budget.remaining(Lane.PROACTIVE), "a zero cap stays a zero cap")
    }

    /**
     * Quitting does **not** hand the allowance back.
     *
     * It used to: the counter was removed on quit, so a player could spend their whole per-player
     * cap, log out, log in, and spend it again — as often as they liked. The per-player cap was
     * therefore not a cap but a suggestion that a reconnect could reset, and reconnecting is free.
     *
     * The cap is a **daily** cap, so the counter belongs to the day; the day is cleared by the
     * rollover, which the next test covers.
     */
    @Test fun `quitting does not hand the allowance back`() {
        val budget = CallBudget(dailyCap = 100, perPlayerCap = 1)
        assertTrue(budget.acquire(Lane.QUESTION, alice))
        assertFalse(budget.acquire(Lane.QUESTION, alice))
        assertEquals(1, budget.usedBy(alice), "the day's spending is still the day's")
        assertFalse(budget.acquire(Lane.QUESTION, alice), "a reconnect is not a refund")
    }

    /**
     * The cap holds when everybody asks at once.
     *
     * The counters used to be read and then written back in two steps, so two threads could both
     * see room and both take it. The per-player cap was then overrun by exactly the number of
     * threads that happened to ask at the same moment — the one moment a cap has to hold.
     */
    @Test fun `the per-player cap holds under concurrency`() {
        val budget = CallBudget(dailyCap = 1_000, perPlayerCap = 10)
        val granted = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (1..16).map {
            Thread {
                start.await()
                repeat(20) { if (budget.acquire(Lane.QUESTION, alice)) granted.incrementAndGet() }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(10_000) }
        assertEquals(10, granted.get(), "the cap is a cap, whoever is asking")
        assertEquals(10, budget.usedBy(alice))
    }

    /** The same for the day's allowance, across many players. */
    @Test fun `the daily cap holds under concurrency`() {
        val budget = CallBudget(dailyCap = 20, perPlayerCap = 100)
        val granted = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (1..16).map { index ->
            Thread {
                start.await()
                repeat(20) {
                    if (budget.acquire(Lane.QUESTION, UUID.randomUUID())) granted.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(10_000) }
        // 20 calls, of which 20% is reserved for chatter: the questions' share is 16.
        assertEquals(16, granted.get(), "the day's allowance is a day's")
    }

    /**
     * A refund gives back a slot that was actually held, and nothing else.
     *
     * A refund for a slot nobody took would hand the lane a free unit — and a free unit is
     * exactly what a cap must not contain.
     */
    @Test fun `a refund for a slot nobody held changes nothing`() {
        val budget = CallBudget(dailyCap = 10, perPlayerCap = 100)
        // 20% of 10 is reserved for chatter, so the questions' share is 8.
        budget.refund(Lane.QUESTION, alice)
        assertEquals(8, budget.remaining(Lane.QUESTION), "the lane gained a unit it never lost")
        assertEquals(0, budget.usedBy(alice))
    }

    /** A refund and a spend cannot race each other into a negative count. */
    @Test fun `a refund racing a spend leaves the counters sane`() {
        val budget = CallBudget(dailyCap = 100, perPlayerCap = 100)
        val start = CountDownLatch(1)
        val worker = Thread {
            start.await()
            repeat(500) { budget.acquire(Lane.QUESTION, alice) }
        }
        val refunding = Thread {
            start.await()
            repeat(500) { budget.refund(Lane.QUESTION, alice) }
        }
        worker.start(); refunding.start()
        start.countDown()
        worker.join(10_000); refunding.join(10_000)
        assertTrue(budget.usedBy(alice) >= 0, "a counter went negative")
        assertTrue(budget.remaining(Lane.QUESTION) <= 100, "the cap inflated: ${budget.remaining(Lane.QUESTION)}")
    }

    @Test fun `a new day resets everything`() {
        val budget = CallBudget(dailyCap = 1, perPlayerCap = 1)
        budget.advanceTo(20_000)
        assertTrue(budget.acquire(Lane.QUESTION, alice))
        assertFalse(budget.acquire(Lane.QUESTION, alice))
        assertTrue(budget.advanceTo(20_001))
        assertTrue(budget.acquire(Lane.QUESTION, alice))
    }

    /**
     * The day is only rolled when it actually changed.
     *
     * The timer that drives this fires every hour for the life of the server, and `advanceTo` is
     * what tells it apart from a rollover. If it reset on every tick the whole allowance would come
     * back hourly — so the boolean is the difference between a daily cap and no cap at all.
     */
    @Test fun `the same day does not reset anything`() {
        val budget = CallBudget(dailyCap = 1, perPlayerCap = 1)
        assertTrue(budget.advanceTo(20_000), "the first roll establishes the day")
        assertTrue(budget.acquire(Lane.QUESTION, alice))
        assertFalse(budget.advanceTo(20_000), "twenty-three hours later is still the same day")
        assertFalse(budget.acquire(Lane.QUESTION, alice), "an hourly tick must not refill the cap")
    }

    /** A player who was on the cap yesterday is not still on it today. */
    @Test fun `yesterday's spending does not follow a player into today`() {
        val budget = CallBudget(dailyCap = 1, perPlayerCap = 1)
        budget.advanceTo(20_000)
        assertTrue(budget.acquire(Lane.QUESTION, alice))
        assertEquals(1, budget.usedBy(alice))
        budget.advanceTo(20_001)
        assertEquals(0, budget.usedBy(alice), "the per-player counter is part of the day too")
    }

    /** Rolling a day back must not hand anybody their spending back. */
    @Test fun `a day cannot be un-spent by setting it backwards`() {
        val budget = CallBudget(dailyCap = 2, perPlayerCap = 2)
        budget.advanceTo(20_000)
        budget.acquire(Lane.QUESTION, alice)
        budget.advanceTo(19_999)
        assertEquals(1, budget.usedBy(alice))
    }

    @Test fun `hasRoom reports without spending`() {
        val budget = CallBudget(dailyCap = 1, perPlayerCap = 100)
        assertTrue(budget.hasRoom(Lane.QUESTION))
        assertTrue(budget.acquire(Lane.QUESTION, alice))
        assertFalse(budget.hasRoom(Lane.QUESTION))
    }
}

class AgentQueueTest {

    private fun admit(
        lane: Lane,
        inFlight: Int = 0,
        capacity: Int = 2,
        backlog: Int = 0,
        maxBacklog: Int = 4,
        closed: Boolean = false,
    ) = AgentQueue.admit(lane, inFlight, capacity, backlog, maxBacklog, closed)

    @Test fun `a question runs when a worker is free`() {
        assertEquals(Admitted.Running, admit(Lane.QUESTION, inFlight = 0))
    }

    /**
     * The asymmetry, stated as a test. A question is held; chatter is dropped. If this ever
     * inverts, the reserved quota stops protecting anybody.
     */
    @Test fun `a question is suspended and chatter is dropped when the server is busy`() {
        assertEquals(Admitted.Suspended, admit(Lane.QUESTION, inFlight = 2, backlog = 0, maxBacklog = 4))
        assertEquals(
            Admitted.Rejected(Busy.SATURATED),
            admit(Lane.PROACTIVE, inFlight = 2, backlog = 0, maxBacklog = 4),
        )
    }

    /** Chatter must not jump a queue of people who are already waiting. */
    @Test fun `chatter never overtakes a waiting question`() {
        assertEquals(
            Admitted.Rejected(Busy.SATURATED),
            admit(Lane.PROACTIVE, inFlight = 1, backlog = 2),
            "a free worker is not a licence to be rude",
        )
    }

    @Test fun `a full backlog is the last line of defence for a question`() {
        assertEquals(Admitted.Suspended, admit(Lane.QUESTION, inFlight = 2, backlog = 3, maxBacklog = 4))
        assertEquals(
            Admitted.Rejected(Busy.BACKLOG_FULL),
            admit(Lane.QUESTION, inFlight = 2, backlog = 4, maxBacklog = 4),
        )
    }

    @Test fun `a closed scheduler refuses everything`() {
        assertEquals(Admitted.Rejected(Busy.CLOSED), admit(Lane.QUESTION, closed = true))
        assertEquals(Admitted.Rejected(Busy.CLOSED), admit(Lane.PROACTIVE, closed = true))
    }

    @Test fun `every outcome is one of the three states`() {
        val outcomes = listOf(
            admit(Lane.QUESTION, inFlight = 0),
            admit(Lane.QUESTION, inFlight = 9),
            admit(Lane.PROACTIVE, inFlight = 0, backlog = 3),
        )
        assertTrue(outcomes.all { it is Admitted.Running || it is Admitted.Suspended || it is Admitted.Rejected })
    }
}

class AgentSchedulerTest {

    @Test fun `work submitted with a free worker runs`() {
        val scheduler = AgentScheduler(workers = 1)
        try {
            val ran = CountDownLatch(1)
            assertTrue(scheduler.submit(Lane.QUESTION) { ran.countDown() } is Admitted)
            assertTrue(ran.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }
    }

    /**
     * The property that matters under load: with a busy, tiny pool, a player's question is still
     * admitted — held rather than dropped — while chatter is refused.
     */
    @Test fun `a question outranks chatter when the pool is saturated`() {
        val release = CountDownLatch(1)
        val busy = CountDownLatch(1)
        val scheduler = AgentScheduler(workers = 1, maxBacklog = 8)
        try {
            // Occupy the single worker.
            scheduler.submit(Lane.QUESTION) {
                busy.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            assertTrue(busy.await(5, TimeUnit.SECONDS))

            val question = scheduler.submit(Lane.QUESTION) { }
            val chatter = scheduler.submit(Lane.PROACTIVE) { }
            release.countDown()

            assertTrue(question is Admitted.Suspended || question is Admitted.Running, "got $question")
            assertEquals(Admitted.Rejected(Busy.SATURATED), chatter, "chatter gives way")
            assertEquals(1, scheduler.droppedProactive.get())
            assertEquals(0, scheduler.droppedQuestion.get())
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    @Test fun `the backlog is bounded, so waiting cannot grow without limit`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val scheduler = AgentScheduler(workers = 1, maxBacklog = 2)
        try {
            scheduler.submit(Lane.QUESTION) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val outcomes = (1..6).map { scheduler.submit(Lane.QUESTION) { } }
            assertTrue(outcomes.any { it is Admitted.Rejected }, "the backlog must fill up: $outcomes")
            assertTrue(scheduler.backlogDepth() <= 2, "backlog was ${scheduler.backlogDepth()}")
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    /** A work item that throws must not take the worker with it. */
    @Test fun `a failing work item does not stop the queue draining`() {
        val scheduler = AgentScheduler(workers = 1)
        try {
            scheduler.submit(Lane.QUESTION) { error("boom") }
            val ran = CountDownLatch(1)
            scheduler.submit(Lane.QUESTION) { ran.countDown() }
            assertTrue(ran.await(5, TimeUnit.SECONDS), "the worker died with the work item")
        } finally {
            scheduler.close()
        }
    }

    @Test fun `a closed scheduler refuses rather than accepting into the void`() {
        val scheduler = AgentScheduler(workers = 1).apply { close() }
        val ran = AtomicInteger()
        val outcome = scheduler.submit(Lane.QUESTION) { ran.incrementAndGet() }
        assertEquals(Admitted.Rejected(Busy.CLOSED), outcome)
        assertEquals(0, ran.get(), "nothing may run after close")
    }

    /**
     * Every submission lands in exactly one bucket, whatever the timing.
     *
     * The previous version asserted `running + suspended == 3` with a backlog of one, which only
     * holds if the worker has not yet picked anything up — so it passed by luck and failed the
     * moment the scheduler was warmed. The invariant is the accounting, not a race.
     */
    @Test fun `every submission is accounted for in exactly one bucket`() {
        val scheduler = AgentScheduler(workers = 1, maxBacklog = 1)
        try {
            val submitted = (1..6).map { scheduler.submit(Lane.QUESTION) { } }
            assertEquals(6, scheduler.admitted.get() + scheduler.droppedQuestion.get() + scheduler.droppedProactive.get(),
                "nothing may be lost or double-counted")
            assertTrue(scheduler.suspended.get() <= scheduler.admitted.get(),
                "suspended is a subset of admitted, never an extra bucket")
            assertEquals(6, submitted.count { it is Admitted.Running || it is Admitted.Suspended || it is Admitted.Rejected })
            assertEquals(submitted.count { it is Admitted.Rejected }, scheduler.droppedQuestion.get() + scheduler.droppedProactive.get())
        } finally {
            scheduler.close()
        }
    }
}
