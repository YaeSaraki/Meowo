package com.meowo.agents.application

import com.meowo.agents.domain.ConversationKey
import com.meowo.agents.domain.LlmClient
import com.meowo.agents.domain.plain
import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The proactive lane's gates, at the only door it has.
 *
 * `offerProactive` was written first and never called by anybody, which is how a whole feature can
 * sit in a codebase looking finished. These tests pin the three things that decide whether a cat
 * speaks unprompted: **the day's allowance**, **how recently it last spoke**, and **that the budget
 * comes back when the work is dropped**.
 *
 * The cooldown is asserted here rather than in `AgentsContext` because that is where it was put on
 * purpose: it is the only entrance to this lane, so "not too soon" belongs to the entrance. Left to
 * a caller it becomes exactly the shape of bug this codebase keeps finding — every new trigger
 * remembers the gate until one does not.
 */
class ProactiveLaneTest {

    private val alice = UUID.randomUUID()
    private val econ = com.meowo.agents.domain.CatRegistry.byId("economy")!!

    /**
     * A real epoch base, because `Guard.cooldownLeft` reads 0 as "never spoke" — a clock stuck at
     * zero makes the first stamp indistinguishable from a player the cat has never met.
     */
    private var T0 = 1_700_000_000_000L
    private var clock: () -> Long = { T0 }

    private class Counting : LlmClient {
        val asked = AtomicInteger()
        val done = CountDownLatch(1)
        override fun complete(
            model: String,
            messages: List<com.meowo.agents.domain.ChatMessage>,
            tools: List<com.meowo.agents.domain.ToolSpec>,
        ) = com.meowo.agents.domain.Completion.Text("喵").also {
            asked.incrementAndGet()
            done.countDown()
        }
    }

    /**
     * The schema as **text**, because that is what the tool contract carries: the domain must not
     * know what a JSON library is.
     */
    private fun params() = """{"type":"object","properties":{}}"""

    private fun runtime(client: LlmClient) = AgentRuntime(
        client = client,
        tools = ToolRegistry(
            listOf(readTool("economy.balance", "查余额", params()) { _, _ -> plain("100 块") }),
        ),
        model = "test",
        nameOf = { if (it == alice) "Alice" else it.toString() },
        memory = com.meowo.agents.domain.ConversationMemory(),
    )

    private var scheduler: AgentScheduler? = null

    private fun sink(
        client: LlmClient = Counting(),
        budget: CallBudget = CallBudget(dailyCap = 100, perPlayerCap = 100),
        cooldown: Long = 10_000L,
        workers: Int = 1,
    ): CatSink {
        val sched = AgentScheduler(workers = workers).also { scheduler = it }
        return CatSink(
            runtime(client), sched, budget,
            policy = { _, cat, _ -> Admission.Accepted(cat) },
            cooldownMillis = cooldown, now = clock,
        )
    }

    @AfterEach fun tearDown() {
        scheduler?.close()
    }

    // ── it fires at all ──────────────────────────────────────────────────────────────────────

    @Test fun `a greeting the player never asked for is delivered`() {
        val client = Counting()
        val sink = sink(client)

        val admitted = sink.offerProactive(alice, econ, "玩家刚执行了 /meo eco")

        assertFalse(admitted is Admitted.Rejected, "the greeting was refused: $admitted")
        assertTrue(client.done.await(3, TimeUnit.SECONDS), "the model was never asked")
    }

    /**
     * The whole point: a greeting costs a **proactive** call, never one from the questions' share.
     *
     * `dailyCap = 5` is what makes this decidable rather than decorative — the reserved share is
     * exactly one call, so one greeting empties it. With a round number like 100 the reserve holds 20
     * and the assertion would pass no matter which pocket was charged.
     */
    @Test fun `a greeting spends the proactive allowance and not the question one`() {
        val budget = CallBudget(dailyCap = 5, perPlayerCap = 100)
        assertTrue(budget.hasRoom(Lane.PROACTIVE), "the reserve should start at one call")

        sink(budget = budget).offerProactive(alice, econ, "触发")

        assertFalse(budget.hasRoom(Lane.PROACTIVE), "the greeting did not come out of the proactive share")
        assertTrue(budget.hasRoom(Lane.QUESTION), "chatter ate the questions' reserve")
    }

    // ── the gates ────────────────────────────────────────────────────────────────────────────

