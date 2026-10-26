package com.meowo.agents.infrastructure

import com.meowo.agents.domain.Sentiment
import com.meowo.agents.domain.WorkOrderLevel
import com.meowo.agents.domain.WorkOrderState
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** See the class note: marked by `/meo forget`, skipped by every read that counts an accusation. */
const val REPORT_FORGOTTEN = "FORGOTTEN"

/** A stored player-to-player report, as the store sees it. */
data class StoredReport(
    val id: String,
    val author: String,
    val subject: String,
    val sentiment: Sentiment,
    val reason: String,
    val atEpochMillis: Long,
    val expiresAt: Long,
    val coScore: Int,
    val trusted: Int,
    val state: String,
)

/** One open work order, as the ladder's reaper sees it. */
data class StoredWorkOrder(
    val id: String,
    val subject: String,
    val level: WorkOrderLevel,
    val state: WorkOrderState,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    val lastLevelAt: Long?,
    val authors: Int,
    val reason: String?,
)

/** One row of the shared factual base. */
data class StoredTraits(
    val player: String,
    val samples: Int,
    val avgLen: Double,
    val publicPct: Double,
    val emojiPct: Double,
    val peakHour: Int?,
    val softHits: Int,
    val traits: String?,
    val updatedAt: Long,
    val createdAt: Long,
)

/** One cat's own wording about one player. */
data class StoredImpression(
    val cat: String,
    val player: String,
    val impression: String,
    val noteCount: Int,
    val updatedAt: Long,
)

/**
 * The agents' own database, separate from the economy's so no context depends on another's storage.
 *
 * ## Row counts are bounded by people, not by messages
 *
 * [playerTraits] has one row per player, [catImpression] one per (cat, player). Only
 * [playerReport] grows with activity, and it carries `expires_at` plus a [prune] call — the plan's
 * growth table, and the only one that needs it.
 *
 * ## `player_report` has no `punishment` column
 *
 * That absence is the guarantee. A report can raise a work order; it cannot raise a sanction,
 * because there is nowhere in this schema to put one. `DISMISSED` and `UPHELD` are the only states
 * a person sets, and `UPHELD` means "a human should look at this".
 *
 * ## `FORGOTTEN` is a state, not a deletion
 *
 * See [forgetAll]. Reports authored by somebody else are not the subject's data, so `/meo forget`
 * cannot delete them; it moves them to [REPORT_FORGOTTEN], which [reportsAbout] skips. The row is
 * still on disk, which is the deliberate remainder — a self-service command must not be able to
 * rewrite what was alleged about someone before anyone has read it.
 */
class PlayerProfileStore(path: Path) : AutoCloseable {

    /**
     * The file, kept for the worker threads that open their own connection to it.
     */
    private val file: String = path.toAbsolutePath().toString()

    /**
     * One connection **per thread**, rather than one connection for everybody.
     *
     * A SQLite connection is not thread-safe, and this store used to have a single one shared by
     * three kinds of caller: the main thread (`/meo known`, `/meo forget`), the out-of-band timer
     * that flushes the profiles, and the discipline pass. The moment the timers moved off the tick
     * thread — which they had to, because a database round trip on a tick is a stall the whole
     * server feels — two threads were inside the same connection at once, which corrupts it in
     * ways SQLite does not report.
     *
     * A connection per thread removes the sharing instead of serialising around it: each thread
     * gets its own handle to the same file, WAL lets them read at the same time, and the two
     * writers wait on each other through `busy_timeout` rather than through a lock that would
     * hold up the chat thread behind a prune.
     *
     * The connections are tracked so [close] can shut them all down: a thread-local handle whose
     * owner has gone away is still an open file descriptor otherwise.
     */
    private val opened = java.util.concurrent.CopyOnWriteArrayList<Connection>()

    private val local = ThreadLocal.withInitial { openConnection() }

    /** This thread's connection, opened on first use. */
    private fun conn(): Connection = local.get()

