package com.meowo.agents.application

import com.meowo.agents.domain.Sentiment
import com.meowo.agents.domain.WorkOrderLevel
import com.meowo.agents.infrastructure.PlayerProfileStore
import com.meowo.agents.infrastructure.StoredTraits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DisciplineTest {

    @TempDir
    lateinit var dir: Path

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private var clock = Instant.parse("2026-10-01T00:00:00Z")

    private fun store() = PlayerProfileStore(dir.resolve("d.db"))

    private fun service(s: PlayerProfileStore, co: (UUID, UUID) -> Int = { _, _ -> 20 }, perDay: Int = 1) =
        ReportService(s, co, { clock }, perDay)

    private fun risk(s: PlayerProfileStore) = RiskScorer(s, now = { clock.toEpochMilli() })

    private fun profile(s: PlayerProfileStore, who: String, hits: Int = 0) = s.upsertTraits(
        StoredTraits(who, 100, 20.0, 90.0, 0.0, 20, hits, "句子不长。", clock.toEpochMilli(), 1L),
    )

    /**
     * Files [count] complaints about [subject] from people who have all played with them.
     *
     * The complaints are spread out so no burst window catches them, because a distrusted report
     * is deliberately **not** evidence: a test that wanted a score had to earn it the way a player
     * does.
     */
    private fun complain(
        s: PlayerProfileStore,
        subject: UUID,
        count: Int,
        svc: ReportService = service(s, perDay = 10),
    ) {
        repeat(count) {
            if (it > 0) clock = clock.plusSeconds(7_200)
            svc.submit(UUID.randomUUID(), subject, Sentiment.NEGATIVE, "第 $it 条")
        }
    }

    // ── 互评准入 ─────────────────────────────────────────────────────────────────────────────

    @Test fun `nobody may report themselves`() {
        store().use { s ->
            assertTrue(service(s).submit(alice, alice, Sentiment.NEGATIVE, "自评") is ReportService.Outcome.Refused)
        }
    }

    /**
     * Xbox's rule, and the most valuable line in the file: a complaint from someone who has never
     * played with you is not a complaint, it is noise.
     */
    @Test fun `someone who never played with you cannot report you`() {
        store().use { s ->
            val out = service(s, co = { _, _ -> 0 }).submit(alice, bob, Sentiment.NEGATIVE, "没见过")
            assertTrue(out is ReportService.Outcome.Refused, "$out")
            assertEquals(0, s.count("reports"))
        }
    }

    @Test fun `a thin shared history is stored but never counted`() {
        store().use { s ->
            val out = service(s, co = { _, _ -> 3 }).submit(alice, bob, Sentiment.NEGATIVE, "一起玩过一点")
            assertTrue(out is ReportService.Outcome.Stored, "$out")
            assertFalse((out as ReportService.Outcome.Stored).counted)
            assertEquals(1, s.count("reports"), "it is still on file")
        }
    }

    /**
     * The cap the design settled on: rate-limited admissions, never eviction. Capping the total
     * would delete the oldest reports, and the oldest are what an open order is standing on.
     */
    @Test fun `one author may add one report a day, and the old one survives`() {
        store().use { s ->
            val svc = service(s, perDay = 1)
            assertTrue(svc.submit(alice, bob, Sentiment.NEGATIVE, "第一次") is ReportService.Outcome.Stored)
            assertTrue(svc.submit(alice, bob, Sentiment.NEGATIVE, "第二次") is ReportService.Outcome.Refused)
            assertEquals(1, s.count("reports"), "nothing was overwritten to make room")
            clock = clock.plusSeconds(86_400).plusSeconds(60) // clear of the window edge, not exactly on it
            assertTrue(svc.submit(alice, bob, Sentiment.NEGATIVE, "第二天") is ReportService.Outcome.Stored)
            assertEquals(2, s.count("reports"), "a day later it is allowed again")
        }
    }

    /** A pile-on must not be able to escalate, so the whole window is distrusted. */
    @Test fun `a burst is stored but stops counting`() {
        store().use { s ->
            val svc = service(s, perDay = 10)
            val others = (1..6).map { UUID.randomUUID() }
            others.forEachIndexed { i, who ->
                val out = svc.submit(who, bob, Sentiment.NEGATIVE, "第 ${i + 1} 条")
                assertTrue(out is ReportService.Outcome.Stored, "$out")
            }
            assertEquals(6, s.count("reports"), "all of it is on file")
            // The window that trips is the one distrusted; the four filed before it existed were
            // ordinary reports when they were made, and rewriting history would be its own lie.
            assertEquals(5, svc.independentAccusations(bob), "only the report that arrived into the spike is distrusted")
        }
    }

    @Test fun `a pile-on that trickles in is still counted`() {
        store().use { s ->
            val svc = service(s, perDay = 10)
            repeat(6) { i ->
                svc.submit(UUID.randomUUID(), bob, Sentiment.NEGATIVE, "第 $i 条")
                clock = clock.plusSeconds(7_200) // two hours apart: no window overlap
            }
            assertEquals(6, svc.independentAccusations(bob))
            assertTrue(svc.worthAnOrder(bob))
        }
    }

    /** People, not messages: three identical accusations from one account is one accusation. */
    @Test fun `the order counts people rather than messages`() {
        store().use { s ->
            val svc = service(s, perDay = 10)
            repeat(3) { clock = clock.plusSeconds(7_200); svc.submit(alice, bob, Sentiment.NEGATIVE, "又一件") }
            assertEquals(1, svc.independentAccusations(bob))
            assertFalse(svc.worthTelling(bob), "one person repeating themselves is not three people")
        }
    }

    // ── setRisk ──────────────────────────────────────────────────────────────────────────────

    /**
     * The property that makes this safe to have at all: a score is recomputed from state, so it
     * comes back down on its own. A latching counter would make every accusation permanent.
     *
     * The evidence is **real complaints**, not a number poked into a column. The old version of
     * this test set `soft_hits` directly, which meant it passed while the production chain was
     * broken — nothing ever filed a complaint, so nothing ever moved the counter, and the score
     * the gate received was always zero.
     */
    @Test fun `a risk score falls back on its own once the evidence is gone`() {
        store().use { s ->
            complain(s, bob, 5)
            val scorer = risk(s)
            scorer.watch(bob)
            val high = scorer.recompute { _, _ -> }[bob]!!
            assertTrue(high > 0, "five accusations should be visible to the gate")

            // The complaints live 30 days. Once they are past their expiry there is no evidence
            // left, and nobody has to forgive anybody for the score to go back down.
            clock = clock.plusSeconds(86_400 * 31)
            val low = scorer.recompute { _, _ -> }[bob]!!
            assertEquals(0, low, "with nothing on file the score must be zero again, not a leftover")
        }
    }

    @Test fun `risk never reaches the gate's own block threshold on its own`() {
        store().use { s ->
            complain(s, bob, 50)
            val scorer = risk(s)
            scorer.watch(bob)
            val score = scorer.recompute { _, _ -> }[bob]!!
            assertTrue(
                score < 80,
                "this must never be able to block by itself; $score would reach chat.block-at",
            )
        }
    }

    @Test fun `a clean player has no score and no watchers`() {
        store().use { s ->
            val scorer = risk(s)
            scorer.watch(carol)
            assertEquals(0, scorer.recompute { _, _ -> }[carol])
            scorer.forget(carol)
            assertNull(scorer.lastPushed(carol))
        }
    }

    /**
     * The whole chain, end to end.
     *
     * This is the test that was missing, and its absence is why the 风纪 side of the plugin ran
     * on schedule and decided nothing: `watch` had no caller, `bumpSoftHits` had no caller, and
     * the factual writer erased the counter on every flush. A complaint now starts a player on the
     * watch list, moves the counter, and produces a score the chat gate can read.
     */
    @Test fun `a complaint starts the whole chain`() {
        store().use { s ->
            val scorer = risk(s)
            val svc = service(s, perDay = 10)
            val loop = DisciplineLoop(
                store = s,
                risk = scorer,
                reaper = WorkOrderReaper(s, { 20 }, { true }, { true }, { clock }),
                reports = svc,
                now = { clock.toEpochMilli() },
            )
            assertTrue(scorer.watched.isEmpty(), "nobody is watched until somebody complains")

            val outcome = loop.report(alice, bob, Sentiment.NEGATIVE, "在骂人")
            assertTrue(outcome is ReportService.Outcome.Stored, "$outcome")
            assertTrue(scorer.watched.contains(bob), "the complaint has to put the player on the watch list")

            val score = scorer.recompute { _, _ -> }[bob]!!
            assertTrue(score > 0, "the complaint has to become a score the gate can read, got $score")
            assertEquals(1, s.softHitsOf(bob.toString()), "the complaint has to move the counter")
            assertEquals(
                RiskScorer.SOFT_HIT_WEIGHT, score,
                "the score is the counter weighted, and nothing else",
            )
        }
    }

    /**
     * The factual writer must not touch the discipline column.
     *
     * It used to: the profile flush wrote `soft_hits = 0` and the upsert wrote that zero straight
     * back over the accumulated count, so the score was reset to zero every ten minutes and the
     * gate never saw anything above zero.
     */
    @Test fun `a profile flush does not erase the discipline counter`() {
        store().use { s ->
            complain(s, bob, 3)
            assertTrue(s.softHitsOf(bob.toString()) > 0, "the complaints have to move the counter")
            // The factual writer, writing the descriptive half of the row.
            profile(s, bob.toString(), hits = 0)
            assertTrue(
                s.softHitsOf(bob.toString()) > 0,
                "the factual writer erased the discipline counter; that is the bug this pins",
            )
        }
    }

    // ── 一个 pass，固定顺序 ────────────────────────────────────────────────────────────────────

    @Test fun `a pass prunes, then scores, then advances the ladder`() {
        store().use { s ->
            val scorer = risk(s)
            scorer.watch(bob)
            profile(s, bob.toString(), hits = 0)
            val pushed = mutableListOf<Pair<UUID, Int>>()
            val loop = DisciplineLoop(
                store = s,
                risk = scorer,
                reaper = WorkOrderReaper(s, { 20 }, { true }, { true }, { clock }),
                reports = service(s),
                pushRisk = { who, value -> pushed += who to value },
                now = { clock.toEpochMilli() },
            )
            val pass = loop.run()!!
            assertEquals(0, pass.risk[bob] ?: 0)
            assertTrue(pushed.contains(bob to 0), "risk is pushed every pass, including zero")
        }
    }

    @Test fun `an order is opened once, not once per pass`() {
        store().use { s ->
            val scorer = risk(s)
            val svc = service(s, perDay = 10)
            val loop = DisciplineLoop(
                store = s,
                risk = scorer,
                reaper = WorkOrderReaper(s, { 20 }, { true }, { true }, { clock }),
                reports = svc,
                now = { clock.toEpochMilli() },
            )
            // Five independent people, spread out so no burst window catches them.
            val people = (1..6).map { UUID.randomUUID() }
            people.forEachIndexed { i, who ->
                if (i > 0) clock = clock.plusSeconds(7_200)
                svc.submit(who, bob, Sentiment.NEGATIVE, "第 $i 条")
                scorer.watch(who)
                scorer.watch(bob)
            }
            repeat(3) { loop.run() }
            assertEquals(1, s.openWorkOrders(clock.toEpochMilli()).size, "re-running must not add a second order")
        }
    }

    @Test fun `quarantined evidence is expired rather than escalated forever`() {
        store().use { s ->
            val svc = service(s, perDay = 10)
            repeat(6) { i ->
                if (i > 0) clock = clock.plusSeconds(7_200)
                svc.submit(UUID.randomUUID(), bob, Sentiment.NEGATIVE, "第 $i 条")
            }
            val reaper = WorkOrderReaper(s, { 1 }, { false }, { false }, { clock })
            reaper.open(bob.toString(), 6, null)
            // The order expires at 14 days; the reports it rests on live 30, so the evidence is
            // still on file after the order is gone — which is the point of expiring them apart.
            repeat(20) { clock = clock.plusSeconds(86_400); reaper.run() }
            assertTrue(s.openWorkOrders(clock.toEpochMilli()).isEmpty(), "the order expires at 14 days")
            assertEquals(0, s.prune(clock.toEpochMilli()), "its evidence still has 10 days left")
            repeat(12) { clock = clock.plusSeconds(86_400) }
            assertTrue(s.prune(clock.toEpochMilli()) > 0, "and goes when it reaches 30")
        }
    }

    @Test fun `the reaper and the risk scorer agree about the level`() {
        store().use { s ->
            val reaper = WorkOrderReaper(s, { 20 }, { true }, { true }, { clock })
            reaper.open(bob.toString(), 6, null)
            repeat(11) { clock = clock.plusSeconds(86_400); reaper.run() }
            val level = s.openWorkOrders(clock.toEpochMilli()).single().level
            assertEquals(WorkOrderLevel.RESTRICTED, level)

            val scorer = risk(s)
            scorer.watch(bob)
            val score = scorer.recompute { _, _ -> }[bob]!!
            assertTrue(score >= 20, "a restricted order must leave a visible score: $score")
        }
    }
}