    /**
     * The reason `offerProactive` re-checks the allowance itself rather than trusting the caller.
     *
     * A five-percent roll on every command would otherwise be enough to empty a day's chatter
     * allowance in a couple of minutes of ordinary play, and the greeting that fails is not an error
     * the player ever sees.
     */
    @Test fun `an empty proactive allowance refuses before anything else happens`() {
        val budget = CallBudget(dailyCap = 5, perPlayerCap = 100)
        // 20% of 5 is one call: take it and the lane is empty.
        repeat(1) { assertTrue(budget.acquire(Lane.PROACTIVE, alice)) }
        assertFalse(budget.hasRoom(Lane.PROACTIVE))

        val client = Counting()
        val admitted = sink(client, budget = budget).offerProactive(alice, econ, "触发")

        assertEquals(Admitted.Rejected(Busy.BUDGET), admitted)
        assertEquals(0, client.asked.get(), "the model was asked with an empty allowance")
    }

    @Test fun `a second greeting too soon is refused`() {
        val client = Counting()
        val sink = sink(client)
        assertFalse(sink.offerProactive(alice, econ, "第一次") is Admitted.Rejected)
        assertTrue(client.done.await(3, TimeUnit.SECONDS))

        val second = sink.offerProactive(alice, econ, "第二次")
        assertTrue(second is Admitted.Rejected, "the cat greeted twice inside the cooldown: $second")
        assertEquals(1, client.asked.get(), "a refused greeting still cost a model call")
    }

    /** Time passing is what opens it again — otherwise the gate would be a switch, not a cooldown. */
    @Test fun `the greeting opens again once the cooldown has passed`() {
        val client = Counting()
        // Two workers, and the second one is the point: `client.done` fires **inside** the model call,
        // so with a single worker the greeting's job is still occupying it when the second offer
        // arrives — and the proactive lane refuses rather than queues, which would make this test
        // assert the pool's availability instead of the cooldown. "Busy refuses a greeting" is pinned
        // separately by `a dropped greeting gives the allowance back`.
        val sink = sink(client, cooldown = 10_000L, workers = 2)
        sink.offerProactive(alice, econ, "第一次")
        assertTrue(client.done.await(3, TimeUnit.SECONDS))

        T0 += 10_001L
        assertFalse(sink.offerProactive(alice, econ, "第二次") is Admitted.Rejected)
    }

    /**
     * A greeting must not start the **question** cooldown.
     *
     * One shared stamp would mean the cat says something unprompted and then refuses the question
     * that greeting invited — the player did nothing wrong and got silence.
     */
    @Test fun `a greeting does not start the question cooldown`() {
        val client = Counting()
        val sink = sink(client)
        sink.offerProactive(alice, econ, "未经询问的问候")
        assertTrue(client.done.await(3, TimeUnit.SECONDS))

        assertTrue(
            sink.onDirectMessage(alice, "economy", "在吗") ,
            "the cat refused the question its own greeting invited",
        )
    }

    /** A greeting the pool dropped must not leave the day spent on a call nobody made. */
    @Test fun `a dropped greeting gives the allowance back`() {
        val budget = CallBudget(dailyCap = 100, perPlayerCap = 100)
        val busy = AgentScheduler(workers = 1).also { scheduler = it }
        // Occupy the only worker so the proactive lane — which is never queued — is refused.
        val blocker = CountDownLatch(1)
        busy.submit(Lane.QUESTION) { blocker.await(5, TimeUnit.SECONDS) }

        val client = Counting()
        val sink = CatSink(
            runtime(client), busy, budget,
            policy = { _, cat, _ -> Admission.Accepted(cat) },
            cooldownMillis = 10_000L, now = { T0 },
        )
        val admitted = sink.offerProactive(alice, econ, "触发")

        blocker.countDown()
        assertTrue(admitted is Admitted.Rejected, "a saturated pool accepted a greeting it never ran")
        assertTrue(budget.hasRoom(Lane.PROACTIVE), "the allowance stayed spent on work that never ran")
        assertEquals(0, client.asked.get())
    }

    /**
     * The reserved share is arithmetic, and this is the case that keeps it honest: a small day's
     * allowance must not round a greeting up out of the questions' pocket.
     */
    @Test fun `the chatter reserve never rounds up into the questions' pocket`() {
        val budget = CallBudget(dailyCap = 1, perPlayerCap = 100)
        var calls = 0
        // Question lane only: if the split had rounded up, this would get through.
        while (budget.acquire(Lane.QUESTION, alice)) calls++
        assertEquals(1, calls, "a 1-call day gave the questions more than 1 call")
        assertFalse(budget.hasRoom(Lane.PROACTIVE), "a 1-call day still reserved room for chatter")
    }
}