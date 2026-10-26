package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TraitMathTest {

    private fun shape(samples: Int, avgLen: Double = 20.0, publicPct: Double = 100.0, emoji: Double = 0.0, peak: Int? = 12) =
        ChatShape(samples, avgLen, publicPct, emoji, peak)

    @Test fun `too few messages produce no traits at all`() {
        assertNull(TraitMath.traitsOf(shape(samples = 0)))
        assertNull(TraitMath.traitsOf(shape(samples = TraitMath.MIN_SAMPLES - 1)),
            "four messages is not enough to characterise anyone")
        assertTrue(TraitMath.traitsOf(shape(samples = TraitMath.MIN_SAMPLES))!!.isNotBlank())
    }

    @Test fun `verbosity is described coarsely`() {
        assertTrue(TraitMath.traitsOf(shape(10, avgLen = 4.0))!!.contains("很短"))
        assertTrue(TraitMath.traitsOf(shape(10, avgLen = 150.0))!!.contains("长篇"))
    }

    @Test fun `public versus private habit is described`() {
        assertTrue(TraitMath.traitsOf(shape(10, publicPct = 100.0))!!.contains("公频"))
        assertTrue(TraitMath.traitsOf(shape(10, publicPct = 5.0))!!.contains("私聊"))
    }

    @Test fun `emoji is only mentioned when it is actually used`() {
        assertFalse(TraitMath.traitsOf(shape(10, emoji = 2.0))!!.contains("emoji"))
        assertTrue(TraitMath.traitsOf(shape(10, emoji = 50.0))!!.contains("emoji"))
    }

    /**
     * A player who is online at every hour of the day is not a night owl.
     *
     * `maxByOrNull` resolves a tie to the lowest index, and index 0 is 凌晨, so a 24-hour player
     * with 50 messages in every single hour was stored and read by every cat as "常在 凌晨 活跃".
     */
    @Test fun `a flat activity pattern has no peak to report`() {
        val always = BehaviorCounters()
        for (h in 0..23) repeat(50) { always.onMessage("在", true, false, h) }
        val shape = always.snapshot()
        assertNull(shape.peakHour, "a uniform histogram is not a habit")
        assertFalse(TraitMath.traitsOf(shape)!!.contains("活跃"), TraitMath.traitsOf(shape)!!)
    }

    @Test fun `a real night owl still gets the hour reported`() {
        val owl = BehaviorCounters()
        for (h in 0..23) repeat(if (h in 22..23 || h <= 1) 30 else 1) { owl.onMessage("在", true, false, h) }
        // 22, 23, 0 and 1 all tie at 30, so the first of them wins. Index 0 is 凌晨, which for
        // somebody awake at half past midnight is the correct label, not a bug.
        assertEquals(0, TraitMath.peakHourOf(owl.hourly()), "a genuine peak must survive the dominance check")
        // The label must follow the peak that was actually found, not a fixed hour: 凌晨 is the
        // correct thing to say about somebody who is up at half past midnight.
        val label = TraitMath.hourLabel(TraitMath.peakHourOf(owl.hourly())!!)
        assertTrue(TraitMath.traitsOf(owl.snapshot())!!.contains(label), "label should be $label")
    }

    @Test fun `one odd message in a quiet hour is not a habit`() {
        val c = BehaviorCounters()
        for (h in 0..23) repeat(if (h == 3) 1 else 4) { c.onMessage("在", true, false, h) }
        assertNull(TraitMath.peakHourOf(c.hourly()))
    }

    /**
     * Five messages and five thousand used to produce the identical sentence, so a player seen once
     * was described as firmly as a regular — and the description is stored, then read by every cat.
     */
    @Test fun `thin evidence is marked as thin`() {
        val thin = TraitMath.traitsOf(ChatShape(5, 12.0, 100.0, 0.0, null))!!
        val solid = TraitMath.traitsOf(ChatShape(5000, 12.0, 100.0, 0.0, null))!!
        assertTrue(thin.contains("才聊过几次"), "a first impression must be marked provisional: $thin")
        assertFalse(solid.contains("才聊过几次"), "a well-known player should not be hedged: $solid")
    }

    @Test fun `an active hour is labelled, not printed as a raw number`() {
        assertTrue(TraitMath.traitsOf(shape(10, peak = 2))!!.contains("凌晨"))
        assertTrue(TraitMath.traitsOf(shape(10, peak = 12))!!.contains("中午"))
        assertTrue(TraitMath.traitsOf(shape(10, peak = 22))!!.contains("晚上"))
        assertFalse(TraitMath.traitsOf(shape(10, peak = null))!!.contains("活跃"))
    }

    @Test fun `traits never mention conduct`() {
        val words = TraitMath.traitsOf(shape(50))!!
        for (forbidden in listOf("坏", "违规", "封禁", "处罚", "风险")) {
            assertFalse(words.contains(forbidden), "traits must describe style, not conduct: $words")
        }
    }
}

