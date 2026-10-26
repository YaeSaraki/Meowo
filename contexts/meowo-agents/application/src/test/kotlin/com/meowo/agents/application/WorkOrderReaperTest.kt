package com.meowo.agents.application

import com.meowo.agents.domain.WorkOrderLevel
import com.meowo.agents.domain.WorkOrderState
import com.meowo.agents.infrastructure.PlayerProfileStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * The reaper, on a real database.
 *
 * The test that matters most is the last one: it says the ladder has no rung that punishes, and
 * that the storage has nowhere to record one. The whole design exists to hold that, and a rule
 * stated in a comment is not a property.
 */
class WorkOrderReaperTest {

    @TempDir
    lateinit var dir: Path

    private val created = Instant.parse("2026-10-01T00:00:00Z")
    private var clock = created

    private fun reaper(
        store: PlayerProfileStore,
        online: Int = 10,
        admin: Boolean = false,
        facts: Boolean = true,
    ) = WorkOrderReaper(store, { online }, { admin }, { facts }, { clock })

    private fun store() = PlayerProfileStore(dir.resolve("wo.db"))

    @Test fun `an order is created without anything being said to anybody`() {
        store().use { s ->
            val id = reaper(s).open("bob", authors = 3, reason = "有人反映")
            val row = s.openWorkOrders(clock.toEpochMilli()).single()
            assertEquals(id, row.id)
            assertEquals(WorkOrderLevel.RECORDED, row.level, "creation is not action")
            assertEquals(WorkOrderState.OPEN, row.state)
        }
    }

    @Test fun `a lonely server never reaches a restriction`() {
        store().use { s ->
            val r = reaper(s, online = 1, admin = false)
            r.open("bob", 5, null)
            repeat(10) { clock = clock.plusSeconds(86_400); r.run() }
            val level = s.openWorkOrders(clock.toEpochMilli()).single().level
            assertTrue(level < WorkOrderLevel.RESTRICTED, "one player online is no audience: $level")
        }
    }

    @Test fun `a populated server advances but stops short of a punishment`() {
        store().use { s ->
            val r = reaper(s, online = 8, admin = true, facts = true)
            r.open("bob", 5, null)
            // Twelve days, inside the 14-day retention: past it the order expires rather than
            // escalating, which is the other half of "creation is not action".
            repeat(11) { clock = clock.plusSeconds(86_400); r.run() }
            val row = s.openWorkOrders(clock.toEpochMilli()).single()
            // RESTRICTED is the last rung the system may apply by itself, and it heals.
            assertEquals(WorkOrderLevel.RESTRICTED, row.level, "the ladder stops at $row")
        }
    }

    /**
     * The third gate, end to end: no corroborating fact, however many people complain and
     * however many are online, cannot produce a restriction.
     */
    @Test fun `opinion alone never opens a restriction`() {
        store().use { s ->
            val r = reaper(s, online = 20, admin = true, facts = false)
            r.open("bob", 9, "大家都这么说")
            repeat(11) { clock = clock.plusSeconds(86_400); r.run() }
            val level = s.openWorkOrders(clock.toEpochMilli()).single().level
            assertTrue(level < WorkOrderLevel.RESTRICTED, "pure opinion reached $level")
        }
    }

    @Test fun `an order nobody acts on expires rather than escalating forever`() {
        store().use { s ->
            val r = reaper(s, online = 1, admin = false, facts = false)
            r.open("bob", 1, null)
            repeat(20) { clock = clock.plusSeconds(86_400); r.run() }
            assertTrue(s.openWorkOrders(clock.toEpochMilli()).isEmpty(), "it should have expired")
        }
    }

    @Test fun `the reaper reports what it decided instead of doing it`() {
        store().use { s ->
            val r = reaper(s, online = 20, admin = true, facts = true)
            r.open("bob", 5, null)
            clock = clock.plusSeconds(86_400)
            val first = r.run()
            assertTrue(first.isNotEmpty(), "a pass should decide something")
            assertTrue(
                first.all { it is WorkOrderReaper.Verdict },
                "and everything it decides is a reportable verdict",
            )
        }
    }

    // ── the guarantee ────────────────────────────────────────────────────────────────────────

    @Test fun `the ladder has no rung that punishes`() {
        // Written out in full on purpose: adding a rung is a deliberate, reviewed decision and
        // this assertion is the thing that forces somebody to look at it.
        assertEquals(
            listOf("RECORDED", "NOTIFIED", "QUEUED", "RESTRICTED"),
            WorkOrderLevel.entries.map { it.name },
        )
        assertFalse(
            WorkOrderLevel.entries.any { it.name.contains("BAN", true) || it.name.contains("PUNISH", true) },
            "there is no automatic sanction, by name",
        )
    }

    /** The storage has nowhere to put one either — the guarantee is not only in the enum. */
    @Test fun `the work order table has no punishment column`() {
        store().use { s ->
            val columns = mutableListOf<String>()
            java.sql.DriverManager
                .getConnection("jdbc:sqlite:${dir.resolve("wo.db").toAbsolutePath()}")
                .use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("PRAGMA table_info(work_order)").use { rs ->
                            while (rs.next()) columns += rs.getString("name")
                        }
                    }
                }
            for (forbidden in listOf("punishment", "ban", "sanction", "duration")) {
                assertFalse(columns.any { it.contains(forbidden, true) }, "work_order gained $forbidden: $columns")
            }
            assertNotNull(columns)
        }
    }

    @Test fun `soft hits move on their own column and nowhere else`() {
        store().use { s ->
            s.upsertTraits(
                com.meowo.agents.infrastructure.StoredTraits(
                    player = "bob", samples = 100, avgLen = 20.0, publicPct = 90.0,
                    emojiPct = 0.0, peakHour = 20, softHits = 0,
                    traits = "句子不长。", updatedAt = 1L, createdAt = 1L,
                ),
            )
            assertEquals(1, s.bumpSoftHits("bob", 3), "one row updated, not three")
            s.bumpSoftHits("bob", 1)
            val row = s.traitsOf("bob")!!
            assertEquals(4, row.softHits, "the column accumulates")
            assertEquals(4, s.softHitsOf("bob"))
            // And the descriptive half is untouched: the counter is not a description of the person.
            assertEquals("句子不长。", row.traits)
        }
    }
}
