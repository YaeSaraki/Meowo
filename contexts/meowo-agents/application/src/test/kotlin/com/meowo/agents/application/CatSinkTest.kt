package com.meowo.agents.application

import com.meowo.agents.domain.CatRegistry
import com.meowo.agents.domain.CatTurn
import com.meowo.agents.domain.Completion
import com.meowo.agents.domain.ConversationMemory
import com.meowo.agents.domain.LlmClient
import com.meowo.agents.domain.Redaction
import com.meowo.agents.domain.ToolSpec
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CatSinkTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val econ = CatRegistry.byId("economy")!!

    /**
     * A realistic epoch base. `Guard.cooldownLeft` treats 0 as "never asked", so a clock stuck
     * at zero makes the very first stamp indistinguishable from a player who has never spoken.
     */
    private var T0 = 1_700_000_000_000L

    /** Returns whatever it is scripted with, and records how often it was asked. */
    private class Scripted(val answer: String = "喵") : LlmClient {
        val asked = AtomicInteger()
        val prompts = mutableListOf<List<com.meowo.agents.domain.ChatMessage>>()

        /** Lets a test observe the call the way an asynchronous test needs to: by waiting on it. */
        var onCall: () -> Unit = {}

        override fun complete(model: String, messages: List<com.meowo.agents.domain.ChatMessage>, tools: List<ToolSpec>) =
            Completion.Text(answer).also {
                asked.incrementAndGet()
                prompts += messages.toList()
                onCall()
            }
    }

    /**
     * The schema as **text**, because that is what the tool contract carries: the domain must not
     * know what a JSON library is.
     */
    private fun params() = """{"type":"object","properties":{}}"""

    private fun runtime(client: LlmClient, memory: ConversationMemory = ConversationMemory()) = AgentRuntime(
        client = client,
        tools = ToolRegistry(
            listOf(
                readTool("economy.balance", "查余额", params()) { _, _ ->
                    com.meowo.agents.domain.plain("100 块")
                },
            ),
        ),
        model = "test",
        nameOf = { if (it == alice) "Alice" else if (it == bob) "Bob" else it.toString() },
        memory = memory,
    )

    private var scheduler: AgentScheduler? = null

    /** Captures the lines the sink hands to the chat context's private-message rendering. */
    private fun recording(sink: CatSink): List<String> {
        val out = mutableListOf<String>()
        sink.attachReply { _, _, _, text -> out += text }
        return out
    }

    private fun sink(
        client: LlmClient = Scripted(),
        policy: (UUID, com.meowo.agents.domain.CatProfile, String) -> Admission = { _, cat, _ -> Admission.Accepted(cat) },
        budget: CallBudget = CallBudget(dailyCap = 10_000, perPlayerCap = 10_000),
        cooldown: Long = 10_000L,
        clock: () -> Long = { T0 },
        pool: AgentScheduler? = null,
        adminOf: (UUID) -> Boolean = { false },
        impressions: ImpressionService? = null,
        disclose: (UUID, UUID) -> Redaction.Verdict = { asker, subject ->
            if (asker == subject) Redaction.Verdict.Allowed(Redaction.Scope.SELF)
            else Redaction.Verdict.Refused(Redaction.NOT_ALLOWED_REFUSAL)
        },
        onFailure: (String, Throwable) -> Unit = { _, _ -> },
    ): CatSink {
        val sched = pool ?: AgentScheduler(workers = 1).also { scheduler = it }
        return CatSink(
            runtime(client), sched, budget, policy, cooldown, clock,
            adminOf = adminOf, disclose = disclose, impressions = impressions,
            onFailure = onFailure,
        )
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        scheduler?.close()
    }

    // ── the synchronous contract ─────────────────────────────────────────────────────────────

    /**
     * The reason this class exists in the shape it is.
     *
     * `ChatService.directMessage` calls the sink on the caller's thread, so a sink that blocked
     * would hold up the chat command for the length of a model round trip. The latch is released
     * only when the model is asked, and the assertion is that the sink returned before that.
     */
    @Test fun `the sink returns before the model has been asked`() {
        val release = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val slowClient = object : LlmClient {
            override fun complete(model: String, messages: List<com.meowo.agents.domain.ChatMessage>, tools: List<ToolSpec>): Completion {
                check(release.await(5, TimeUnit.SECONDS)) { "test never released the model" }
                answered.countDown()
                return Completion.Text("喵")
            }
        }
        val s = sink(slowClient)
        val started = System.nanoTime()
        assertTrue(s.onDirectMessage(alice, "economy", "我有多少钱"), "an accepted message promises an answer")
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        // The model is parked on the latch for five seconds. A sink that called it inline could not
        // have returned at all, so the elapsed time is the whole proof — asserting anything about
        // when the pool thread *starts* would only be asserting that threads are threads.
        assertTrue(elapsedMillis < 250, "the sink took ${elapsedMillis}ms; it must not wait on a model")
        release.countDown()
        assertTrue(answered.await(5, TimeUnit.SECONDS))
    }

    @Test fun `a reply is delivered to the injected handler`() {
        val delivered = CountDownLatch(1)
        var got: CatTurn? = null
        val s = sink()
        s.onReply = { turn, key, cat -> got = turn; delivered.countDown() }
        assertTrue(s.onDirectMessage(alice, "economy", "我有多少钱"))
        assertTrue(delivered.await(5, TimeUnit.SECONDS), "no reply was delivered")
        assertTrue(got is CatTurn.Reply, "got $got")
        assertEquals("喵", (got as CatTurn.Reply).text)
    }

    // ── refusals, and who may be told what ────────────────────────────────────────────────────

    /**
     * The one refusal that still returns `false`: nobody is answering that speaker at all, which is
     * what the chat context's "现在没人应答" line actually means. A cat declining is a different
     * thing and has a different line.
     */
    @Test fun `an unknown speaker is refused rather than guessed at`() {
        val s = sink()
        assertFalse(s.onDirectMessage(alice, "not-a-cat", "在吗"))
        assertEquals(TurnedAway.NOT_A_CAT, s.lastRefusal)
    }

    @Test fun `a cat with no tools still answers by delegating`() {
        // SitCat is toolless; refusing the message outright would leave players unable to reach it
        // at all. The turn comes back as a plain reply and delegation happens inside the model.
        val s = sink(Scripted("这个问 MeowCat 吧"))
        assertTrue(s.onDirectMessage(alice, "sit", "我能坐下吗"))
    }

    @Test fun `policy can refuse without the sink knowing why`() {
        val s = sink(policy = { _, _, _ -> Admission.Refused(TurnedAway.NOT_ALLOWED) })
        val said = recording(s)
        assertTrue(s.onDirectMessage(alice, "economy", "在吗"), "policy refusals are explained, not silent")
        assertEquals(TurnedAway.NOT_ALLOWED, s.lastRefusal)
        assertTrue(said.isNotEmpty())
    }

    // ── rate limits ──────────────────────────────────────────────────────────────────────────

    @Test fun `the same player cannot hammer the same cat`() {
        var clock = T0
        val s = sink(cooldown = 10_000L, clock = { clock })
        assertTrue(s.onDirectMessage(alice, "economy", "第一次"))
        // The cooldown starts when the question is accepted, not when the answer is ready: waiting
        // for the answer would leave a window in which a second question is admitted.
        assertTrue(s.onDirectMessage(alice, "economy", "第二次"), "handled, with an explanation")
        assertEquals(TurnedAway.COOLDOWN, s.lastRefusal)
        assertEquals(TurnedAway.COOLDOWN, s.lastRefusal)
        clock = T0 + 10_001L
        assertTrue(s.onDirectMessage(alice, "economy", "第三次"), "the cooldown must expire")
    }

    @Test fun `a cooldown on one cat does not lock the player out of another`() {
        var clock = T0
        val s = sink(cooldown = 10_000L, clock = { clock })
        assertTrue(s.onDirectMessage(alice, "economy", "钱"))
        assertEquals(TurnedAway.COOLDOWN, s.onDirectMessage(alice, "economy", "钱").let { s.lastRefusal })
        assertTrue(s.onDirectMessage(alice, "home", "家"), "a different cat is a different window")
        clock = T0 + 10_001L
        assertTrue(s.onDirectMessage(bob, "economy", "钱"), "a different player is a different window")
    }

    /** A spammer must not be able to spend the budget that real questions need. */
    @Test fun `a player in cooldown does not consume quota`() {
        val budget = CallBudget(dailyCap = 10_000, perPlayerCap = 10_000)
        var clock = T0
        val s = sink(budget = budget, cooldown = 10_000L, clock = { clock })
        repeat(20) { s.onDirectMessage(alice, "economy", "spam") }
        assertEquals(1, budget.usedBy(alice), "only the first admitted message may spend budget")
    }

    @Test fun `quota exhaustion is a refusal, not a silent drop`() {
        val budget = CallBudget(dailyCap = 10_000, perPlayerCap = 1)
        val s = sink(budget = budget, cooldown = 0L)
        val said = recording(s)
        assertTrue(s.onDirectMessage(alice, "economy", "第一次"))
        assertTrue(s.onDirectMessage(alice, "economy", "第二次"), "the player is told, not dropped")
        assertEquals(TurnedAway.QUOTA, s.lastRefusal)
        assertTrue(said.isNotEmpty(), "and the reason is said out loud")
    }

    @Test fun `a shutting-down pool is refused honestly`() {
        val pool = AgentScheduler(workers = 1).apply { close() }
        val s = sink(pool = pool)
        val said = recording(s)
        assertTrue(s.onDirectMessage(alice, "economy", "在吗"), "a dead pool still explains itself")
        assertEquals(TurnedAway.BUSY, s.lastRefusal)
        assertTrue(said.isNotEmpty())
    }

    // ── housekeeping ──────────────────────────────────────────────────────────────────────────

    @Test fun `quitting forgets a player's windows`() {
        val memory = ConversationMemory()
        val s = sink(clock = { 0L })
        s.onDirectMessage(alice, "economy", "钱")
        val latch = CountDownLatch(1)
        s.onReply = { _, _, _ -> latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        assertTrue(s.hasAnswered(alice, "economy"))
        s.forget(alice)
        assertFalse(s.hasAnswered(alice, "economy"))
    }

    @Test fun `a model failure still produced a turn, so the player is not left hanging`() {
        val failing = object : LlmClient {
            override fun complete(model: String, messages: List<com.meowo.agents.domain.ChatMessage>, tools: List<ToolSpec>) =
                Completion.Unusable
        }
        val delivered = CountDownLatch(1)
        var got: CatTurn? = null
        val s = sink(failing)
        s.onReply = { turn, _, _ -> got = turn; delivered.countDown() }
        assertTrue(s.onDirectMessage(alice, "economy", "在吗"))
        assertTrue(delivered.await(5, TimeUnit.SECONDS), "a failure must still close the loop")
        assertEquals(CatTurn.Refuse(AgentRuntime.APOLOGISE), got)
    }

    /**
     * The reported bug: a cat that could not answer returned `false`, and the chat context renders
     * that as `msg-refused-blocked` — "该内容被审核拦截". Nothing had been moderated. The sink now
     * explains itself and returns `true`, so the player gets a line and a journal entry.
     */
    @Test fun `a cat that cannot answer explains itself rather than claiming a censor`() {
        val s = sink(policy = { _, _, _ -> Admission.Refused(TurnedAway.NOT_ALLOWED) })
        val said = recording(s)
        assertTrue(s.onDirectMessage(alice, "economy", "在吗"), "a refusal that explains itself is still handled")
        assertTrue(said.isNotEmpty(), "a decline must be said out loud, not swallowed")
        val line = said.joinToString(" ")
        assertFalse(line.contains("拦截"), "nothing was moderated: $line")
        assertFalse(line.contains("审核"), line)
    }

    @Test fun `a cooldown is explained rather than silent`() {
        var clock = T0
        val s = sink(cooldown = 10_000L, clock = { clock })
        val replies = mutableListOf<String>()
        s.attachReply { _, _, _, text -> replies += text }
        s.onDirectMessage(alice, "economy", "第一次")
        clock = T0 + 1
        s.onDirectMessage(alice, "economy", "第二次")
        assertTrue(replies.any { it.contains("歇") }, "the cat should say it is taking a break: $replies")
    }

    /**
     * A failure on the worker thread is reported, and the player is not left waiting.
     *
     * The exception used to be swallowed by a `runCatching` that nobody looked at: the cat said
     * nothing, the player got nothing, and the server log said nothing either. That is the shape
     * of a failure nobody can fix.
     */
    @Test fun `a failure on the worker is reported and the player is not left waiting`() {
        val boom = RuntimeException("boom")
        val failing = object : LlmClient {
            override fun complete(model: String, messages: List<com.meowo.agents.domain.ChatMessage>, tools: List<ToolSpec>): Completion =
                throw boom
        }
        val reported = CountDownLatch(1)
        val errors = mutableListOf<Throwable>()
        val s = sink(failing, onFailure = { _, error -> errors += error; reported.countDown() })
        val turns = mutableListOf<CatTurn>()
        s.onReply = { turn, _, _ -> turns += turn }
        assertTrue(s.onDirectMessage(alice, "economy", "在吗"))
        assertTrue(reported.await(5, TimeUnit.SECONDS), "the failure was swallowed")
        assertTrue(errors.contains(boom), "the failure must be reported as it was: $errors")
        assertEquals(1, turns.size, "the player must not be left waiting for an answer that is not coming")
        assertEquals(CatTurn.Refuse(AgentRuntime.APOLOGISE), turns.single())
    }

    /**
     * Quitting clears **both** clocks.
     *
     * The greeting cooldown used to survive the logout, so a player who came back was still inside
     * it and the cat refused to greet them because of a stamp from before the logout.
     */
    @Test fun `quitting clears the greeting cooldown too`() {
        var clock = T0
        // A pool with room: the proactive lane drops its work when every worker is busy, and that
        // refusal looks exactly like a cooldown refusal. The test is about the cooldown, so the
        // pool must not be the reason anything is refused.
        AgentScheduler(workers = 4).use { pool ->
            val s = sink(cooldown = 10_000L, clock = { clock }, pool = pool)
            val cat = com.meowo.agents.domain.CatRegistry.byId("economy")!!
            s.offerProactive(alice, cat, "喵")
            clock = T0 + 1
            val refused = s.offerProactive(alice, cat, "喵")
            assertTrue(
                refused is Admitted.Rejected && refused.reason == Busy.SATURATED,
                "the greeting cooldown is real: $refused",
            )
            s.forget(alice)
            clock = T0 + 2
            val after = s.offerProactive(alice, cat, "喵")
            assertFalse(
                after is Admitted.Rejected && after.reason == Busy.SATURATED,
                "a returning player must not be inside the previous session's greeting cooldown",
            )
        }
    }

    @Test fun `the budget is resettable for a new day`() {
        val budget = CallBudget(dailyCap = 1, perPlayerCap = 100)
        assertTrue(budget.advanceTo(20_000))
        assertTrue(budget.acquire(Lane.QUESTION, alice))
        assertFalse(budget.acquire(Lane.QUESTION, alice))
        assertTrue(budget.advanceTo(20_001))
        assertTrue(budget.acquire(Lane.QUESTION, alice))
    }

    /**
     * The sink is what the context ticks to make `agents.daily-cap` a daily cap.
     *
     * It used to have no path to the budget's counters at all, so nothing but a server restart ever
     * refilled them: a server that ran out of allowance looked exactly like one whose cats had
     * stopped answering. This is the wiring, and it is one line long because the day has to come
     * from the caller rather than from a clock inside the budget.
     */
    @Test fun `a new day gives the sink its allowance back`() {
        val budget = CallBudget(dailyCap = 1, perPlayerCap = 100)
        val s = sink(budget = budget)
        s.onDirectMessage(alice, "economy", "在吗")
        assertNull(s.lastRefusal)
        s.onDirectMessage(alice, "home", "在吗")
        assertEquals(TurnedAway.QUOTA, s.lastRefusal, "the cap is spent")
        assertTrue(s.advanceBudgetDay(30_000))
        s.onDirectMessage(alice, "home", "还在吗")
        assertNull(s.lastRefusal, "tomorrow the cap is new again")
    }

    @Test fun `the same day does not give the sink anything back`() {
        val budget = CallBudget(dailyCap = 1, perPlayerCap = 100)
        val s = sink(budget = budget)
        s.advanceBudgetDay(30_000)
        s.onDirectMessage(alice, "economy", "在吗")
        assertFalse(s.advanceBudgetDay(30_000))
        s.onDirectMessage(alice, "home", "在吗")
        assertEquals(TurnedAway.QUOTA, s.lastRefusal, "an hourly tick must not refill the cap")
    }

    /**
     * The whole point of layer 2, end to end through the sink.
     *
     * `CatSink` built every question without an `ownImpression`, so the prompt's `[your impression]`
     * block could only ever read "(你还没怎么和 Alice 说过话)" — permanently, because nothing wrote
     * an impression either. This asserts the text the cat is actually handed.
     */
    @Test fun `an impression the cat wrote reaches its next question`() {
        val dir = java.nio.file.Files.createTempDirectory("impression")
        val profile = com.meowo.agents.infrastructure.PlayerProfileStore(dir.resolve("profile.db"))
        try {
            val writer = Scripted(answer = "很实在，不抱怨")
            val written = CountDownLatch(1)
            writer.onCall = { written.countDown() }
            val impressions = ImpressionService(
                client = writer,
                store = profile,
                model = "test",
                nameOf = { if (it == alice) "Alice" else it.toString() },
                traitsOf = { "短句" },
                localeOf = { "zh_CN" },
                spend = { true },
            )
            val chat = Scripted()
            var clock = T0
            val s = sink(client = chat, clock = { clock }, impressions = impressions)
            // The sink answers on the pool, and the impression is written after the reply is
            // delivered, so each turn is waited for rather than fired and forgotten.
            val done = CountDownLatch(4)
            s.onReply = { _, _, _ -> done.countDown() }
            repeat(4) {
                s.onDirectMessage(alice, "economy", "我还有多少钱？")
                clock += 20_000
            }
            // Waiting on the *reply* is not enough: the impression is written after it, on the same
            // worker. Waiting on the impression call itself is what makes this a test rather than a
            // race that usually passes.
            assertTrue(done.await(5, TimeUnit.SECONDS), "not every turn answered")
            assertTrue(written.await(5, TimeUnit.SECONDS), "no impression was written")

            assertEquals(1, writer.asked.get(), "three meetings is one impression, not three")
            val last = (chat.prompts.last().last { it.role == "user" } as com.meowo.agents.domain.ChatMessage.User).content
            assertTrue(last.contains("你觉得 Alice：很实在，不抱怨"), "the prompt the cat is handed:\n$last")
        } finally {
            profile.close()
        }
    }

    /**
     * The wiring the topics slot never had, asserted where it lives: the window the runtime keeps
     * is the only source, and it is read on the agent thread after the reply — never from the
     * delivery path, and never from the database.
     */
    @Test fun `what they talked about reaches the impression as labels`() {
        val dir = java.nio.file.Files.createTempDirectory("topics")
        val profile = com.meowo.agents.infrastructure.PlayerProfileStore(dir.resolve("profile.db"))
        try {
            val writer = Scripted(answer = "挺实在的矿工")
            val written = CountDownLatch(1)
            writer.onCall = { written.countDown() }
            val impressions = ImpressionService(
                client = writer, store = profile, model = "test",
                nameOf = { if (it == alice) "Alice" else it.toString() },
                traitsOf = { "短句" }, localeOf = { "zh_CN" }, spend = { true },
            )
            val chat = Scripted()
            var clock = T0
            val s = sink(client = chat, clock = { clock }, impressions = impressions)
            val done = CountDownLatch(3)
            s.onReply = { _, _, _ -> done.countDown() }
            repeat(3) {
                s.onDirectMessage(alice, "economy", "我刚下矿洞挖矿，挖到好多钻石")
                clock += 20_000
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "not every turn answered")
            assertTrue(written.await(5, TimeUnit.SECONDS), "no impression was written")

            val system = (writer.prompts.single().first() as com.meowo.agents.domain.ChatMessage.System).content
            assertTrue(system.contains("挖矿"), "the conversation's subject must reach the prompt:\n$system")
            assertFalse(system.contains("钻石"), "labels only — the sentence itself must not be passed on")
        } finally {
            profile.close()
        }
    }

    /** With no impression the prompt says so, rather than leaving a hole. */
    @Test fun `no impression yet means the prompt says so`() {        val client = Scripted()
        val s = sink(client = client)
        val done = CountDownLatch(1)
        s.onReply = { _, _, _ -> done.countDown() }
        s.onDirectMessage(alice, "economy", "我还有多少钱？")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val user = (client.prompts.single().last { it.role == "user" } as com.meowo.agents.domain.ChatMessage.User).content
        assertTrue(user.contains("(你还没怎么和 Alice 说过话)"), user)
    }
}