class ActionStatsTest {

    /** The counter-example that motivates this whole class. */
    @Test fun `the same number of broken blocks means opposite things`() {
        val farmer = ActionCounters().apply { repeat(10_000) { onBreak(valuable = false) } }
        val griefer = ActionCounters().apply { repeat(200) { onBreak(valuable = true, othersPlaced = true) } }
        assertEquals(10_000, farmer.brokenCommon + farmer.brokenValuable)
        assertEquals(200, griefer.brokenCommon + griefer.brokenValuable)
        // Same totals cannot tell them apart; the breakdown can.
        assertTrue(ActionStats.describe(ActionStats.shapeOf(farmer))!!.contains("普通"))
        assertTrue(ActionStats.describe(ActionStats.shapeOf(griefer))!!.contains("贵重"))
    }

    @Test fun `net building separates builders from clearers`() {
        val builder = ActionCounters().apply { repeat(300) { onPlace() } }
        val clearer = ActionCounters().apply { repeat(300) { onBreak(valuable = false) } }
        assertTrue(ActionStats.shapeOf(builder).netBuild > 0)
        assertTrue(ActionStats.shapeOf(clearer).netBuild < 0)
    }

    @Test fun `nothing is said about a player who did nothing`() {
        assertNull(ActionStats.describe(ActionStats.shapeOf(ActionCounters())))
    }

    @Test fun `valuable materials are recognised by name`() {
        for (m in listOf("DIAMOND_PICKAXE", "NETHERITE_HELMET", "OBSIDIAN", "IRON_SWORD")) {
            assertTrue(ActionStats.isValuable(m), "$m should be protected")
        }
        for (m in listOf("STONE", "OAK_LOG", "DIRT", "COBBLESTONE")) {
            assertFalse(ActionStats.isValuable(m), "$m is ordinary building material")
        }
    }

    @Test fun `without an ownership source nothing can corroborate a report`() {
        // The plan is explicit: brokenOthersPlacedValuable needs CoreProtect, so opinion alone can
        // never open a work order.
        val opinionOnly = ActionCounters().apply { repeat(10_000) { onBreak(valuable = true) } }
        assertFalse(ActionStats.shapeOf(opinionOnly).corroborates,
            "without brokenOthersPlacedValuable the corroboration gate must stay shut")
    }

    @Test fun `a confirmed pattern of breaking others' valuables does corroborate`() {
        val confirmed = ActionCounters().apply {
            repeat(60) { onBreak(valuable = true, othersPlaced = true) }
        }
        assertTrue(ActionStats.shapeOf(confirmed).corroborates)
    }

    @Test fun `own mining alone never corroborates however much of it there is`() {
        val miner = ActionCounters().apply { repeat(60) { onBreak(valuable = true) } }
        assertFalse(ActionStats.shapeOf(miner).corroborates)
    }
}

class CoScoreTest {

