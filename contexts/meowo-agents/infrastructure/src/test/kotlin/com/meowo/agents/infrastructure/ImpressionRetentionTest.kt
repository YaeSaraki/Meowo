package com.meowo.agents.infrastructure

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Retention for the **compressed** layer.
 *
 * An impression is a summary of the chat log, and this is the promise that a summary cannot outlive
 * its source: fifteen days without a login and every cat forgets what it thought of that player.
 * Before this, the table had no retention at all — `prune` only ever touched `player_report`.
 */
class ImpressionRetentionTest {

    @TempDir lateinit var dir: Path

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val cat = "economy"
    private var store: PlayerProfileStore? = null
    private var day = 1_000L

    @BeforeEach fun open() { store = PlayerProfileStore(dir.resolve("profile.db")) }

    @AfterEach fun close() { store?.close() }

    private fun daysAgo(n: Long): Long = day - TimeUnit.DAYS.toMillis(n)

    private fun write(catId: String, player: UUID, text: String, at: Long = day) = run {
        store!!.upsertImpression(StoredImpression(catId, player.toString(), text, 1, at))
    }

    private fun cutoff(days: Long): Long = daysAgo(days)

    private val present = { _: String -> day }
    private val absent = { _: String -> daysAgo(30) }

    // ── the promise ──────────────────────────────────────────────────────────────────────────

    /** Fifteen days of absence is the promise, and it has to actually delete. */
    @Test fun `a player who has not logged in for the window loses every cat's impression`() {
        write(cat, alice, "很实在")
        write("home", alice, "话不多")
        assertEquals(2, store!!.pruneImpressions(cutoff(15), absent))
        assertNull(store!!.impressionOf(cat, alice.toString()))
        assertNull(store!!.impressionOf("home", alice.toString()))
    }

    /** A player who logs in keeps it, however old the row is. */
    @Test fun `logging in keeps the impression however old the row is`() {
        write(cat, alice, "很实在", at = daysAgo(400))
        assertEquals(0, store!!.pruneImpressions(cutoff(15), present))
        assertEquals("很实在", store!!.impressionOf(cat, alice.toString())?.impression)
    }

    /**
     * The clock is the **login**, not the row.
     *
     * This is the whole difference between the two designs. Anchored on `updated_at` — which is only
     * ever rewritten when a cat refreshes its opinion — a player who logs in every day and stops
     * talking to cats would lose what the cats thought of them, while their chat is still being kept
     * in full.
     */
    @Test fun `a quiet-but-present player is not forgotten`() {
        write(cat, alice, "很实在", at = daysAgo(90))
        assertEquals(0, store!!.pruneImpressions(cutoff(15), present), "90-day-old row, logged in today")
        assertTrue(store!!.impressionsOf(alice.toString()).isNotEmpty())
    }

    @Test fun `the boundary is inclusive of the last day inside the window`() {
        write(cat, alice, "很实在")
        // Exactly on the cutoff: still inside, because `seen >= cutoff` keeps.
        assertEquals(0, store!!.pruneImpressions(day, present))
        assertEquals(1, store!!.pruneImpressions(day + 1, present), "a millisecond past it is gone")
    }

    // ── unknown is not absent ────────────────────────────────────────────────────────────────

    /**
     * The one that must not be got wrong.
     *
     * `chat_active` only knows players who joined **since the chat log existed**. Treating "no
     * record" as "never logs in" would forget everybody who joined before the log was created, and
     * forget them **silently**. Null means unknown; unknown keeps.
     */
    @Test fun `no login record means unknown, and unknown is kept`() {
        write(cat, alice, "很实在")
        assertEquals(0, store!!.pruneImpressions(cutoff(15)) { null })
        assertTrue(store!!.impressionsOf(alice.toString()).isNotEmpty(), "deleted on absence of evidence")
    }

    @Test fun `known and unknown players are handled independently`() {
        write(cat, alice, "很实在")
        write(cat, bob, "很实在")
        // Alice is unknown and must be kept; Bob is known-absent and must go.
        val seen = mapOf(bob.toString() to daysAgo(30))
        assertEquals(1, store!!.pruneImpressions(cutoff(15)) { seen[it] })
        assertNull(store!!.impressionOf(cat, bob.toString()), "known absent: deleted")
        assertTrue(store!!.impressionsOf(alice.toString()).isNotEmpty(), "unknown: kept")
    }

    // ── blast radius ─────────────────────────────────────────────────────────────────────────

    /** The player is gone, not one cat's memory of them: every row goes together. */
    @Test fun `one absent player does not take anybody else with them`() {
        write(cat, alice, "a")
        write(cat, bob, "b")
        write("home", bob, "c")
        val seen = mapOf(alice.toString() to daysAgo(40), bob.toString() to day)
        assertEquals(1, store!!.pruneImpressions(cutoff(15)) { seen[it] })
        assertNull(store!!.impressionOf(cat, alice.toString()))
        assertEquals("b", store!!.impressionOf(cat, bob.toString())?.impression)
        assertEquals("c", store!!.impressionOf("home", bob.toString())?.impression)
    }

    /** Nothing to do is not an error. */
    @Test fun `an empty table prunes to zero`() {
        assertEquals(0, store!!.pruneImpressions(cutoff(15), absent))
    }

    /** Reports keep their own 30-day window; this must not have swallowed them. */
    @Test fun `report retention is untouched`() {
        assertEquals(0, store!!.pruneImpressions(cutoff(15), absent))
        assertEquals(0, store!!.prune(day), "reports past their window are a different table's business")
    }

    // ── the listing that makes any of this checkable ─────────────────────────────────────────

    @Test fun `a player can see every cat's impression of them`() {
        write("home", alice, "话不多", at = daysAgo(3))
        write(cat, alice, "很实在", at = daysAgo(1))
        write(cat, bob, "别人的")
        val mine = store!!.impressionsOf(alice.toString())
        assertEquals(2, mine.size)
        // Newest first, so the freshest opinion is the one read first.
        assertEquals("很实在", mine.first().impression)
        assertEquals(listOf(cat, "home"), mine.map { it.cat })
    }

    @Test fun `a player with no impression lists nothing`() {
        assertTrue(store!!.impressionsOf(alice.toString()).isEmpty())
    }

    /** A row whose cat no longer exists is still visible — it is still a sentence about a person. */
    @Test fun `an impression from a cat that no longer exists is still listed`() {
        write("retired-cat", alice, "很久以前写的")
        val rows = store!!.impressionsOf(alice.toString())
        assertEquals(1, rows.size)
        assertEquals("retired-cat", rows.single().cat)
    }
}