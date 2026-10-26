package com.meowo.chat.infrastructure

import com.meowo.chat.domain.Channel
import com.meowo.chat.domain.Record
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The durable half of the chat log: every line, its `#k3f9` code, and who sent it.
 *
 * ## Retention is the product
 *
 * A player's chat is deleted when that player has not logged in for [retentionDays]. The clock runs
 * from **the player's last login, not from the row's age**, because those are different promises.
 * Age-based retention quietly lies to a regular: everything they said in a good month evaporates
 * while they are still playing every day, and a note that has gone stale is still just as much
 * their words sitting on disk. "You stopped showing up, so we stopped keeping what you said" is a
 * rule a player can believe, and it bounds the table the only way that matters — by who is still
 * here.
 *
 * The rows of nobody in particular (a server notice, a `/balance` echo) belong to no player, so they
 * keep the old rule and simply age out. Without that carve-out they would be the only thing on disk
 * that never goes.
 *
 * What this costs: an active player's rows are never deleted by age. The table grows with the
 * volume of what *currently-present* players said, not with calendar time, which is the trade the
 * retention promise is worth — but it is a trade, and `chat.log-days` now means "days of absence",
 * not "days of age".
 *
 * ## Writes never touch the chat thread
 *
 * Every call hands off to one serial worker and returns a [CompletableFuture]. A chat line is the one
 * place a plugin must never wait, and SQLite on the tick thread is a stall the whole server feels.
 * A full queue **drops** the row rather than blocking: a lost archive entry is better than a chat
 * freeze, and the in-memory log still has it for the current session.
 *
 * [noteSeen] is the one deliberate exception to dropping. Losing an archived line costs a `#k3f9`
 * nobody replays; losing "this player is here" deletes a live player's chat while they play, so it
 * is recorded in memory first and flushed by the next task that gets to run.
 *
 * Its own file, for the same reason `MailStore` has one: no context depends on another's schema.
 */
