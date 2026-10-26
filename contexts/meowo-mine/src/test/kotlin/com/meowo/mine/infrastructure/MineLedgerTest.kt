package com.meowo.mine.infrastructure

import com.meowo.mine.domain.RareKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Real SQLite against a temporary file, mirroring `SettlementRepositoryTest` in the economy context.
 * The point is that the register survives a restart, and that it stays the size it is meant to be.
 */
class MineLedgerTest {

    @TempDir lateinit var dir: Path

    private fun db() = dir.resolve("registry.db")

    @Test fun `an untouched world loads as an empty register`() {
        MineLedger(db()).use { store ->
            val ledger = store.load("world")
            assertFalse(ledger.isDiscovered(RareKind.DIAMOND))
            assertEquals(0, ledger.totalOf(RareKind.DIAMOND))
            assertEquals(0, store.count())
        }
    }

    @Test fun `a recorded find survives reopening the file`() {
        MineLedger(db()).use { it.record("world", RareKind.DIAMOND, 1, "steve", 1L) }
        MineLedger(db()).use { reopened ->
            val ledger = reopened.load("world")
            assertTrue(ledger.isDiscovered(RareKind.DIAMOND))
            assertEquals(1, ledger.totalOf(RareKind.DIAMOND))
            assertEquals("steve", ledger.firstFinder(RareKind.DIAMOND))
        }
    }

    @Test fun `each world keeps its own register`() {
        MineLedger(db()).use { it.record("world_nether", RareKind.NETHERITE, 1, "alex", 1L) }
        MineLedger(db()).use { store ->
            assertTrue(store.load("world_nether").isDiscovered(RareKind.NETHERITE))
            assertFalse(store.load("world").isDiscovered(RareKind.NETHERITE), "the overworld must be unaffected")
        }
    }

    @Test fun `surface and deepslate are stored as separate rows`() {
        MineLedger(db()).use {
            it.record("world", RareKind.DIAMOND, 1, "steve", 1L)
            it.record("world", RareKind.DEEPSLATE_DIAMOND, 1, "alex", 1L)
        }
        MineLedger(db()).use { store ->
            val ledger = store.load("world")
            assertEquals(2, store.count(), "one row per kind, never folded together")
            assertEquals(1, ledger.totalOf(RareKind.DIAMOND))
            assertEquals(1, ledger.totalOf(RareKind.DEEPSLATE_DIAMOND))
            assertEquals("alex", ledger.firstFinder(RareKind.DEEPSLATE_DIAMOND))
        }
    }

    @Test fun `repeating a find accumulates instead of creating rows`() {
        MineLedger(db()).use {
            it.record("world", RareKind.EMERALD, 1, "steve", 1L)
            it.record("world", RareKind.EMERALD, 4, "steve", 2L)
        }
        MineLedger(db()).use { store ->
            assertEquals(1, store.count(), "the primary key must collapse repeats into one row")
            assertEquals(5, store.load("world").totalOf(RareKind.EMERALD))
        }
    }

    @Test fun `a later find never steals the first finder`() {
        MineLedger(db()).use {
            it.record("world", RareKind.DIAMOND, 1, "alex", 1L)
            it.record("world", RareKind.DIAMOND, 1, "steve", 2L)
        }
        MineLedger(db()).use { store ->
            assertEquals("alex", store.load("world").firstFinder(RareKind.DIAMOND))
        }
    }

    @Test fun `an unknown kind written by a newer build is skipped rather than failing the load`() {
        MineLedger(db()).use { it.record("world", RareKind.DIAMOND, 1, "steve", 1L) }
        DriverManager.getConnection("jdbc:sqlite:${db().toAbsolutePath()}").use { c ->
            c.createStatement().use { it.execute("INSERT INTO rare_material VALUES ('world','UNOBTANIUM',1,'x',1)") }
        }
        MineLedger(db()).use { store ->
            val ledger = store.load("world")
            assertTrue(ledger.isDiscovered(RareKind.DIAMOND), "known rows must still load")
            assertEquals(1, ledger.totalOf(RareKind.DIAMOND))
        }
    }

    @Test fun `a non positive amount is rejected before it reaches the table`() {
        MineLedger(db()).use { store ->
            assertThrows(IllegalArgumentException::class.java) {
                store.record("world", RareKind.DIAMOND, 0, "steve", 1L)
            }
            assertEquals(0, store.count(), "a rejected write must not leave a row behind")
        }
    }

    @Test fun `the register is bounded by the number of kinds, not by how long the server ran`() {
        // This is the property that answers "will it grow forever": one row per kind per world.
        MineLedger(db()).use { store ->
            repeat(500) { store.record("world", RareKind.DIAMOND, 1, "steve", it.toLong()) }
            for (kind in RareKind.entries) store.record("world", kind, 1, "steve", 1L)
            assertEquals(RareKind.entries.size, store.count(), "500 digs and 5 kinds is still 5 rows")
        }
    }

    @Test fun `reopening never destroys records`() {
        MineLedger(db()).use { it.record("world", RareKind.DIAMOND, 1, "steve", 1L) }
        MineLedger(db()).use { it.record("world", RareKind.EMERALD, 1, "steve", 1L) }
        MineLedger(db()).use { store ->
            assertEquals(2, store.count())
            assertTrue(store.load("world").isDiscovered(RareKind.DIAMOND), "older rows survive")
        }
    }
}
