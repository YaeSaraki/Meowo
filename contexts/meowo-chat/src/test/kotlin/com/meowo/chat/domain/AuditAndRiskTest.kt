package com.meowo.chat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** The bounded audit window and the decaying risk score the AI reads on the chat path. */
class AuditAndRiskTest {

    private fun msg(text: String, player: UUID = UUID.randomUUID()) =
        ChatMessage(player, "global", text, System.currentTimeMillis())

    @Test fun `the audit window is bounded and drops the oldest first`() {
        val buffer = AuditBuffer(capacity = 16)
        repeat(100) { buffer.record(msg("m$it")) }
        assertEquals(16, buffer.size(), "an unbounded log would be a memory leak with extra steps")
        assertTrue(buffer.history(null, 1).first().rawText == "m99", "newest first")
        assertTrue(buffer.history(null, 5).first().rawText == "m99")
    }

    @Test fun `history can be scoped to one player`() {
        val buffer = AuditBuffer(capacity = 64)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        buffer.record(msg("from a", a))
        buffer.record(msg("from b", b))
        assertEquals(1, buffer.history(a, 10).size)
        assertEquals("from a", buffer.history(a, 10).single().rawText)
        assertEquals(2, buffer.history(null, 10).size)
    }

    @Test fun `a nonsensical capacity is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { AuditBuffer(capacity = 1) }
    }

    @Test fun `risk is clamped to the ceiling and floored at zero`() {
        val risk = RiskRegistry(ceiling = 100)
        val id = UUID.randomUUID()
        risk.set(id, 5_000)
        assertEquals(100, risk.risk(id))
        risk.set(id, -10)
        assertEquals(0, risk.risk(id))
    }

    @Test fun `risk decays on its own so a past offence is not permanent`() {
        val risk = RiskRegistry(decayMillis = 1_000)
        val id = UUID.randomUUID()
        risk.set(id, 3)
        Thread.sleep(1_100)
        assertTrue(risk.risk(id) < 3, "risk must fall on its own")
    }

    @Test fun `bumping accumulates and forgetting clears`() {
        val risk = RiskRegistry()
        val id = UUID.randomUUID()
        risk.bump(id, 10); risk.bump(id, 5)
        assertEquals(15, risk.risk(id))
        risk.forget(id)
        assertEquals(0, risk.risk(id))
    }

    /**
     * Two judgements arriving together must both count.
     *
     * The 风纪 side writes the score from its own thread while the gate reads it from the chat
     * path, and the update used to be a read, an addition and a write — three steps with nothing
     * holding them together. Two updates that overlapped lost one of them, so a player judged
     * twice was judged once.
     */
    @Test fun `concurrent judgements do not lose an update`() {
        val risk = RiskRegistry()
        val id = UUID.randomUUID()
        val threads = 16
        val perThread = 100
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = (1..threads).map {
            Thread {
                start.await()
                repeat(perThread) { risk.bump(id, 1) }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(10_000) }
        assertEquals(threads * perThread, risk.risk(id), "an update was lost")
    }

    /** A score that falls to zero is no score at all. */
    @Test fun `a bump down to zero removes the entry`() {
        val risk = RiskRegistry()
        val id = UUID.randomUUID()
        risk.bump(id, 5)
        risk.bump(id, -5)
        assertEquals(0, risk.risk(id))
    }
}
