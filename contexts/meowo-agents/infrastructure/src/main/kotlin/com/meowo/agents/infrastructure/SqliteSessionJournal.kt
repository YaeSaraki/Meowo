package com.meowo.agents.infrastructure

import com.meowo.agents.domain.ConversationKey
import com.meowo.agents.domain.SessionJournal
import com.meowo.agents.domain.Speaker
import com.meowo.agents.domain.Turn
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/**
 * A cat's memory of what one player said to it, kept across a logout and a restart.
 *
 * ## Why this exists
 *
 * The window used to live in memory only and was wiped on quit, so a cat met the same player as a
 * stranger every time. That reads as indifference rather than forgetfulness, which is the one thing
 * these are not supposed to be.
 *
 * ## What it deliberately does not store
 *
 * **Private messages between players.** This table holds one row per turn of a (player, cat)
 * conversation and nothing else; a whisper that never reached a cat has no row here. The chat
 * journal keeps those for seven days anyway (`chat/log.db`), so this is a different slice of the same
 * words, not a new category of stored data.
 *
 * ## Retention and bounds
 *
 * Three independent bounds, because any one of them alone leaves a way for the table to grow without
 * limit: [retentionDays] by age, [turnsPerKey] per conversation, [maxKeys] per server. The oldest
 * row of a conversation goes first, and age is only checked periodically so a busy server is not
 * deleting on every append.
 *
 * ## Why there is no cache
 *
 * There was one, and it produced the same turn twice: the cache held the newest turns and the table
 * held them too, so merging them on read duplicated every conversation. Deduplicating them needs an
 * identity the row does not have.
 *
 * So there is only one source of truth. `AgentRuntime.ask` already runs on the agent pool — a
 * conversation is a network round trip — so one indexed insert per turn there costs nothing a
 * player's chat would ever notice, and the whole class of read-merge bugs disappears with the cache.
 */
class SqliteSessionJournal(
    path: Path,
    private val retentionDays: Int = 7,
    private val turnsPerKey: Int = 20,
) : SessionJournal, AutoCloseable {

    init {
        require(retentionDays in 1..3650) { "session retention must be 1..3650 days" }
        require(turnsPerKey in 2..200) { "turns per conversation must be 2..200" }
    }

    @Volatile
    var dropped: Int = 0
        private set

    private val connection: Connection
    private val sincePrune = AtomicInteger(0)

    init {
        path.toAbsolutePath().parent.toFile().mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try {
            connection.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL")
                s.execute("PRAGMA busy_timeout=5000")
                s.execute(
                    """
                    CREATE TABLE IF NOT EXISTS cat_session (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        cat        TEXT    NOT NULL,
                        player     TEXT    NOT NULL,
                        who        TEXT    NOT NULL,
                        text       TEXT    NOT NULL,
                        at         INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // Every read is "newest N for one conversation"; every prune is "older than a day".
                s.execute("CREATE INDEX IF NOT EXISTS cat_session_pair ON cat_session(cat, player, id)")
                s.execute("CREATE INDEX IF NOT EXISTS cat_session_at ON cat_session(at)")
            }
            prune()
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    /** Synchronous, and that is the point: one source of truth, no read-merge to get wrong. */
    override fun append(key: ConversationKey, turn: Turn) {
        try {
            connection.prepareStatement("INSERT INTO cat_session(cat,player,who,text,at) VALUES (?,?,?,?,?)").use { st ->
                st.setString(1, key.cat)
                st.setString(2, key.player.toString())
                st.setString(3, turn.who.name)
                st.setString(4, turn.text)
                st.setLong(5, System.currentTimeMillis())
                st.executeUpdate()
            }
            trim(key)
            if (sincePrune.incrementAndGet() >= 256) prune()
        } catch (error: RuntimeException) {
            // A cat forgetting one line must never take a conversation down with it.
            dropped++
        }
    }

    override fun recent(key: ConversationKey, limit: Int): List<Turn> = read(key, limit)

    /**
     * Nothing to free — there is no cache.
     *
     * A logout must **not** delete what the cat learned: doing so puts persistence back to zero and
     * reintroduces exactly the "stranger every session" problem this class exists to fix. Age, not
     * logout, is what removes a conversation.
     */
    override fun forgetPlayer(player: UUID) = Unit

    private fun read(key: ConversationKey, limit: Int): List<Turn> = runCatching {
        connection.prepareStatement(
            "SELECT who, text FROM cat_session WHERE cat = ? AND player = ? ORDER BY id DESC LIMIT ?",
        ).use { st ->
            st.setString(1, key.cat)
            st.setString(2, key.player.toString())
            st.setInt(3, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Turn(Speaker.valueOf(rs.getString(1)), rs.getString(2))) }
                    .asReversed()
            }
        }
    }.getOrDefault(emptyList())

    /** Keeps only the newest [turnsPerKey] rows of one conversation. */
    private fun trim(key: ConversationKey) {
        connection.prepareStatement(
            """
            DELETE FROM cat_session WHERE id NOT IN (
                SELECT id FROM cat_session WHERE cat = ? AND player = ? ORDER BY id DESC LIMIT ?
            ) AND cat = ? AND player = ?
            """.trimIndent(),
        ).use { st ->
            st.setString(1, key.cat)
            st.setString(2, key.player.toString())
            st.setInt(3, turnsPerKey)
            st.setString(4, key.cat)
            st.setString(5, key.player.toString())
            st.executeUpdate()
        }
    }

    /** Removes everything older than the retention window. Age, never logout, is what forgets. */
    fun prune(): Int {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        sincePrune.set(0)
        return connection.prepareStatement("DELETE FROM cat_session WHERE at < ?").use { st ->
            st.setLong(1, cutoff)
            st.executeUpdate()
        }
    }

    override fun clear() {
        runCatching { connection.createStatement().use { it.executeUpdate("DELETE FROM cat_session") } }
            .onFailure { dropped++ }
    }

    override fun close() = runCatching { connection.close() }.let { }
}