package com.meowo.teleport

import com.meowo.api.TaxReason
import com.meowo.platform.TravelJournal
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The `/back` fee and the record it reads.
 *
 * The fee is a pure calculation, so it is worth pinning exactly: 0.1% of the balance, never below
 * 10 and never above 233. Both ends matter — without a floor it is free for a broke player, and
 * without a ceiling a rich one can lose a fortune to a slip.
 */
class BackTravelTest {

    private val alice = UUID.randomUUID()

    // ── the fee ─────────────────────────────────────────────────────────────────────────────

    private val settings = TeleportSettings()

    @Test fun `the assessment is a tenth of a percent, floored and capped`() {
        val a = settings.backAssessment()
        assertEquals(TaxReason.BACK_TRAVEL, a.reason)
        assertEquals(BigDecimal("0.001"), a.rate)
        assertEquals(BigDecimal("10"), a.minimum)
        assertEquals(BigDecimal("233"), a.maximum)
    }

    @Test fun `a normal balance pays the percentage`() {
        // 1% would be 1,000 on 100k; a tenth of that is 100.
        assertEquals(BigDecimal("10.00"), settings.backAssessment().amount(BigDecimal("10000")))
    }

    /** The floor is what makes a rescue possible for somebody who died broke. */
    @Test fun `a poor player pays the floor, not nothing`() {
        assertEquals(BigDecimal("10.00"), settings.backAssessment().amount(BigDecimal("100")))
        assertEquals(BigDecimal("10.00"), settings.backAssessment().amount(BigDecimal("0")))
    }

    @Test fun `a rich player pays the ceiling, not a fortune`() {
        assertEquals(BigDecimal("233.00"), settings.backAssessment().amount(BigDecimal("1000000")))
    }

    @Test fun `the fee is never zero, whatever the balance`() {
        for (balance in listOf("0", "0.01", "9.99", "100", "12345.67")) {
            assertTrue(
                settings.backAssessment().amount(BigDecimal(balance)) >= BigDecimal("10"),
                "balance $balance paid less than the floor",
            )
        }
    }

    // ── what `/back` returns to ──────────────────────────────────────────────────────────────

    private fun location(x: Int) = Location(null, x.toDouble(), 64.0, 0.0)

    @Test fun `a player with nowhere to go back to is told so`() {
        assertNull(TravelJournal().destinationFor(alice))
    }

    @Test fun `a death is where the command goes, and it beats an older departure`() {
        val journal = TravelJournal()
        journal.departed(alice, location(10))
        assertEquals(10.0, journal.destinationFor(alice)!!.x)

        journal.died(alice, location(20))
        assertEquals(20.0, journal.destinationFor(alice)!!.x, "the death is the more recent event")
    }

    /** Walking off normally clears the death point, or `/back` would drag a player back hours later. */
    @Test fun `leaving after a death supersedes it`() {
        val journal = TravelJournal()
        journal.died(alice, location(20))
        journal.departed(alice, location(30))
        assertEquals(30.0, journal.destinationFor(alice)!!.x)
    }

    /**
     * Going back records where you were, so it is reversible. That permits a paid ping-pong
     * between two spots; each hop costs, which is what bounds it.
     */
    @Test fun `going back is itself reversible`() {
        val journal = TravelJournal()
        journal.departed(alice, location(10))
        val first = journal.destinationFor(alice)!!.clone()
        journal.departed(alice, location(99))
        assertEquals(10.0, first.x)
        assertEquals(99.0, journal.destinationFor(alice)!!.x, "the record moved on with the player")
    }

    /** A caller that mutates what it was handed must not corrupt the record. */
    @Test fun `the returned location is a copy`() {
        val journal = TravelJournal()
        journal.departed(alice, location(10))
        val taken = journal.destinationFor(alice)!!
        taken.x = 999.0
        assertEquals(10.0, journal.destinationFor(alice)!!.x, "the record was mutated through the copy")
    }

    @Test fun `quitting forgets the return point`() {
        val journal = TravelJournal()
        journal.died(alice, location(20))
        assertTrue(journal.has(alice))
        journal.forget(alice)
        assertNull(journal.destinationFor(alice))
    }

    /**
     * A departure with no death is the **ordinary** case — `/spawn`, `/home` and `/tpa` all record
     * one — so a `has` that only asked the death map answered "no" for almost everybody. The bug
     * was invisible because it read `containsKey(...) ?: containsKey(...)`, and `containsKey` is a
     * non-null `Boolean`, so the elvis could never fall through to the second map at all.
     */
    @Test fun `a departure on its own is still something to go back to`() {
        val journal = TravelJournal()
        journal.departed(alice, location(10))
        assertTrue(journal.has(alice), "a live departure is a return point")
    }

    @Test fun `the journal is bounded so it cannot grow forever`() {
        val journal = TravelJournal(capacity = 4)
        repeat(50) { journal.departed(UUID.randomUUID(), location(it)) }
        assertTrue(journal.trackedPlayers() <= 4, "held ${journal.trackedPlayers()}")
    }

    @Test fun `badly configured bounds are refused at start-up, not on a player's first trip`() {
        var threw = false
        try {
            TeleportSettings(backMinimum = BigDecimal("300"), backMaximum = BigDecimal("10"))
        } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw, "minimum above maximum must fail loudly")
    }
}