    /**
     * One message in each named hour, zero elsewhere.
     *
     * The index *is* the hour: `coScore` compares hour `h` of one histogram with hour `h` of the
     * other. An earlier helper took a vararg and wrote the values into slots 0, 1, 2 instead,
     * which quietly turned "these two never overlapped" into "they overlapped at midnight".
     */
    private fun active(vararg hours: Int): IntArray =
        IntArray(24).also { hist -> hours.forEach { hist[it] = 1 } }

    @Test fun `two people who never overlapped share nothing`() {
        assertEquals(0, TraitMath.coScore(active(5, 3, 2), active(1, 1, 1)))
    }

    @Test fun `the score counts shared hours, not the busiest of them`() {
        // Alice was very active all day, Bob barely: the intersection is what counts, so the score
        // stays at the small number rather than the large one.
        val alice = IntArray(24) { 20 }
        val bob = active(1)
        assertEquals(1, TraitMath.coScore(alice, bob))
    }

    @Test fun `an hour both were active in counts once`() {
        assertEquals(3, TraitMath.coScore(active(10, 11, 12), active(12, 11, 10)))
    }

    @Test fun `a player is their own best company`() {
        val self = active(1, 2, 3)
        assertEquals(3, TraitMath.coScore(self, self))
    }

    @Test fun `histograms of different lengths do not throw`() {
        assertEquals(0, TraitMath.coScore(IntArray(0), IntArray(0)))
        // Only the overlapping prefix is examined, and a short histogram says nothing about later
        // hours rather than silently reading past its own end.
        assertEquals(1, TraitMath.coScore(IntArray(1) { 1 }, IntArray(24) { 3 }))
    }
}

class ReportGuardTest {

    private val alice = java.util.UUID.randomUUID()
    private val bob = java.util.UUID.randomUUID()
    private val now = java.time.Instant.parse("2026-10-07T00:00:00Z")

    @Test fun `nobody may report themselves`() {
        assertFalse(ReportGuard.admits(alice, alice, coScore = 999))
    }

    @Test fun `a player who never shared a session cannot report`() {
        assertFalse(ReportGuard.admits(alice, bob, coScore = 0), "the Xbox rule, stated as a refusal")
        assertTrue(ReportGuard.admits(alice, bob, coScore = 1))
    }

    @Test fun `a thin shared history is stored but never counted`() {
        assertFalse(ReportGuard.trusted(ReportGuard.MIN_CO_PLAY - 1))
        assertTrue(ReportGuard.trusted(ReportGuard.MIN_CO_PLAY))
    }

    @Test fun `a coordinated spike distrusts the whole window`() {
        val window = ReportGuard.burstWindowStart(now)
        val burst = (0 until ReportGuard.BURST_THRESHOLD).map { now.minusSeconds(it * 60L) }
        assertTrue(ReportGuard.isBurst(burst, window))
        val few = listOf(now.minusSeconds(60), now.minusSeconds(120))
        assertFalse(ReportGuard.isBurst(few, window))
    }

    @Test fun `reports older than the window do not trip the check`() {
        val window = ReportGuard.burstWindowStart(now)
        val old = (0 until 20).map { now.minusSeconds(7200L + it * 60L) }
        assertFalse(ReportGuard.isBurst(old, window), "a slow trickle is not a pile-on")
    }

    @Test fun `the work order counts people, not messages`() {
        val reports = listOf(
            alice to true, alice to true, alice to true,
            bob to true,
            bob to false, // not trusted
        )
        assertEquals(2, ReportGuard.independentAuthors(reports), "three messages from Alice is one person")
    }

    @Test fun `reports and work orders both expire`() {
        val at = now
        assertTrue(ReportGuard.expiresAt(at).isAfter(at.plusSeconds(29 * 86_400)))
        assertTrue(ReportGuard.workOrderExpiresAt(at).isAfter(at.plusSeconds(13 * 86_400)))
    }
}