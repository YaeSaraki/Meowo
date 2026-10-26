package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class RecommendationTest {

    private fun chat(samples: Int = 50, avgLen: Double = 20.0, publicPct: Double = 90.0, emoji: Double = 5.0, peak: Int? = 20) =
        ChatShape(samples, avgLen, publicPct, emoji, peak)

    private fun actions(net: Int = 0) = ActionShape(0, 0, 0, net, 0, net)

    private fun profile(id: String, name: String, shape: ChatShape = chat(), acts: ActionShape = actions()) =
        Similarity.Profile(id, name, shape, acts)

    @Test fun `identical profiles are the best possible match`() {
        assertEquals(1.0, Similarity.score(chat(), actions(), chat(), actions()), 1e-9)
    }

    @Test fun `opposite habits score poorly`() {
        // Opposite has to be opposite on *every* axis. An earlier version of this test varied only
        // message length and channel, so three of five dimensions scored a perfect 1.0 and the
        // weighted average could not fall below ~0.55 however different the two people were.
        val night = chat(avgLen = 2.0, publicPct = 100.0, emoji = 0.0, peak = 21)
        val day = chat(avgLen = 140.0, publicPct = 0.0, emoji = 90.0, peak = 9)
        val score = Similarity.score(night, actions(net = 200), day, actions(net = -200))
        assertTrue(score < 0.5, "opposite people should not be recommended, got $score")
    }

    /**
     * The invariant a real bug broke: `score(a, b)` once compared a pair of dimensions with itself
     * and returned 1.0 for every input. Symmetry alone would not have caught that, so the
     * assertion that matters is the range, not the mirror.
     */
    @Test fun `the score is symmetric and lands in range`() {
        val a = chat(avgLen = 12.0, publicPct = 70.0, emoji = 10.0, peak = 19)
        val b = chat(avgLen = 60.0, publicPct = 30.0, emoji = 40.0, peak = 2)
        val ab = Similarity.score(a, actions(net = 40), b, actions(net = -90))
        val ba = Similarity.score(b, actions(net = -90), a, actions(net = 40))
        assertEquals(ab, ba, 1e-9, "asking 'who suits me' must not depend on who is asking")
        assertTrue(ab in 0.0..1.0, "got $ab")
    }

    /**
     * The point of the whole class.
     *
     * A recommendation engine that read `soft_hits` would quietly be a blacklist: "we think you
     * would get on" would really mean "nobody near here has been punished".
     */
    @Test fun `evaluative data cannot move a single score`() {
        val before = Similarity.score(chat(), actions(), chat(), actions())
        // The only way soft_hits can leak in is if some dimension read it. None does.
        val profileWithHits = profile("b", "Bob", chat(), ActionShape(0, 999, 999, 0, 999, 0))
        val after = Similarity.score(chat(), actions(), profileWithHits.shape, profileWithHits.actions)
        assertEquals(before, after, "action shape is compared only through netBuild")
    }

    @Test fun `players with too little data are not recommended to anyone`() {
        val thin = profile("b", "Bob", chat(samples = 2))
        assertTrue(Similarity.recommend(chat(), actions(), listOf(thin), 5).isEmpty())
    }

    @Test fun `the asker is never recommended to themselves`() {
        val list = listOf(
            profile("me", "Me", chat()),
            profile("b", "Bob", chat()),
        )
        val out = Similarity.recommend(chat(), actions(), list, 5, exclude = setOf("me"))
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test fun `results are ordered and capped`() {
        val list = (1..10).map { profile("p$it", "P$it") }
        val out = Similarity.recommend(chat(), actions(), list, limit = 3)
        assertEquals(3, out.size)
        assertEquals(out.sortedByDescending { it.score }, out, "best match first")
    }

    @Test fun `a candidate's uuid is carried but never needed for scoring`() {
        val out = Similarity.recommend(chat(), actions(), listOf(profile("uuid-1", "Bob")), 1)
        assertEquals("uuid-1", out.single().id)
        assertEquals("Bob", out.single().name)
    }
}

class ReportVisibilityTest {

    private val me = java.util.UUID.randomUUID()
    private val you = java.util.UUID.randomUUID()
    private val reports = listOf(
        VisibleReport("1", Sentiment.NEGATIVE, "偷了我的箱子", "Alice", 1000L),
        VisibleReport("2", Sentiment.POSITIVE, "帮忙修了农场", "Bob", 2000L),
    )

    @Test fun `the subject sees everything and can therefore appeal`() {
        assertEquals(ReportVisibility.SELF, ReportVisibilityCut.visibilityOf(me, me, isAdmin = false))
        assertEquals(2, ReportVisibilityCut.forViewer(me, me, isAdmin = false, reports = reports).size)
    }

    @Test fun `another player gets nothing but the tally`() {
        assertEquals(ReportVisibility.AGGREGATE, ReportVisibilityCut.visibilityOf(you, me, isAdmin = false))
        assertTrue(ReportVisibilityCut.forViewer(you, me, isAdmin = false, reports = reports).isEmpty())
    }

    @Test fun `the aggregate carries counts only`() {
        val summary = ReportVisibilityCut.summaryOf(reports)
        assertEquals(1, summary.positive)
        assertEquals(1, summary.negative)
        // No author, no wording, no timestamps: nothing that can be used to run a pile-on.
        assertEquals("ReportSummary(positive=1, negative=1)", summary.toString())
    }

    @Test fun `an admin sees the record but is still an admin`() {
        assertEquals(ReportVisibility.ADMIN, ReportVisibilityCut.visibilityOf(you, me, isAdmin = true))
        assertEquals(2, ReportVisibilityCut.forViewer(you, me, isAdmin = true, reports = reports).size)
    }
}

class WorkOrderLadderTest {

    private val created = Instant.parse("2026-10-01T00:00:00Z")
    private val later = created.plusSeconds(30 * 86_400)

    private fun order(
        level: WorkOrderLevel = WorkOrderLevel.RECORDED,
        authors: Int = 5,
        state: WorkOrderState = WorkOrderState.OPEN,
    ) = WorkOrder("subject", level, state, created, authors)

    private fun reap(
        order: WorkOrder = order(),
        online: Int = 10,
        admin: Boolean = false,
        facts: Boolean = true,
        now: Instant = later,
        lastRestricted: Instant? = null,
        quiet: Boolean = false,
    ) = WorkOrderLadder.next(
        ReapInputs(order, online, admin, facts, now, lastRestricted, quiet),
    )

    @Test fun `one accusation is not enough to tell anybody anything`() {
        assertNull(reap(order(authors = 1)))
        assertNull(reap(order(authors = 2)))
        assertEquals(WorkOrderLevel.NOTIFIED, reap(order(authors = 3)))
    }

    @Test fun `the ladder only ever goes up`() {
        val rungs = WorkOrderLevel.entries.toList()
        for (i in 0 until rungs.lastIndex) {
            val from = order(level = rungs[i])
            val next = reap(from, online = 20, admin = true, now = later)
            assertTrue(next == null || next.ordinal > from.level.ordinal, "$from must not step down")
        }
        assertNull(reap(order(level = WorkOrderLevel.RESTRICTED)), "the ladder ends at a restriction")
    }

    @Test fun `one player online is never enough for a restriction`() {
        assertNull(reap(order(level = WorkOrderLevel.QUEUED), online = 1, admin = false))
        assertEquals(
            WorkOrderLevel.RESTRICTED,
            reap(order(level = WorkOrderLevel.QUEUED), online = 5, admin = false),
        )
    }

    @Test fun `an admin counts as an audience even on a quiet server`() {
        assertEquals(WorkOrderLevel.RESTRICTED, reap(order(level = WorkOrderLevel.QUEUED), online = 2, admin = true))
    }

    @Test fun `opinion alone can never open a restriction`() {
        assertNull(reap(order(level = WorkOrderLevel.QUEUED), online = 20, admin = true, facts = false),
            "the third gate: pure opinion must not escalate")
    }

    @Test fun `an immediate reaction cannot punish`() {
        assertNull(
            reap(order(level = WorkOrderLevel.QUEUED), online = 20, admin = true, now = created.plusSeconds(60)),
            "the 24h gate exists so an angry reaction cannot trigger anything",
        )
    }

    @Test fun `a repeat restriction inside the window is refused`() {
        assertNull(
            reap(order(level = WorkOrderLevel.QUEUED), online = 20, admin = true, lastRestricted = later.minusSeconds(3600)),
        )
        assertEquals(
            WorkOrderLevel.RESTRICTED,
            reap(order(level = WorkOrderLevel.QUEUED), online = 20, admin = true, lastRestricted = later.minusSeconds(8 * 86_400L)),
        )
    }

    @Test fun `a dismissed or upheld order stops moving`() {
        assertNull(reap(order(state = WorkOrderState.DISMISSED)))
        assertNull(reap(order(state = WorkOrderState.UPHELD)))
        assertNull(reap(order(state = WorkOrderState.EXPIRED)))
    }

    /**
     * Every terminal state, at the one level that would otherwise climb.
     *
     * Written as an exhaustive loop because the failure mode is a state being *added* and then
     * quietly left out of the guard: `SELF_RESOLVED` sat in that enum, named like a finish, with no
     * caller and no mention in `next()` — so the day somebody used it, the ladder would have carried
     * on from a closed case. `openWorkOrders` hid it, which is exactly why the bug could sit there.
     */
    @Test fun `no terminal state can be climbed out of`() {
        val climbing = WorkOrderLevel.QUEUED
        for (state in WorkOrderState.entries) {
            val moves = reap(order(level = climbing, state = state), online = 20, admin = true)
            val expected = state in setOf(WorkOrderState.OPEN, WorkOrderState.NOTIFIED)
            assertEquals(expected, moves != null, "$state should ${if (expected) "move" else "be terminal"}")
        }
    }

    @Test fun `a quiet player is never addressed even when their order has climbed`() {
        val climbed = order(level = WorkOrderLevel.QUEUED)
        assertEquals(WorkOrderLevel.RESTRICTED, reap(climbed, quiet = true))
        assertFalse(WorkOrderLadder.maySpeak(WorkOrderLevel.QUEUED, quiet = true))
        assertFalse(WorkOrderLadder.maySpeak(WorkOrderLevel.RECORDED, quiet = false))
        assertTrue(WorkOrderLadder.maySpeak(WorkOrderLevel.QUEUED, quiet = false))
    }

    @Test fun `a restriction heals instead of leaving a mark`() {
        val until = later.plusSeconds(1800)
        assertFalse(WorkOrderLadder.expiry(later, until))
        assertTrue(WorkOrderLadder.expiry(until.plusSeconds(1), until))
    }
}

class PromptFactsTest {

    @Test fun `evaluative columns are stripped before anything is rendered`() {
        val row = mapOf("traits" to "话少", "soft_hits" to "5", "risk" to "9")
        assertEquals(setOf("traits"), Redaction.promptableFacts(row).keys)
    }

    @Test fun `the forbidden set covers every evaluative field`() {
        assertTrue(NEVER_IN_PROMPT.containsAll(listOf("soft_hits", "risk")))
        assertFalse(NEVER_IN_PROMPT.contains("traits"), "descriptive facts must stay usable")
    }

    @Test fun `hour labels are stable`() {
        assertEquals("凌晨", TraitMath.hourLabel(3))
        assertEquals("上午", TraitMath.hourLabel(9))
        assertEquals("中午", TraitMath.hourLabel(12))
        assertEquals("下午", TraitMath.hourLabel(16))
        assertEquals("晚上", TraitMath.hourLabel(21))
    }

    @Test fun `hour buckets respect the configured zone`() {
        val tokyo = ZoneId.of("Asia/Shanghai")
        val moment = ZonedDateTime.parse("2026-10-07T23:30:00+09:00")
        assertEquals(moment.hour, TraitMath.hourOf(moment))
    }
}