package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

class GuardTest {

    private fun ready(
        idle: Boolean = true,
        cooldown: Boolean = false,
        quota: Boolean = true,
        quiet: Boolean = false,
        enabled: Boolean = true,
        public: Boolean = true,
        others: Int = 5,
    ) = Guard.proactive(
        roll = 0.0, idle = idle, inCooldown = cooldown, proactiveQuotaLeft = quota,
        quiet = quiet, enabled = enabled, public = public, othersOnline = others,
    )

    @Test fun `a proactive message fires when everything is ready`() {
        assertTrue(ready())
    }

    @Test fun `the 5% boundary is exact and never sampled`() {
        fun fires(roll: Double) = Guard.proactive(
            roll, idle = true, inCooldown = false, proactiveQuotaLeft = true,
            quiet = false, enabled = true, public = true, othersOnline = 5,
        )
        assertTrue(fires(0.0), "0.0 is inside the 5%")
        assertTrue(fires(0.049), "just under the threshold fires")
        assertFalse(fires(0.05), "the threshold itself does not fire")
        assertFalse(fires(0.051), "just over the threshold does not fire")
        assertFalse(fires(0.9), "an ordinary roll must not fire either")
    }

    @Test fun `a busy agent never speaks`() {
        assertFalse(ready(idle = false), "proactive chatter must not compete with a real question")
        assertEquals(0.05, Guard.PROACTIVE_ROLL, "the documented rate")
    }

    @Test fun `an opted-out player is never spoken to unprompted`() {
        assertFalse(ready(quiet = true))
    }

    @Test fun `an exhausted proactive budget is silent rather than a fallback message`() {
        assertFalse(ready(quota = false))
    }

    @Test fun `a cooldown suppresses chatter but never a direct question`() {
        assertFalse(ready(cooldown = true))
        // Cooldown applies to the roll only; Guard has no path that turns it into an error.
    }

    @Test fun `public chatter needs someone else to hear it`() {
        assertFalse(ready(public = true, others = 0), "no audience, no deterrent, only resentment")
        assertTrue(ready(public = false, others = 0), "a private reply needs no audience")
    }

    @Test fun `the feature can be switched off entirely`() {
        assertFalse(ready(enabled = false))
    }

    @Test fun `the daily budget always leaves four fifths for real questions`() {
        for (cap in listOf(0, 1, 7, 100, 499, 500, 1000)) {
            val (proactive, questions) = Guard.quotaSplit(cap)
            assertTrue(proactive + questions == cap, "the split must not lose or invent calls")
            // At least four fifths for the questions, and the rounding stays on their side.
            //
            // These two inequalities are what pin the rounding: a split that rounded the other way
            // gives 100/399 for a cap of 499 and fails the first. "Questions are not fewer than
            // proactive" is satisfied by every split, including one that gave chatter half the
            // budget, so it proved nothing.
            assertTrue(questions * 5 >= cap * 4, "cap $cap: the questions must get at least four fifths")
            assertTrue(proactive * 5 <= cap, "cap $cap: chatter must never round up past a fifth")
        }
        assertEquals(100 to 400, Guard.quotaSplit(500))
        assertEquals(0 to 0, Guard.quotaSplit(0))
    }

    @Test fun `delegation follows a declared edge when the model names a target`() {
        val current = CatRegistry.byId("economy")!!
        assertEquals("system", Guard.nextDelegate(current, "system", setOf("economy"), Random(1)))
    }

    @Test fun `delegation refuses to loop or to hand off to itself`() {
        val economy = CatRegistry.byId("economy")!!
        val random = Random(7)
        assertNull(Guard.nextDelegate(economy, "economy", setOf("economy"), random), "self-delegation is not a delegation")
        assertNull(Guard.nextDelegate(economy, "system", setOf("economy", "system"), random), "a visited cat would loop")
        // An unknown name resolves to the system cat by design, so a hallucinated target is
        // handed to the one cat allowed to answer, rather than being dropped.
        assertEquals("system", Guard.nextDelegate(economy, "nobody", setOf("economy"), random))
    }

    @Test fun `delegation stops at the depth cap`() {
        val deep = setOf("a", "b", "c")
        assertNull(Guard.nextDelegate(CatRegistry.byId("economy")!!, "system", deep, Random(1)))
        assertEquals(2, Guard.MAX_DELEGATIONS)
    }

    @Test fun `an unnamed delegation picks among unvisited options deterministically`() {
        val economy = CatRegistry.byId("economy")!!
        val picked = Guard.nextDelegate(economy, null, setOf("economy"), Random(42))
        assertEquals("system", picked, "a seeded RNG makes this assertion exact rather than flaky")
        assertNull(Guard.nextDelegate(economy, null, setOf("economy", "system"), Random(42)))
    }

    @Test fun `the subject opting out is absolute, even for an admin`() {
        val asker = UUID.randomUUID()
        val subject = UUID.randomUUID()
        val verdict = Redaction.verdict(asker, subject, subjectProfilingEnabled = false, askerIsAdmin = true, sharing = Guard.Sharing.OPEN)
        assertTrue(verdict is Redaction.Verdict.Refused, "an opt-out is not overridable by permission")
    }

    @Test fun `the subject always reads their own profile`() {
        val me = UUID.randomUUID()
        val verdict = Redaction.verdict(me, me, subjectProfilingEnabled = true, askerIsAdmin = false, sharing = Guard.Sharing.OFF)
        assertEquals(Redaction.Scope.SELF, (verdict as Redaction.Verdict.Allowed).scope)
    }

    @Test fun `sharing is off means nobody else can look`() {
        val asker = UUID.randomUUID()
        val subject = UUID.randomUUID()
        val verdict = Redaction.verdict(asker, subject, subjectProfilingEnabled = true, askerIsAdmin = false, sharing = Guard.Sharing.OFF)
        assertTrue(verdict is Redaction.Verdict.Refused)
    }

    @Test fun `consent sharing gives others a summary, never detail`() {
        val verdict = Redaction.verdict(
            UUID.randomUUID(), UUID.randomUUID(), subjectProfilingEnabled = true,
            askerIsAdmin = false, sharing = Guard.Sharing.CONSENT,
        )
        assertEquals(Redaction.Scope.SUMMARY, (verdict as Redaction.Verdict.Allowed).scope)
    }

    @Test fun `only an admin ever reaches full detail`() {
        val verdict = Redaction.verdict(
            UUID.randomUUID(), UUID.randomUUID(), subjectProfilingEnabled = true,
            askerIsAdmin = true, sharing = Guard.Sharing.CONSENT,
        )
        assertEquals(Redaction.Scope.FULL, (verdict as Redaction.Verdict.Allowed).scope)
    }

    @Test fun `evaluative fields can never reach a prompt`() {
        val prompt = Redaction.promptableFacts(
            mapOf(
                "traits" to "话少",
                "soft_hits" to "3",
                "risk" to "12",
                "punishment" to "banned",
            ),
        )
        assertEquals(setOf("traits"), prompt.keys)
        assertFalse(prompt.containsKey("soft_hits"), "soft_hits must never be promptable")
    }

    @Test fun `a cooldown window expires rather than sticking`() {
        val now = 10_000L
        assertEquals(0L, Guard.cooldownLeft(lastAt = 0L, now = now, cooldownMillis = 3_000L), "never asked yet")
        assertEquals(1_000L, Guard.cooldownLeft(lastAt = 8_000L, now = now, cooldownMillis = 3_000L))
        assertEquals(0L, Guard.cooldownLeft(lastAt = 6_000L, now = now, cooldownMillis = 3_000L), "the window has passed")
    }
}