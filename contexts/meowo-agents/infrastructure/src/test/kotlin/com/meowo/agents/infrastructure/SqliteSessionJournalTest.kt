package com.meowo.agents.infrastructure

import com.meowo.agents.domain.ConversationKey
import com.meowo.agents.domain.Speaker
import com.meowo.agents.domain.Turn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * Real SQLite, real file, no mocks.
 *
 * What is being proven is not that a map works — it is that **a restart does not make a cat forget a
 * player**, which is the whole reason this table exists and the one property an in-memory map can
 * never have.
 */
class SqliteSessionJournalTest {

    @TempDir
    lateinit var dir: Path

    private val player = UUID.nameUUIDFromBytes("alice".toByteArray())
    private val key = ConversationKey(player, "econ")

    private fun journal(name: String = "memory.db", days: Int = 7) =
        SqliteSessionJournal(dir.resolve(name), retentionDays = days)

    @Test fun `a conversation survives a restart`() {
        journal().use { j ->
            j.append(key, Turn(Speaker.PLAYER, "在吗"))
            j.append(key, Turn(Speaker.CAT, "喵"))
        }
        journal().use { reopened ->
            assertEquals(listOf("在吗", "喵"), reopened.recent(key, 10).map { it.text })
        }
    }

    /**
     * The bug this class was written for.
     *
     * Logout used to wipe the window, so the next session met a stranger. It must now free the cache
     * only: age removes a conversation, not a disconnect.
     */
    @Test fun `a logout forgets nothing`() {
        journal().use { j ->
            j.append(key, Turn(Speaker.PLAYER, "记住我"))
            j.forgetPlayer(player)
        }
        journal().use { reopened ->
            assertEquals(listOf("记住我"), reopened.recent(key, 10).map { it.text })
        }
    }

    @Test fun `clear really does forget, for when the server wants a clean slate`() {
        journal().use { j ->
            j.append(key, Turn(Speaker.PLAYER, "在吗"))
            j.clear()
            assertEquals(emptyList<Turn>(), j.recent(key, 10))
        }
    }

    @Test fun `a conversation is capped at the newest turns`() {
        journal().use { j ->
            repeat(30) { j.append(key, Turn(Speaker.PLAYER, "第${it}句")) }
            val kept = j.recent(key, 100)
            assertEquals(20, kept.size, "twenty turns is ten exchanges; past that the prompt is carrying noise")
            assertEquals("第29句", kept.last().text)
        }
    }

    /** The cache-and-table merge used to hand back every turn twice. One source, one answer. */
    @Test fun `a turn is never returned twice`() {
        journal().use { j ->
            repeat(6) { j.append(key, Turn(Speaker.PLAYER, "第${it}句")) }
            val all = j.recent(key, 50).map { it.text }
            assertEquals(all.size, all.toSet().size, "the same turn came back twice: $all")
        }
    }

    @Test fun `one cat cannot read another's conversation`() {
        journal().use { j ->
            j.append(key, Turn(Speaker.PLAYER, "只给经济猫"))
            assertEquals(emptyList<Turn>(), j.recent(key.copy(cat = "tele"), 10))
        }
    }

    @Test fun `aged-out conversations are pruned on open`() {
        journal().use { j ->
            j.append(key, Turn(Speaker.PLAYER, "很久以前"))
        }
        // A retention of one day cannot be reached by a test clock, so age the row directly: the
        // prune is what matters, not the retention arithmetic.
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dir.resolve("memory.db")}").use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE cat_session SET at = 0") }
        }
        journal().use { reopened ->
            assertEquals(emptyList<Turn>(), reopened.recent(key, 10), "seven days is a promise, not a suggestion")
        }
    }

    /** A read must not duplicate, truncate or reorder what a write just stored. */
    @Test fun `what was written is what comes back`() {
        journal().use { j ->
            repeat(4) { j.append(key, Turn(Speaker.PLAYER, "第${it}句")) }
            assertEquals(listOf("第2句", "第3句"), j.recent(key, 2).map { it.text })
        }
    }
}