class ChatLogStore(
    path: Path,
    private val retentionDays: Int = 15,
    private val queueLimit: Int = 2048,
) : AutoCloseable {

    init {
        require(retentionDays in 1..3650) { "chat.log-days must be 1..3650" }
        require(queueLimit in 16..65_536) { "chat.log-queue must be 16..65536" }
    }

    /**
     * How many rows were dropped because the storage worker could not keep up.
     *
     * An [AtomicInteger] rather than a `var`: the counter is incremented from the worker's rejection
     * path, which runs on **the caller's** thread, and `++` on a plain `Int` is read-modify-write —
     * two concurrent rejections could lose one between them, and the number an operator reads at
     * the end of a bad minute is exactly the number they would act on.
     */
    private val droppedCount = AtomicInteger(0)

    /**
     * Set once [close] starts.
     *
     * The rejection handler cannot ask the executor whether it is shut down: the handler is written
     * **inside the executor's own initializer**, so referring to it there is a recursive definition
     * and Kotlin cannot type it. A separate flag says the same thing without the cycle.
     */
    private val shuttingDown = AtomicBoolean(false)

    /**
     * One serial worker with a **bounded** queue.
     *
     * Unbounded would just move the problem: a server that outruns SQLite would grow a backlog until
     * the JVM ran out of heap. Bounded means the oldest pressure point is the queue, and the policy
     * below turns overflow into a counted drop rather than a stall on somebody's chat line.
     *
     * ## The rejection handler throws, on purpose
     *
     * The obvious policy — "count the drop and move on" — is what this used to be, and it was a
     * trap: a [RejectedExecutionHandler] that returns normally tells the caller the task was
     * **accepted**, so `CompletableFuture.supplyAsync` hands back a future for work that will never
     * run. Nothing completes it, so every `get()` on it blocks until its timeout and every
     * `exceptionally` on it never fires. A rejected write became a write that silently never
     * happened and a reader that hung for the length of its timeout.
     *
     * So the handler rethrows [java.util.concurrent.RejectedExecutionException], which
     * [CompletableFuture.supplyAsync] propagates to [submit], which converts it into an
     * **already-failed future**. A caller then sees a failure, not a hang — and the count is still
     * kept, because "how many rows did we lose" is exactly what an operator needs to know.
     */
    private val worker = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(queueLimit),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "meowo-chat-log").apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { _, _ ->
            // Counted only while the worker is still running: a submission that arrives *after*
            // `close()` is not a row lost to a busy queue, it is a row nobody asked for any more,
            // and mixing the two would make the number an operator watches mean two things.
            if (!shuttingDown.get()) droppedCount.incrementAndGet()
            throw java.util.concurrent.RejectedExecutionException("chat log queue is full ($queueLimit tasks)")
        },
    )

    private val connection: Connection
    private val sincePrune = AtomicInteger(0)

    /** How many rows were dropped because the storage worker could not keep up. */
    val dropped: Int get() = droppedCount.get()

    /**
     * "These players are online" waiting for the worker to write it down.
     *
     * [noteSeen] is called from a join handler on the main thread, where the connection must not be
     * touched. Putting the fact in this map first means the *fact* is recorded even when the
     * persistence task is rejected, and the next task that runs flushes it. A rejected archive row
     * costs one replay; a rejected login would cost that player their entire history.
     */
    private val pendingSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
                    CREATE TABLE IF NOT EXISTS chat_log (
                        code       TEXT PRIMARY KEY,
                        channel    TEXT NOT NULL,
                        author     TEXT NOT NULL,
                        speaker_id TEXT,
                        author_id  TEXT,
                        recipient  TEXT,
                        recipient_id TEXT,
                        body       TEXT NOT NULL,
                        anchor     TEXT,
                        sent_at    INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // Retention scans by age and thread walks scan by anchor; both run often, both need an
                // index, and neither may fall back to a table scan on a busy server.
                s.execute("CREATE INDEX IF NOT EXISTS chat_log_sent ON chat_log(sent_at)")
                s.execute("CREATE INDEX IF NOT EXISTS chat_log_anchor ON chat_log(anchor)")
                // Retention asks "which players are gone, and what did they say" — the opposite
                // direction from the index the old age rule wanted. Both participant columns are
                // indexed because either side can be the one that stopped logging in.
                s.execute("CREATE INDEX IF NOT EXISTS chat_log_author ON chat_log(author_id)")
                s.execute("CREATE INDEX IF NOT EXISTS chat_log_recipient ON chat_log(recipient_id)")

                // When did this player last show up? Kept beside the log rather than inferred from
                // it, because a player who logs in daily and says nothing must still be a player we
                // are keeping.
                s.execute(
                    """
                    CREATE TABLE IF NOT EXISTS chat_active (
                        uuid    TEXT PRIMARY KEY,
                        seen_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                s.execute("CREATE INDEX IF NOT EXISTS chat_active_seen ON chat_active(seen_at)")
                backfillSeen()
            }
            prune()
        } catch (e: Exception) {
            connection.close()
            shuttingDown.set(true)
            worker.shutdownNow()
            throw e
        }
    }

    /**
     * Writes one line.
     *
     * A plain `INSERT`, never `INSERT OR REPLACE`.
     *
     * `OR REPLACE` deletes the colliding row and writes a new one in its place, so a code that
     * somehow collided with an existing line would **destroy** the older message — the reader's
     * `#k3f9` would then lead to the newer conversation and the older one would be gone from the
     * archive for good. A plain insert refuses instead: the write fails, the failure is reported,
     * and nothing is lost. The codes are unique by construction (`CodeBook`), so the refusal
     * should never happen; it is the backstop, not the plan.
     */
    fun insert(record: Record): CompletableFuture<Unit> = submit {
        connection.prepareStatement(
            "INSERT INTO chat_log(code,channel,author,speaker_id,author_id,recipient,recipient_id,body,anchor,sent_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        ).use { p ->
            p.setString(1, record.code)
            p.setString(2, record.channel.name)
            p.setString(3, record.author)
            p.setString(4, record.speakerId)
            p.setString(5, record.authorId?.toString())
            p.setString(6, record.recipient)
            p.setString(7, record.recipientId?.toString())
            p.setString(8, record.body)
            p.setString(9, record.anchor)
            p.setLong(10, record.atEpochMillis)
            p.executeUpdate()
        }
        // Amortised rather than per write: deleting on every line would make the hot path a scan.
        if (sincePrune.incrementAndGet() >= 256) prune()
        Unit
    }

    /**
     * A player is here. This is what retention measures against.
     *
     * Called from [org.bukkit.event.player.PlayerJoinEvent], on the main thread. It records in
     * memory first and only then asks the worker to persist, so a saturated queue cannot lose the
     * fact that a live player is live.
     *
     * Later logins overwrite earlier ones by value, never backwards: a clock that jumped, or a task
     * that ran out of order, must not move "last seen" into the past.
     */
    fun noteSeen(player: java.util.UUID, at: Long = System.currentTimeMillis()): CompletableFuture<Unit> {
        pendingSeen.merge(player.toString(), at) { _, existing -> maxOf(existing, at) }
        return submit { flushSeen() }
    }

    /**
     * Writes pending logins down and clears them.
     *
     * Removes each entry only if it still holds the value just written, so a login that arrived
     * while the batch was running is left for the next flush instead of being overwritten with the
     * older timestamp.
     */
    private fun flushSeen() {
        if (pendingSeen.isEmpty()) return
        val batch = pendingSeen.entries.map { it.key to it.value }
        // The upsert is the real guard, and it has to live here rather than in the map: once an
        // entry is flushed it is gone from [pendingSeen], so the next straggler would be comparing
        // against nothing and `OR REPLACE` would happily roll a live player's clock back ten days
        // and have their chat deleted while they play. `MAX` against the stored row makes the
        // database, not the queue order, the thing that decides which login is newer.
        connection.prepareStatement(
            "INSERT INTO chat_active(uuid, seen_at) VALUES (?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET seen_at = MAX(seen_at, excluded.seen_at)",
        ).use { p ->
            for ((uuid, at) in batch) {
                p.setString(1, uuid)
                p.setLong(2, at)
                p.addBatch()
            }
            p.executeBatch()
        }
        for ((uuid, at) in batch) pendingSeen.remove(uuid, at)
    }

    /**
     * Dates every player the log already knows about, once, from their own last message.
     *
     * Without this an upgrade would either delete a year of history or — by treating "no login
     * record" as "never logged in" — delete a week of it on the next prune. A player's last message
     * is a lower bound on their last login, never a higher one, so the worst it can do is forget
     * someone slightly early on the **first** boot after the upgrade; the first join fixes it.
     *
     * `INSERT OR IGNORE` is the whole trick: a row that already has a real login time keeps it, so
     * this can never pull anybody's clock backwards.
     */
    private fun backfillSeen() {
        for (column in listOf("author_id", "recipient_id")) {
            connection.createStatement().use { s ->
                s.executeUpdate(
                    """
                    INSERT OR IGNORE INTO chat_active(uuid, seen_at)
                    SELECT $column, MAX(sent_at) FROM chat_log WHERE $column IS NOT NULL GROUP BY $column
                    """.trimIndent(),
                )
            }
        }
    }

    /**
     * The whole conversation around [code], oldest first: climb to the root of the chain, then take
     * that root's thread. Empty when [code] has aged out.
     */
    fun thread(code: String, limit: Int = 32): CompletableFuture<List<Record>> = submit {
        var root = one(code) ?: return@submit emptyList()
        val walked = HashSet<String>()
        while (true) {
            val anchor = root.anchor
            if (anchor == null || !walked.add(root.code)) break
            val parent = one(anchor) ?: break
            root = parent
        }
        threadFrom(root, limit)
    }

    /**
 * When [player] last logged in, or null when this log has never seen them.
 *
 * Reads `chat_active`, the same table retention is computed from, so the answer can never disagree
 * with the delete. Runs on the store's worker: it is a lookup, but it may be asked from a command
 * that a player is waiting on, and JDBC never runs on the caller's thread.
 */
fun lastSeen(player: java.util.UUID): CompletableFuture<Long?> = submit {
    connection.prepareStatement("SELECT seen_at FROM chat_active WHERE uuid = ?").use { p ->
        p.setString(1, player.toString())
        p.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
    }
}

/**
 * Deletes **everything** recorded for [player], as author or recipient, at that player's request.
 *
 * One delete rather than a select-then-delete loop: a thread walk or a page turn must never see half
 * of a conversation. Submits to the worker like every other write, and returns how many rows went so
 * the caller can report it truthfully.
 *
 * The in-memory ring is left alone, exactly as [prune] leaves it: that ring is what the current
 * session is reading, and it ages out on its own within minutes. A player asking to be forgotten is
 * not asking to have the line they are looking at vanish mid-read.
 */
fun forgetPlayer(player: java.util.UUID): CompletableFuture<Int> = submit {
    connection.prepareStatement(
        "DELETE FROM chat_log WHERE author_id = ? OR recipient_id = ?",
    ).use { p ->
        p.setString(1, player.toString())
        p.setString(2, player.toString())
        p.executeUpdate()
    }
}

/**
 * Every code still in the log.
 *
 * Read once at open, on the calling thread, like [allLastSeen]: the code book has to know which
 * codes are taken before it mints a new one, and a code that collides with an existing row would
 * either overwrite it or send a reader to the wrong message.
 */
fun codes(): List<String> = connection.prepareStatement("SELECT code FROM chat_log").use { p ->
    p.executeQuery().use { rs ->
        buildList { while (rs.next()) add(rs.getString(1)) }
    }
}

/**
 * Every player's last login, for the synchronous read at start-up.
 *
 * One query rather than one per player: the chat log asks the whole question once when it opens and
 * keeps the answer in memory, because a retention decision is made on a timer thread that must not
 * block on JDBC.
 */
fun allLastSeen(): Map<String, Long> = connection.createStatement().use { s ->
    s.executeQuery("SELECT uuid, seen_at FROM chat_active").use { rs ->
        buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) }
    }
}

private fun threadFrom(root: Record, limit: Int): List<Record> {
        val seen = HashSet<String>()
        val out = ArrayList<Record>(limit)
        var frontier = listOf(root)
        var depth = 0
        while (frontier.isNotEmpty() && out.size < limit && depth < MAX_DEPTH) {
            val next = ArrayList<String>()
            for (record in frontier) {
                if (!seen.add(record.code)) continue
                out += record
                if (out.size >= limit) break
                next += childrenOf(record.code)
            }
            frontier = next.distinct().mapNotNull { one(it) }
            depth++
        }
        return out.sortedBy { it.atEpochMillis }
    }

    /**
     * Deletes the chat of players who have not logged in for [retentionDays], on open and after
     * every so many writes.
     *
     * Three clauses, and each exists because of a concrete case:
     *
     * 1. **The author has gone.** Their own words.
     * 2. **The recipient has gone.** "Corresponding chat" is symmetric: Bob's copy of what Alice
     *    said is Bob's data, and it is the *receiver* who usually has no idea it is on disk.
     * 3. **Nobody owns this line, and it is old.** A server notice belongs to no player and would
     *    otherwise be the only row on disk that never goes.
     *
     * [flushSeen] runs first so a prune never deletes against a view of who is online that is one
     * login out of date.
     */
    private fun prune(): Int {
        flushSeen()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        return connection.prepareStatement(
            "DELETE FROM chat_log " +
                "WHERE author_id IN (SELECT uuid FROM chat_active WHERE seen_at < ?) " +
                "OR recipient_id IN (SELECT uuid FROM chat_active WHERE seen_at < ?) " +
                "OR (author_id IS NULL AND recipient_id IS NULL AND sent_at < ?)",
        ).use { p ->
            p.setLong(1, cutoff)
            p.setLong(2, cutoff)
            p.setLong(3, cutoff)
            p.executeUpdate()
        }.also { sincePrune.set(0) }
    }

    private fun one(code: String): Record? =
        connection.prepareStatement("SELECT * FROM chat_log WHERE code = ?").use { p ->
            p.setString(1, code)
            p.executeQuery().use { rs -> if (rs.next()) read(rs) else null }
        }

    private fun childrenOf(code: String): List<String> =
        connection.prepareStatement("SELECT code FROM chat_log WHERE anchor = ? ORDER BY sent_at").use { p ->
            p.setString(1, code)
            p.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private fun read(rs: java.sql.ResultSet) = Record(
        code = rs.getString("code"),
        channel = runCatching { Channel.valueOf(rs.getString("channel")) }.getOrDefault(Channel.SYSTEM),
        author = rs.getString("author"),
        speakerId = rs.getString("speaker_id"),
        authorId = rs.getString("author_id")?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() },
        recipient = rs.getString("recipient"),
        recipientId = rs.getString("recipient_id")?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() },
        body = rs.getString("body"),
        anchor = rs.getString("anchor"),
        atEpochMillis = rs.getLong("sent_at"),
    )

    /**
     * Hands a block to the worker and returns a future for its result.
     *
     * The rejection path is the important part. [CompletableFuture.supplyAsync] calls
     * `executor.execute(...)`, and the worker's rejection handler **throws** — so the exception
     * arrives here synchronously, and this method turns it into an **already-failed future**.
     *
     * That is the whole difference between "the queue was full" and "the future never completes".
     * A handler that returned normally would leave the caller holding a future for work nobody will
     * ever do: `get()` would block until its timeout and `exceptionally` would never fire, so a
     * dropped row looked like a slow one and a failed read looked like a hung one.
     */
    private fun <T> submit(block: () -> T): CompletableFuture<T> = try {
        CompletableFuture.supplyAsync(java.util.function.Supplier(block), worker)
    } catch (rejected: java.util.concurrent.RejectedExecutionException) {
        // The count was already incremented by the rejection handler; this is only the conversion
        // into a future the caller can actually observe.
        CompletableFuture.failedFuture(rejected)
    }

    override fun close() {
        // Set **before** the shutdown, so a submission that races the close is not counted as a row
        // lost to a busy queue.
        shuttingDown.set(true)
        worker.shutdown()
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow()
        } catch (_: InterruptedException) {
            worker.shutdownNow()
            Thread.currentThread().interrupt()
        }
        runCatching { connection.close() }
    }

    private companion object { const val MAX_DEPTH = 8 }
}