package com.meowo.agents.infrastructure

import com.meowo.agents.domain.Sentiment
import com.meowo.agents.domain.WorkOrderLevel
import com.meowo.agents.domain.WorkOrderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Real SQLite, real file, no mocks — the point of these is that the *schema* is the guarantee, and
 * a schema cannot be checked with a hand-written fake (docs/minimax/08 §19, grade B).
 */
class PlayerProfileStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun path(name: String = "agents.db"): Path = dir.resolve(name)

    private fun store(name: String = "agents.db"): PlayerProfileStore = PlayerProfileStore(path(name))

    private fun traits(
        player: String = "alice",
        samples: Int = 10,
        peak: Int? = 20,
        softHits: Int = 0,
        text: String? = "句子不长，几乎都在公频说话。",
        updated: Long = 1_000L,
        created: Long = 500L,
    ) = StoredTraits(player, samples, 18.5, 90.0, 5.0, peak, softHits, text, updated, created)

    private fun order(
        id: String,
        subject: String,
        level: WorkOrderLevel = WorkOrderLevel.RECORDED,
    ) = StoredWorkOrder(
        id = id, subject = subject, level = level, state = WorkOrderState.OPEN,
        createdAt = 0L, updatedAt = 0L, expiresAt = Long.MAX_VALUE, lastLevelAt = null,
        authors = 1, reason = "test",
    )

    private fun report(
        id: String = "r1",
        author: String = "bob",
        subject: String = "alice",
        sentiment: Sentiment = Sentiment.NEGATIVE,
        reason: String = "偷了我的箱子",
        at: Long = 2_000L,
        expires: Long = 3_000L,
        co: Int = 12,
        trusted: Int = 1,
        state: String = "OPEN",
    ) = StoredReport(id, author, subject, sentiment, reason, at, expires, co, trusted, state)

    // ── the guarantee that cannot be tested any other way ─────────────────────────────────────

    /**
     * The most important assertion in this file.
     *
     * Player reports may open a work order. They may never raise a sanction. Everywhere else that
     * is a promise; here it is a fact about the table, read from the live schema by a connection
     * that knows nothing about the code under test. If somebody adds the column anyway, this fails
     * and the failure is self-explanatory.
     */
    @Test fun `the report table has nowhere to put a punishment`() {
        store().use { it.upsertTraits(traits()) } // force the schema to exist
        val columns = columnsOf(path(), "player_report")
        assertFalse(
            columns.any { it.contains("punish") || it.contains("sanction") || it.contains("ban") },
            "player_report gained an evaluative column: $columns",
        )
        assertTrue(
            columns.containsAll(listOf("id", "author", "subject", "sentiment", "reason", "state")),
            "the columns a report does need are missing: $columns",
        )
    }

    @Test fun `a player with no peak hour stores a null rather than a zero`() {
        store().use { s ->
            s.upsertTraits(traits(peak = null))
            assertNull(s.traitsOf("alice")!!.peakHour, "hour 0 is a real hour and must not stand in for 'unknown'")
        }
    }

    /** Traits are recomputed constantly; `created_at` must survive that, or the row has no age. */
    @Test fun `an update replaces the numbers but keeps the original creation time`() {
        store().use { s ->
            s.upsertTraits(traits(samples = 10, updated = 1_000L, created = 500L))
            s.upsertTraits(traits(samples = 99, peak = 3, softHits = 4, updated = 2_000L, created = 9_999L))
            val back = s.traitsOf("alice")!!
            assertEquals(99, back.samples)
            assertEquals(3, back.peakHour)
            assertEquals(
                0, back.softHits,
                "the factual writer must not set the discipline column; that column is the " +
                    "discipline cat's, and writing it here is what reset the 风纪 score to zero " +
                    "on every flush",
            )
            assertEquals(2_000L, back.updatedAt)
            assertEquals(500L, back.createdAt, "created_at is the first sighting, not the last write")
            assertEquals(1, s.count("traits"))
        }
    }

    /** The other half of the same contract: what the discipline side wrote, the factual side keeps. */
    @Test fun `a counter written by the discipline side survives a factual rewrite`() {
        store().use { s ->
            s.upsertTraits(traits(samples = 10))
            s.bumpSoftHits("alice", 3, 1_500L)
            assertEquals(3, s.softHitsOf("alice"))
            s.upsertTraits(traits(samples = 99, updated = 2_000L))
            assertEquals(3, s.softHitsOf("alice"), "a profile flush must not erase the counter")
        }
    }

    /** A complaint can arrive before anybody has described the player. */
    @Test fun `a complaint counts even when the player has no profile row yet`() {
        store().use { s ->
            s.bumpSoftHits("ghost", 1, 1_000L)
            assertEquals(1, s.softHitsOf("ghost"), "an UPDATE against a missing row updates nothing")
            assertEquals(1, s.traitsOf("ghost")!!.softHits)
        }
    }

    // ── impressions: one row per cat per player ───────────────────────────────────────────────

    @Test fun `each cat keeps its own separate impression of the same player`() {
        store().use { s ->
            s.upsertImpression(StoredImpression("MeowCat", "alice", "话不多，但每次都说到点上", 1, 1_000L))
            s.upsertImpression(StoredImpression("EconCat", "alice", "每次路过都看余额", 1, 1_000L))
            assertEquals("话不多，但每次都说到点上", s.impressionOf("MeowCat", "alice")!!.impression)
            assertEquals("每次路过都看余额", s.impressionOf("EconCat", "alice")!!.impression)
            assertNull(s.impressionOf("MineCat", "alice"), "a cat that has not met them has no opinion")
        }
    }

    @Test fun `a second meeting accumulates notes instead of overwriting the count`() {
        store().use { s ->
            s.upsertImpression(StoredImpression("MeowCat", "alice", "第一次", 1, 1_000L))
            s.upsertImpression(StoredImpression("MeowCat", "alice", "第二次", 1, 2_000L))
            s.upsertImpression(StoredImpression("MeowCat", "alice", "第三次", 1, 3_000L))
            val back = s.impressionOf("MeowCat", "alice")!!
            assertEquals(3, back.noteCount, "the threshold for trusting a cat's impression counts meetings")
            assertEquals("第三次", back.impression)
            assertEquals(3_000L, back.updatedAt)
        }
    }

    @Test fun `being forgotten clears every cat at once`() {
        store().use { s ->
            s.upsertImpression(StoredImpression("MeowCat", "alice", "a", 1, 1L))
            s.upsertImpression(StoredImpression("EconCat", "alice", "b", 1, 1L))
            s.upsertImpression(StoredImpression("MeowCat", "bob", "c", 1, 1L))
            assertEquals(2, s.forgetImpressions("alice"))
            assertEquals(1, s.count("impressions"), "only Bob's row survives")
            assertNotNull(s.impressionOf("MeowCat", "bob"))
        }
    }

    // ── reports ──────────────────────────────────────────────────────────────────────────────

    @Test fun `an expired report is invisible to everyone including the subject`() {
        store().use { s ->
            s.insertReport(report(id = "live", expires = 5_000L))
            s.insertReport(report(id = "dead", expires = 500L))
            assertEquals(listOf("live"), s.reportsAbout("alice", now = 1_000L).map { it.id })
        }
    }

    @Test fun `a report round-trips with its evidence intact`() {
        store().use { s ->
            s.insertReport(report(sentiment = Sentiment.POSITIVE, reason = "帮忙修了农场", co = 30, trusted = 1))
            val back = s.reportsAbout("alice", now = 0L).single()
            assertEquals(Sentiment.POSITIVE, back.sentiment)
            assertEquals("帮忙修了农场", back.reason)
            assertEquals(30, back.coScore, "co-presence is kept so the claim can be re-judged later")
            assertEquals(1, back.trusted)
        }
    }

    @Test fun `a human outcome is recorded on the report`() {
        store().use { s ->
            s.insertReport(report())
            assertEquals(1, s.setReportState("r1", "DISMISSED"))
            assertEquals("DISMISSED", s.reportsAbout("alice", now = 0L).single().state)
            assertEquals(0, s.setReportState("no-such-report", "UPHELD"), "updating nothing changes nothing")
        }
    }

    @Test fun `pruning removes the expired and keeps the rest`() {
        store().use { s ->
            repeat(5) { s.insertReport(report(id = "old$it", expires = 1_000L)) }
            repeat(3) { s.insertReport(report(id = "new$it", expires = 9_000L)) }
            assertEquals(5, s.prune(now = 2_000L))
            assertEquals(3, s.count("reports"))
        }
    }

    // ── the right to be forgotten ────────────────────────────────────────────────────────────

    /** What belongs to the player goes: their impressions, their traits, and the reports they filed. */
    @Test fun `a player's own memory is deleted`() {
        store().use { s ->
            s.upsertTraits(traits(player = "alice"))
            s.upsertTraits(traits(player = "bob"))
            s.upsertImpression(StoredImpression("MeowCat", "alice", "a", 1, 1L))
            s.insertReport(report(id = "by-alice", author = "alice", subject = "carol"))
            s.insertReport(report(id = "neither", author = "bob", subject = "carol"))

            s.forgetAll("alice")

            assertNull(s.traitsOf("alice"))
            assertNotNull(s.traitsOf("bob"), "a bystander's facts are not collateral")
            assertNull(s.impressionOf("MeowCat", "alice"))
            assertEquals(listOf("neither"), s.reportsAbout("carol", now = 0L).map { it.id },
                "only the report between two other people should remain")
        }
    }

    /**
     * The one that is easy to get wrong in the direction of being polite.
     *
     * A report written by somebody else is *their* speech about this player, and it lands unread
     * (`state = "OPEN"`). Deleting it here would mean a player who has just been complained about can
     * type their own name and make the accusation — and the ladder it would have climbed — disappear.
     * So the row stays, marked, and stops counting for anything.
     */
    @Test fun `a report somebody else filed is marked out of use, not deleted`() {
        store().use { s ->
            s.insertReport(report(id = "about-alice", author = "bob", subject = "alice"))

            val forgot = s.forgetAll("alice")

            assertEquals(1, forgot.reportsMarked)
            assertTrue(forgot.anythingKept, "and the command has to say something was kept")
            assertEquals(1, s.count("reports"), "the row is still on disk — this is a marking, not a wipe")
            assertTrue(s.reportsAbout("alice", now = 0L).isEmpty(), "and it must no longer count as an accusation")
        }
    }

    /**
     * The rate limit counts an author's conduct. If a marked report stopped counting there, a
     * subject asking to be forgotten would hand the author a fresh quota — and a limit a third party
     * can reset is not a limit.
     */
    @Test fun `forgetting does not reset the filing limit`() {
        store().use { s ->
            s.insertReport(report(id = "about-alice", author = "bob", subject = "alice"))
            s.forgetAll("alice")

            assertEquals(1, s.reportsByAuthorSince("bob", "alice", since = 0L), "the author's record is not laundered")
            assertEquals(0, s.reportsByAuthorSince("bob", "alice", since = 9_000L), "and it still expires with the window")
        }
    }

    /** `work_order` is the moderation side's record: it leaves the ladder, it is not erased from history. */
    @Test fun `forgetting a player takes their work orders out of the ladder`() {
        store().use { s ->
            s.createWorkOrder(order("o-alice", "alice", WorkOrderLevel.NOTIFIED))
            s.createWorkOrder(order("o-alice-2", "alice", WorkOrderLevel.RECORDED))
            s.createWorkOrder(order("o-bob", "bob", WorkOrderLevel.NOTIFIED))

            val forgot = s.forgetAll("alice")

            assertEquals(2, forgot.workOrdersMarked)
            assertEquals(listOf("o-bob"), s.openWorkOrders(now = 0L).map { it.id },
                "another player's case is not collateral, and a forgotten one leaves the ladder")
        }
    }

    /** Marking twice is not marking twice; the number the player is shown has to be honest. */
    @Test fun `forgetting twice does not count the same rows again`() {
        store().use { s ->
            s.insertReport(report(id = "about-alice", author = "bob", subject = "alice"))
            s.createWorkOrder(order("o-alice", "alice", WorkOrderLevel.RECORDED))

            s.forgetAll("alice")
            val second = s.forgetAll("alice")

            assertEquals(0, second.reportsMarked, "already marked; claiming otherwise would inflate the reply")
            assertEquals(0, second.workOrdersMarked)
        }
    }

    /**
     * The command tells the player what went, per table, and which half was a deletion and which was a
     * marking. Those numbers are the only part of a privacy feature anybody can check.
     */
    @Test fun `the report counts each table separately`() {
        store().use { s ->
            s.upsertTraits(traits(player = "alice"))
            s.upsertImpression(StoredImpression("MeowCat", "alice", "a", 1, 1L))
            s.upsertImpression(StoredImpression("EconCat", "alice", "b", 1, 1L))
            s.insertReport(report(id = "about-alice", author = "bob", subject = "alice"))
            s.insertReport(report(id = "by-alice", author = "alice", subject = "carol"))
            s.createWorkOrder(order("o-alice", "alice", WorkOrderLevel.RECORDED))

            val forgot = s.forgetAll("alice")

            assertEquals(
                PlayerProfileStore.Forgot(
                    impressions = 2, reportsFiled = 1, reportsMarked = 1, workOrdersMarked = 1, traits = true,
                ),
                forgot,
            )
            assertTrue(forgot.anything)
        }
    }

    /**
     * A self-report — somebody filing about themselves — is their own statement, so it is deleted
     * rather than marked. Otherwise the two counters would both claim the same row.
     */
    @Test fun `a report about yourself is yours, and is deleted`() {
        store().use { s ->
            s.insertReport(report(id = "self", author = "alice", subject = "alice"))

            val forgot = s.forgetAll("alice")

            assertEquals(1, forgot.reportsFiled)
            assertEquals(0, forgot.reportsMarked, "counted once, and as what it actually is")
            assertEquals(0, s.count("reports"))
        }
    }

    /** A player with no trace at all is still a successful answer, and must not be reported as work. */
    @Test fun `forgetting somebody who was never recorded is not a failure`() {
        store().use { s ->
            val forgot = s.forgetAll("nobody")

            assertEquals(PlayerProfileStore.Forgot(0, 0, 0, 0, false), forgot)
            assertFalse(forgot.anything)
            assertFalse(forgot.anythingKept, "nothing was kept, so there is nothing to explain")
        }
    }

    @Test fun `the database survives a restart`() {
        PlayerProfileStore(path("persist.db")).use { it.upsertTraits(traits()) }
        PlayerProfileStore(path("persist.db")).use {
            assertEquals(18.5, it.traitsOf("alice")!!.avgLen, 1e-9)
        }
    }

    @Test fun `the store creates its own directory`() {
        val nested = dir.resolve("a/b/c/agents.db")
        PlayerProfileStore(nested).use { it.upsertTraits(traits()) }
        assertTrue(Files.exists(nested))
    }

    /**
     * Column names read from the live database over an independent connection, so the assertion is
     * about the file on disk rather than about what this code believes it wrote.
     */
    private fun columnsOf(db: Path, table: String): List<String> {
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info($table)").use { rs ->
                    val names = mutableListOf<String>()
                    while (rs.next()) names += rs.getString("name")
                    return names
                }
            }
        }
    }
}
