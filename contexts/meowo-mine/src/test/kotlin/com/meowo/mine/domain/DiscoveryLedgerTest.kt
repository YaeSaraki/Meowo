package com.meowo.mine.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The register, tested without a server — it is a value object precisely so these rules can be
 * proved this way.
 */
class DiscoveryLedgerTest {

    /** Records a find and asserts it was accepted, so tests can chain `.next` directly. */
    private fun record(ledger: DiscoveryLedger, kind: RareKind, amount: Int = 1, who: String = "steve") =
        requireNotNull(ledger.record(kind, amount, who)) { "$kind/$amount should be a valid find" }

    @Test fun `every find is reported, not only the first`() {
        val first = record(DiscoveryLedger(), RareKind.DIAMOND)
        val second = record(first!!.next, RareKind.DIAMOND)
        assertNotNull(second, "a second diamond must still be reported")
        assertEquals(1, second.amount)
        assertFalse(second.isFirst)
    }

    @Test fun `the first find is flagged so the broadcast can single it out`() {
        val first = record(DiscoveryLedger(), RareKind.DIAMOND)
        assertTrue(first.isFirst)
        val second = record(first.next, RareKind.DIAMOND)
        assertFalse(second.isFirst, "only the first is a first")
    }

    @Test fun `deepslate and surface ore are tracked separately`() {
        val ledger = record(DiscoveryLedger(), RareKind.DIAMOND).next
        val deep = record(ledger, RareKind.DEEPSLATE_DIAMOND)
        assertTrue(deep.isFirst, "deepslate has never been seen, so it is a first of its own kind")
        assertEquals(1, ledger.totalOf(RareKind.DIAMOND))
        assertEquals(1, deep.next.totalOf(RareKind.DEEPSLATE_DIAMOND))
        assertFalse(ledger.isDiscovered(RareKind.DEEPSLATE_DIAMOND), "they must not share a count")
    }

    @Test fun `each of the five kinds is discovered independently`() {
        var ledger = DiscoveryLedger()
        for (kind in RareKind.entries) {
            ledger = record(ledger, kind).next
        }
        assertEquals(5, ledger.discovered.size)
        assertEquals(5, ledger.totals.size)
    }

    @Test fun `a non positive amount is rejected and changes nothing`() {
        val ledger = DiscoveryLedger()
        // Calls the ledger directly: the `record` helper asserts acceptance, which is the opposite
        // of what this case needs to prove.
        assertNull(ledger.record(RareKind.DIAMOND, 0, "steve"))
        assertNull(ledger.record(RareKind.DIAMOND, -3, "steve"))
        assertFalse(ledger.isDiscovered(RareKind.DIAMOND))
        assertEquals(0, ledger.totalOf(RareKind.DIAMOND))
    }

    @Test fun `totals accumulate across finds`() {
        var ledger = DiscoveryLedger()
        ledger = record(ledger, RareKind.EMERALD, 3).next
        ledger = record(ledger, RareKind.EMERALD, 4).next
        assertEquals(7, ledger.totalOf(RareKind.EMERALD))
    }

    @Test fun `the first finder is remembered and is never stolen by a later find`() {
        var ledger = record(DiscoveryLedger(), RareKind.DIAMOND, 1, "alex").next
        ledger = record(ledger, RareKind.DIAMOND, 1, "steve").next
        assertEquals("alex", ledger.firstFinder(RareKind.DIAMOND), "a later find must not overwrite it")
    }

    @Test fun `an untouched kind reports zero and no owner instead of failing`() {
        val ledger = DiscoveryLedger()
        assertEquals(0, ledger.totalOf(RareKind.EMERALD))
        assertNull(ledger.firstFinder(RareKind.EMERALD))
        assertFalse(ledger.isDiscovered(RareKind.EMERALD))
    }

    @Test fun `a fresh ledger does not share state with one that already discovered something`() {
        val spent = record(DiscoveryLedger(), RareKind.DIAMOND).next
        assertTrue(spent.isDiscovered(RareKind.DIAMOND))
        assertFalse(DiscoveryLedger().isDiscovered(RareKind.DIAMOND), "register state must not be global")
    }
}