    private fun openConnection(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:$file")
        connection.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA busy_timeout=5000")
        }
        opened.add(connection)
        return connection
    }

    init {
        java.io.File(file).parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        try {
            conn().createStatement().use { s ->
s.execute("PRAGMA journal_mode=WAL")
                s.execute("PRAGMA busy_timeout=5000")
                s.execute(
                    """
                    CREATE TABLE IF NOT EXISTS player_traits (
                        player      TEXT PRIMARY KEY,
                        samples     INTEGER NOT NULL,
                        avg_len     REAL    NOT NULL,
                        public_pct  REAL    NOT NULL,
                        emoji_pct   REAL    NOT NULL,
                        peak_hour   INTEGER,
                        soft_hits   INTEGER NOT NULL,
                        traits      TEXT,
                        updated_at  INTEGER NOT NULL,
                        created_at  INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                s.execute(
                    """
                    CREATE TABLE IF NOT EXISTS cat_impression (
                        cat         TEXT NOT NULL,
                        player      TEXT NOT NULL,
                        impression  TEXT NOT NULL,
                        note_count  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL,
                        PRIMARY KEY (cat, player)
                    )
                    """.trimIndent(),
                )
                s.execute(
                    """
                    CREATE TABLE IF NOT EXISTS player_report (
                        id          TEXT PRIMARY KEY,
                        author      TEXT NOT NULL,
                        subject     TEXT NOT NULL,
                        sentiment   TEXT NOT NULL,
                        reason      TEXT NOT NULL,
                        at          INTEGER NOT NULL,
                        expires_at  INTEGER NOT NULL,
                        co_score    INTEGER NOT NULL,
                        trusted     INTEGER NOT NULL,
                        state       TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                s.execute(
                    """
                    CREATE TABLE IF NOT EXISTS work_order (
                        id          TEXT PRIMARY KEY,
                        subject     TEXT NOT NULL,
                        level       TEXT NOT NULL,
                        state       TEXT NOT NULL,
                        created_at  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL,
                        expires_at  INTEGER NOT NULL,
                        last_level_at INTEGER,
                        authors     INTEGER NOT NULL DEFAULT 0,
                        reason      TEXT
                    )
                    """.trimIndent(),
                )
                s.execute("CREATE INDEX IF NOT EXISTS work_order_subject ON work_order(subject, state)")
                s.execute("CREATE INDEX IF NOT EXISTS player_report_subject ON player_report(subject, sentiment, expires_at)")
                s.execute("CREATE INDEX IF NOT EXISTS player_report_expiry ON player_report(expires_at)")
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    // ── shared factual base: one row per player ────────────────────────────────────────────────

    /**
     * One upsert of the **shared factual base**.
     *
     * ## `soft_hits` is deliberately *not* written here
     *
     * `soft_hits` is the discipline cat's column and this method is the factual writer's. The two
     * used to be the same statement, and the consequence was that the discipline side was erased
     * every ten minutes: [ProfileTracker] flushed a summary row with `soft_hits = 0`, and the
     * `ON CONFLICT` clause wrote that zero straight back over the accumulated count. The 风纪
     * score was therefore always 0, always recomputed as 0, and the whole ladder — the risk
     * scorer, the work orders, the score pushed into the chat gate — was a machine with nothing
     * connected to it. It looked alive because every part of it ran.
     *
     * So the update clause leaves the column alone. The insert still writes `row.softHits`,
     * because a row that does not exist yet has no discipline history to preserve.
     *
     * The counter itself is written by [bumpSoftHits] and [recomputeSoftHits], and only by those.
     */
    fun upsertTraits(row: StoredTraits) {
        conn().prepareStatement(
            """
            INSERT INTO player_traits (player, samples, avg_len, public_pct, emoji_pct, peak_hour, soft_hits, traits, updated_at, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(player) DO UPDATE SET
                samples = excluded.samples, avg_len = excluded.avg_len,
                public_pct = excluded.public_pct, emoji_pct = excluded.emoji_pct,
                peak_hour = excluded.peak_hour,
                traits = excluded.traits, updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { st ->
            st.setString(1, row.player)
            st.setInt(2, row.samples)
            st.setDouble(3, row.avgLen)
            st.setDouble(4, row.publicPct)
            st.setDouble(5, row.emojiPct)
            if (row.peakHour == null) st.setNull(6, java.sql.Types.INTEGER) else st.setInt(6, row.peakHour)
            st.setInt(7, row.softHits)
            st.setString(8, row.traits)
            st.setLong(9, row.updatedAt)
            st.setLong(10, row.createdAt)
            st.executeUpdate()
        }
    }

    fun traitsOf(player: String): StoredTraits? = conn().prepareStatement(
        "SELECT player, samples, avg_len, public_pct, emoji_pct, peak_hour, soft_hits, traits, updated_at, created_at FROM player_traits WHERE player=?",
    ).use { st ->
        st.setString(1, player)
        st.executeQuery().use { rs ->
            if (!rs.next()) return null
            StoredTraits(
                player = rs.getString(1), samples = rs.getInt(2), avgLen = rs.getDouble(3),
                publicPct = rs.getDouble(4), emojiPct = rs.getDouble(5),
                peakHour = rs.getObject(6)?.let { (it as Number).toInt() },
                softHits = rs.getInt(7), traits = rs.getString(8),
                updatedAt = rs.getLong(9), createdAt = rs.getLong(10),
            )
        }
    }

    // ── per-cat impressions: one row per (cat, player) ────────────────────────────────────────

    fun upsertImpression(row: StoredImpression) {
        conn().prepareStatement(
            """
            INSERT INTO cat_impression (cat, player, impression, note_count, updated_at)
            VALUES (?,?,?,?,?)
            ON CONFLICT(cat, player) DO UPDATE SET
                impression = excluded.impression,
                note_count = cat_impression.note_count + 1,
                updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { st ->
            st.setString(1, row.cat)
            st.setString(2, row.player)
            st.setString(3, row.impression)
            st.setInt(4, row.noteCount)
            st.setLong(5, row.updatedAt)
            st.executeUpdate()
        }
    }

    fun impressionOf(cat: String, player: String): StoredImpression? = conn().prepareStatement(
        "SELECT cat, player, impression, note_count, updated_at FROM cat_impression WHERE cat=? AND player=?",
    ).use { st ->
        st.setString(1, cat)
        st.setString(2, player)
        st.executeQuery().use { rs ->
            if (!rs.next()) null
            else StoredImpression(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5))
        }
    }

    /**
 * Every cat's impression of [player], newest first.
 *
 * Exists so a player can be **shown** what is held about them. An impression nobody can read is
 * indistinguishable from one the server does not have, and a player who suspects a cat is keeping
 * notes has no way to find out which it is.
 */
fun impressionsOf(player: String): List<StoredImpression> =
        conn().prepareStatement(
        "SELECT cat, player, impression, note_count, updated_at FROM cat_impression " +
            "WHERE player=? ORDER BY updated_at DESC",
    ).use { st ->
        st.setString(1, player)
        st.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(StoredImpression(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5)))
                }
            }
        }
    }

/** Clears every cat's impression of a player. Called when they opt out or ask to be forgotten. */
    fun forgetImpressions(player: String): Int =
        conn().prepareStatement("DELETE FROM cat_impression WHERE player=?").use { st ->
            st.setString(1, player)
            st.executeUpdate()
        }

    /**
     * Forgets the impressions of players who have not logged in since [cutoff]. Returns how many
     * rows went.
     *
     * ## Why the clock comes from outside
     *
     * An impression is a **compression of the chat log**, so it cannot outlive the thing it
     * compresses without one of the two promises quietly becoming a lie: the chat log says "15 days
     * of absence and we delete what you said", and a cat holding a sentence distilled from those
     * words would still be holding it on day 16. The login history lives in `chat/log.db` and
     * nowhere else, so it is passed in rather than guessed at from `updated_at` — a row is only as
     * old as the last impression, not as old as the player.
     *
     * ## Unknown is not absent
     *
     * [lastSeen] returning null means "no evidence", and the row is **kept**. A player who predates
     * the chat log has no stamp, and forgetting them for that reason is the exact inverse of what a
     * retention promise is for. The leak is bounded by [forgetAll], which exists for the player who
     * asks.
     */
    fun pruneImpressions(cutoff: Long, lastSeen: (String) -> Long?): Int {
        val players = conn().createStatement().use { s ->
            s.executeQuery("SELECT DISTINCT player FROM cat_impression").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        var removed = 0
        for (player in players) {
            val seen = lastSeen(player) ?: continue
            if (seen >= cutoff) continue
            // Every cat's impression goes together: the player is gone, not one cat's memory of them.
            removed += forgetImpressions(player)
        }
        return removed
    }

    // ── 风纪: the one evaluative column, and the ladder's own table ─────────────────────────

    /**
     * The discipline cat's counter. **Never** read into a prompt, a recommendation, or anything a
     * player can see; the whole reason it lives in its own column is that [traitsOf] can be handed
     * to a model wholesale without it being noticed.
     *
     * ## It creates the row if there is none
     *
     * A complaint can arrive long before the player has said anything, and a `player_traits` row
     * only exists once the factual writer has flushed one. An `UPDATE` against a row that does not
     * exist updates **nothing**, silently: the accusation was on file and the counter said zero,
     * and the 风纪 score was computed from that zero. So this is an upsert — the complaint is
     * counted whether or not anybody has described the player yet.
     *
     * The insert path creates a row with no samples and no description, which is exactly what it
     * knows: an accusation, and nothing else about the person.
     */
    fun bumpSoftHits(player: String, delta: Int, at: Long = System.currentTimeMillis()): Int =
        conn().prepareStatement(
            """
            INSERT INTO player_traits (player, samples, avg_len, public_pct, emoji_pct, peak_hour, soft_hits, traits, updated_at, created_at)
            VALUES (?, 0, 0, 0, 0, NULL, ?, NULL, ?, ?)
            ON CONFLICT(player) DO UPDATE SET
                soft_hits = player_traits.soft_hits + excluded.soft_hits,
                updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { st ->
            st.setString(1, player)
            st.setInt(2, delta)
            st.setLong(3, at)
            st.setLong(4, at)
            st.executeUpdate()
        }

    /**
     * Brings the counter back in line with the evidence that is actually on file, and returns it.
     *
     * ## Why this exists at all
     *
     * The counter is a number, and a number only goes up. The 风纪 design says the opposite: the
     * score is **recomputed, never accumulated**, so a player who was complained about and has
     * since stopped being complained about sees their score fall back on the next pass — without
     * anybody deciding to forgive them. With a counter that only grows, the score never falls
     * back, and the promise is a lie nobody notices until the score is high enough to matter.
     *
     * So the counter is not the truth; the report table is. What is on file right now — negative,
     * **counted** (so not one of a pile-on and not one from somebody who barely played with them),
     * unexpired, and not marked as forgotten — is the truth, and this brings the counter into line
     * with it.
     *
     * [bumpSoftHits] is still the right way to *record* an accusation, because it takes effect at
     * once rather than at the next pass. The two agree: the bump adds one, the next recompute
     * counts the same one and sets the same total.
     */
    fun recomputeSoftHits(player: String, now: Long): Int {
        val live = conn().prepareStatement(
            "SELECT COUNT(*) FROM player_report " +
                "WHERE subject=? AND sentiment='NEGATIVE' AND trusted=1 AND state<>? AND expires_at>?",
        ).use { st ->
            st.setString(1, player)
            st.setString(2, REPORT_FORGOTTEN)
            st.setLong(3, now)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        // Only `soft_hits` is written: the factual half of this row belongs to the factual writer,
        // and `updated_at` says "when the description was last recomputed", which this is not.
        conn().prepareStatement(
            """
            INSERT INTO player_traits (player, samples, avg_len, public_pct, emoji_pct, peak_hour, soft_hits, traits, updated_at, created_at)
            VALUES (?, 0, 0, 0, 0, NULL, ?, NULL, ?, ?)
            ON CONFLICT(player) DO UPDATE SET soft_hits = excluded.soft_hits
            """.trimIndent(),
        ).use { st ->
            st.setString(1, player)
            st.setInt(2, live)
            st.setLong(3, now)
            st.setLong(4, now)
            st.executeUpdate()
        }
        return live
    }

    /** The discipline cat's counter, or 0 when they have no profile yet. */
    fun softHitsOf(player: String): Int =
        conn().prepareStatement("SELECT soft_hits FROM player_traits WHERE player = ?").use { st ->
            st.setString(1, player)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    fun openWorkOrders(now: Long): List<StoredWorkOrder> =
        conn().prepareStatement(
            "SELECT id, subject, level, state, created_at, updated_at, expires_at, last_level_at, authors, reason " +
                "FROM work_order WHERE state = 'OPEN' AND expires_at > ?",
        ).use { st ->
            st.setLong(1, now)
            st.executeQuery().use { rs ->
                val out = mutableListOf<StoredWorkOrder>()
                while (rs.next()) {
                    out += StoredWorkOrder(
                        id = rs.getString(1), subject = rs.getString(2),
                        level = WorkOrderLevel.valueOf(rs.getString(3)),
                        state = WorkOrderState.valueOf(rs.getString(4)),
                        createdAt = rs.getLong(5), updatedAt = rs.getLong(6),
                        expiresAt = rs.getLong(7),
                        lastLevelAt = rs.getObject(8)?.let { (it as Number).toLong() },
                        authors = rs.getInt(9), reason = rs.getString(10),
                    )
                }
                out
            }
        }

    fun advanceWorkOrder(id: String, level: WorkOrderLevel, at: Long): Int =
        conn().prepareStatement(
            "UPDATE work_order SET level = ?, last_level_at = ?, updated_at = ? WHERE id = ?",
        ).use { st ->
            st.setString(1, level.name); st.setLong(2, at); st.setLong(3, at); st.setString(4, id)
            st.executeUpdate()
        }

    fun setWorkOrderState(id: String, state: WorkOrderState, at: Long): Int =
        conn().prepareStatement("UPDATE work_order SET state = ?, updated_at = ? WHERE id = ?").use { st ->
            st.setString(1, state.name); st.setLong(2, at); st.setString(3, id)
            st.executeUpdate()
        }

    fun createWorkOrder(order: StoredWorkOrder) =
        conn().prepareStatement(
            "INSERT INTO work_order (id, subject, level, state, created_at, updated_at, expires_at, last_level_at, authors, reason) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
        ).use { st ->
            st.setString(1, order.id); st.setString(2, order.subject)
            st.setString(3, order.level.name); st.setString(4, order.state.name)
            st.setLong(5, order.createdAt); st.setLong(6, order.updatedAt); st.setLong(7, order.expiresAt)
            st.setObject(8, order.lastLevelAt); st.setInt(9, order.authors); st.setString(10, order.reason)
            st.executeUpdate()
        }

    // ── reports: the only table that grows with activity ────────────────────────────────────

    fun insertReport(r: StoredReport) {
        conn().prepareStatement(
            "INSERT INTO player_report (id, author, subject, sentiment, reason, at, expires_at, co_score, trusted, state) VALUES (?,?,?,?,?,?,?,?,?,?)",
        ).use { st ->
            st.setString(1, r.id)
            st.setString(2, r.author)
            st.setString(3, r.subject)
            st.setString(4, r.sentiment.name)
            st.setString(5, r.reason)
            st.setLong(6, r.atEpochMillis)
            st.setLong(7, r.expiresAt)
            st.setInt(8, r.coScore)
            st.setInt(9, r.trusted)
            st.setString(10, r.state)
            st.executeUpdate()
        }
    }

    /**
     * Accusations currently standing against a subject, newest last.
     *
     * Skips [REPORT_FORGOTTEN] — that is the whole point of the state. Every count that feeds the
     * discipline ladder or the rate limit comes through here, so filtering in one place is what makes
     * "marked, not deleted" actually mean *out of use* rather than *still counting*.
     */
    fun reportsAbout(subject: String, now: Long): List<StoredReport> = conn().prepareStatement(
        "SELECT id, author, subject, sentiment, reason, at, expires_at, co_score, trusted, state " +
            "FROM player_report WHERE subject=? AND expires_at>? AND state<>?",
    ).use { st ->
        st.setString(1, subject)
        st.setLong(2, now)
        st.setString(3, REPORT_FORGOTTEN)
        st.executeQuery().use { rs ->
            val out = mutableListOf<StoredReport>()
            while (rs.next()) {
                out += StoredReport(
                    rs.getString(1), rs.getString(2), rs.getString(3), Sentiment.valueOf(rs.getString(4)),
                    rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getString(10),
                )
            }
            out
        }
    }

    /**
     * How many times one author has filed against one subject since a moment.
     *
     * Deliberately **not** filtered on [REPORT_FORGOTTEN], unlike [reportsAbout]. That filter is
     * about the subject's data; this one is about the author's conduct, and the subject deleting
     * their own name must not launder someone else's filing record. A rate limit that a third party
     * can reset by asking to be forgotten is not a rate limit.
     */
    fun reportsByAuthorSince(author: String, subject: String, since: Long): Int =
        scalar(
            "SELECT COUNT(*) FROM player_report WHERE author=? AND subject=? AND at>=?",
            author, subject, since.toString(),
        )

    fun setReportState(id: String, state: String): Int = conn().prepareStatement(
        "UPDATE player_report SET state=? WHERE id=?",
    ).use { st ->
        st.setString(1, state)
        st.setString(2, id)
        st.executeUpdate()
    }

    /**
     * Deletes expired reports and hands the freed pages back.
     *
     *
     * The rows go away; the *file* does not shrink, because SQLite returns the pages to its free
     * list for reuse rather than truncating. That is the behaviour worth knowing about: a
     * churn-heavy table plateaus at its high-water mark instead of growing forever, which is why
     * this needs no VACUUM. `PlayerProfileFootprintTest` measures the plateau rather than taking it
     * on trust, because the whole growth story of the module rests on it.
     */
    fun prune(now: Long): Int = conn().prepareStatement(
        "DELETE FROM player_report WHERE expires_at<=?",
    ).use { st ->
        st.setLong(1, now)
        st.executeUpdate()
    }

    /**
     * What a forget actually did, so the player is told the truth rather than "done".
     *
     * Counts, not a boolean: a privacy command that answers "yes" while missing a table is worse than
     * one that refuses, because the player stops looking. And the counts are split in two on purpose
     * — what was deleted and what was only taken out of use are different answers to "what happened
     * to me", and merging them would make the command claim a deletion it did not perform.
     */
    data class Forgot(
        /** Every cat's impression of them. Gone. */
        val impressions: Int,
        /** Reports they filed about other people. Their own statements — gone. */
        val reportsFiled: Int,
        /** Reports other people filed **about** them. Still on disk, out of use. See [forgetAll]. */
        val reportsMarked: Int,
        /** The discipline ladder's rows about them. Still on disk, out of use. See [forgetAll]. */
        val workOrdersMarked: Int,
        /** Whether a profile row went; the counters themselves are one summary row. */
        val traits: Boolean,
    ) {
        val anything: Boolean
            get() = impressions + reportsFiled + reportsMarked + workOrdersMarked > 0 || traits

        /** True when something stayed behind on purpose — the message has to say so. */
        val anythingKept: Boolean get() = reportsMarked + workOrdersMarked > 0
    }

    /**
     * Forgets a player: every cat's impression, the reports they filed, and their profile.
     *
     * ## What is not deleted, and why that is the point
     *
     * The first version ran `DELETE FROM player_report WHERE subject=? OR author=?` and
     * `DELETE FROM work_order WHERE subject=?`, reasoning that leaving the discipline ladder's rows
     * behind would break the promise. It does — **but it breaks it in the other direction.**
     *
     * A report filed by somebody else is *their* speech about this player, and a work order is the
     * moderation side's record. Deleting them was not forgetting; it was letting a player erase an
     * accusation that had just been filed and not yet read (`state = "OPEN"` when it lands). The
     * player could type their own name and make an unreviewed complaint — and the ladder it would
     * have climbed — disappear, and be told it had deleted two rows.
     *
     * So those are **marked** instead: the rows stay, in state [REPORT_FORGOTTEN] /
     * [WorkOrderState.FORGOTTEN], where no read path counts them and [WorkOrderLadder] can never
     * climb out of them. The player's name is still in the file, which is the one thing this
     * command is not allowed to do on its own — and [Forgot.anythingKept] exists so the command can
     * say so out loud instead of quietly reporting a smaller number.
     *
     * Reports they **filed** are deleted, on purpose, and the opposite reason: those are their
     * statements about other people, and asking not to be remembered does not leave behind the
     * things they remembered about others.
     */
    fun forgetAll(player: String): Forgot {
        val impressions = forgetImpressions(player)
        // Author first, then subject: a self-report (author == subject) is theirs, and counting it
        // in both numbers would make the reply add up to more than the table ever held.
        val filed = conn().prepareStatement("DELETE FROM player_report WHERE author=?").use { st ->
            st.setString(1, player)
            st.executeUpdate()
        }
        val marked = conn().prepareStatement(
            "UPDATE player_report SET state=? WHERE subject=? AND state<>?",
        ).use { st ->
            st.setString(1, REPORT_FORGOTTEN)
            st.setString(2, player)
            st.setString(3, REPORT_FORGOTTEN)
            st.executeUpdate()
        }
        val orders = conn().prepareStatement(
            "UPDATE work_order SET state=? WHERE subject=? AND state<>?",
        ).use { st ->
            st.setString(1, WorkOrderState.FORGOTTEN.name)
            st.setString(2, player)
            st.setString(3, WorkOrderState.FORGOTTEN.name)
            st.executeUpdate()
        }
        val traits = conn().prepareStatement("DELETE FROM player_traits WHERE player=?").use { st ->
            st.setString(1, player)
            st.executeUpdate()
        } > 0
        return Forgot(
            impressions = impressions,
            reportsFiled = filed,
            reportsMarked = marked,
            workOrdersMarked = orders,
            traits = traits,
        )
    }

    fun count(table: String): Int = when (table) {
        "traits", "player_traits" -> scalar("SELECT COUNT(*) FROM player_traits")
        "impressions", "cat_impression" -> scalar("SELECT COUNT(*) FROM cat_impression")
        "reports", "player_report" -> scalar("SELECT COUNT(*) FROM player_report")
        else -> throw IllegalArgumentException("unknown table $table")
    }

    private fun scalar(sql: String): Int = conn().createStatement().use { s ->
        s.executeQuery(sql).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

    /** [scalar] with bound parameters — string arguments are never interpolated into SQL. */
    private fun scalar(sql: String, vararg args: Any): Int = conn().prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setString(i + 1, a.toString()) }
        st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

    override fun close() {
        // Every connection this store opened, not just this thread's: a thread-local handle whose
        // owner has gone away is still an open file descriptor otherwise.
        opened.forEach { connection -> runCatching { if (!connection.isClosed) connection.close() } }
        opened.clear()
    }
}