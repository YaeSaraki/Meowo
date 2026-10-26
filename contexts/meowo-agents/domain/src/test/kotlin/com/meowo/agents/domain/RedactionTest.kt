package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The rule that decides **whose profile a cat may read**, pinned case by case.
 *
 * This had no test at all while a *dead copy* of it sat in `Guard` with a full KDoc explaining the
 * rules. The copy was never called — not by the runtime, not by any test — and it disagreed with the
 * live one in three places. Nobody noticed, because the documentation was on the wrong function.
 *
 * So the test lives here, on the function `AgentsContext.disclose` actually calls.
 *
 * ## What the KDoc promised and the code has to keep
 *
 * - The subject's opt-out is **absolute**: checked before permission, so an admin cannot override a
 *   player who said no. Not even for themselves — a player who turned profiling off gets nothing back.
 * - Nobody but the subject ever sees detail without admin, and `FULL` is the admin's alone.
 */
class RedactionTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    private fun verdict(
        asker: UUID = bob,
        subject: UUID = alice,
        profiling: Boolean = true,
        admin: Boolean = false,
        sharing: Guard.Sharing = Guard.Sharing.OFF,
    ) = Redaction.verdict(asker, subject, profiling, admin, sharing)

    private fun allowed(v: Redaction.Verdict) = v as Redaction.Verdict.Allowed

    // ── yourself ─────────────────────────────────────────────────────────────────────────────

    @Test fun `your own profile is yours when you are sharing`() {
        assertEquals(Redaction.Scope.SELF, allowed(verdict(asker = alice, subject = alice)).scope)
    }

    /**
     * The case the dead `Guard.mayReadProfile` got wrong.
     *
     * It read `subjectProfiling || true`, which is `true` whatever the flag says — so a player who
     * had switched profiling off would have kept reading their own record through it. That branch was
     * unreachable, so nothing caught it. If a second implementation of this rule ever reappears, this
     * assertion is the thing it has to agree with.
     */
    @Test fun `opting out removes even your own record`() {
        val v = verdict(asker = alice, subject = alice, profiling = false)
        assertEquals(Redaction.Verdict.Refused(Redaction.OPTED_OUT_REFUSAL), v)
    }

    // ── somebody else ────────────────────────────────────────────────────────────────────────

    @Test fun `the opt-out is absolute even for an admin`() {
        assertEquals(
            Redaction.Verdict.Refused(Redaction.OPTED_OUT_REFUSAL),
            verdict(admin = true, profiling = false),
            "an admin must not be able to read a player who said no",
        )
    }

    @Test fun `sharing off means somebody else is refused`() {
        val v = verdict(asker = bob, subject = alice, admin = false, sharing = Guard.Sharing.OFF)
        assertEquals(Redaction.Verdict.Refused(Redaction.NOT_ALLOWED_REFUSAL), v)
    }

    /** With sharing off an admin still gets in — the server has to be able to run a discipline case. */
    @Test fun `an admin gets full detail when sharing is off`() {
        assertEquals(Redaction.Scope.FULL, allowed(verdict(admin = true, sharing = Guard.Sharing.OFF)).scope)
    }

    @Test fun `an open profile is visible to anyone as a summary only`() {
        val v = verdict(asker = bob, subject = alice, admin = false, sharing = Guard.Sharing.OPEN)
        assertEquals(Redaction.Scope.SUMMARY, allowed(v).scope, "open must never mean full for a bystander")
    }

    @Test fun `consent behaves like open for the outcome`() {
        assertEquals(
            allowed(verdict(asker = bob, subject = alice, sharing = Guard.Sharing.OPEN)).scope,
            allowed(verdict(asker = bob, subject = alice, sharing = Guard.Sharing.CONSENT)).scope,
        )
    }

    /**
     * The invariant rather than a per-sharing table: a bystander is either refused or gets a summary,
     * and **never** detail, under any sharing setting.
     *
     * Written this way because the first version asserted "always SUMMARY", which is wrong —
     * `sharing = OFF` legitimately refuses outright — and it failed for the right reason. The
     * property worth protecting is the upper bound, not the exact answer.
     */
    @Test fun `detail is the admin's alone`() {
        for (sharing in Guard.Sharing.entries) {
            val v = verdict(asker = bob, subject = alice, admin = false, sharing = sharing)
            if (v is Redaction.Verdict.Allowed) {
                assertEquals(Redaction.Scope.SUMMARY, v.scope, "a bystander got detail with sharing=$sharing")
            }
        }
    }

    /** And the converse, so the loop above cannot pass by everything being refused. */
    @Test fun `an admin is not refused by a sharing setting`() {
        for (sharing in Guard.Sharing.entries) {
            val v = verdict(asker = bob, subject = alice, admin = true, sharing = sharing)
            assertTrue(v is Redaction.Verdict.Allowed, "an admin was refused with sharing=$sharing: $v")
        }
    }

    // ── what may reach a prompt ──────────────────────────────────────────────────────────────

    /**
     * `soft_hits` staying inside the discipline cat is the line between "a profile" and "a
     * punishment record", so it is pinned rather than left to the reader of a filter call.
     */
    @Test fun `evaluative fields never reach a prompt`() {
        for (field in listOf("soft_hits", "risk", "muteUntil", "punishment")) {
            assertTrue(field in NEVER_IN_PROMPT, "$field must never be promptable")
            val facts = Redaction.promptableFacts(mapOf("topics" to "x", field to "y"))
            assertFalse(facts.containsKey(field), "$field leaked into a prompt: $facts")
            assertEquals(setOf("topics"), facts.keys, "the safe fields should have survived")
        }
    }

    @Test fun `a prompt carries names and never uuids`() {
        assertEquals("Alice, Bob", Redaction.forPrompt(mapOf(alice to "Alice", bob to "Bob")))
    }
}
