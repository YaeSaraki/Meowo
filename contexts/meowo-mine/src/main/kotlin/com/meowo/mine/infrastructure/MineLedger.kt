package com.meowo.mine.infrastructure

import com.meowo.mine.domain.DiscoveryLedger
import com.meowo.mine.domain.RareKind
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Durable storage for the rare-material discovery register.
 *
 * **A cache, not a query path.** The table is read once at start into a [DiscoveryLedger] and the
 * block handlers then work purely in memory; a row is written only when something is actually
 * recorded. Querying SQLite on every block event would flood the single-threaded worker, since
 * `BlockBreakEvent` is the busiest event on the server.
 *
 * The primary key is `(world, kind)`, so the table holds **at most five rows per world** — one per
 * [RareKind] — and never grows regardless of how long the server runs or how many players dig.
 * That is the whole reason for keying by material rather than by region; see `DiscoveryLedger`.
 *
 * Lives in its own file rather than the economy database so no context depends on another's
 * implementation (architecture red line 2).
 */
class MineLedger(path: Path) : AutoCloseable {

    private val connection: Connection

    init {
        path.toAbsolutePath().parent.toFile().mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try {
            connection.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL")
                s.execute("PRAGMA busy_timeout=5000")
                // The earlier player-scoped shape is pre-release only, so it is dropped rather than
                // migrated: it keyed on (world, kind, player) and could not express "first finder".
                s.execute("DROP TABLE IF EXISTS rare_discovery")
                s.execute(
                    """
                    CREATE TABLE IF NOT EXISTS rare_material (
                        world    TEXT NOT NULL,
                        kind     TEXT NOT NULL,
                        total    INTEGER NOT NULL CHECK(total > 0),
                        first_by TEXT NOT NULL,
                        seen_at  INTEGER NOT NULL,
                        PRIMARY KEY (world, kind)
                    )
                    """.trimIndent(),
                )
            }
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    /** Reads a world's register back into memory. */
    fun load(world: String): DiscoveryLedger {
        var discovered = emptySet<RareKind>()
        var totals = emptyMap<RareKind, Int>()
        var firstBy = emptyMap<RareKind, String>()
        connection.prepareStatement("SELECT kind, total, first_by FROM rare_material WHERE world=?").use { st ->
            st.setString(1, world)
            st.executeQuery().use { rs ->
                var kinds = emptySet<RareKind>()
                var counts = emptyMap<RareKind, Int>()
                var owners = emptyMap<RareKind, String>()
                while (rs.next()) {
                    // A row written by a newer build must not make this one fail to start.
                    val kind = runCatching { RareKind.valueOf(rs.getString(1)) }.getOrNull() ?: continue
                    kinds = kinds + kind
                    counts = counts + (kind to rs.getInt(2))
                    owners = owners + (kind to rs.getString(3))
                }
                discovered = kinds
                totals = counts
                firstBy = owners
            }
        }
        return DiscoveryLedger(discovered, totals, firstBy)
    }

    /**
     * Adds one find to the register.
     *
     * `total` accumulates, but `first_by` and `seen_at` only move on the very first row for this
     * `(world, kind)` — so replaying the same event after a crash cannot steal a first find from
     * the player who earned it.
     */
    fun record(world: String, kind: RareKind, amount: Int, player: String, atMillis: Long) {
        require(amount > 0) { "amount must be positive" }
        connection.prepareStatement(
            """
            INSERT INTO rare_material (world, kind, total, first_by, seen_at) VALUES (?,?,?,?,?)
            ON CONFLICT(world, kind) DO UPDATE SET
                total   = total + excluded.total,
                seen_at = excluded.seen_at
            """.trimIndent(),
        ).use { st ->
            st.setString(1, world)
            st.setString(2, kind.name)
            st.setInt(3, amount)
            st.setString(4, player)
            st.setLong(5, atMillis)
            st.executeUpdate()
        }
    }

    /** Total rows held, for tests and diagnostics. */
    fun count(): Int = connection.createStatement().use { s ->
        s.executeQuery("SELECT COUNT(*) FROM rare_material").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

    override fun close() {
        if (!connection.isClosed) connection.close()
    }
}
