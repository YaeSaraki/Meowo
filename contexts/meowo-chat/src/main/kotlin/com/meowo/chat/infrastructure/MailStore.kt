package com.meowo.chat.infrastructure

import com.meowo.chat.domain.Mail
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Durable storage for offline private messages.
 *
 * **A cache, not a query path.** Rows are read once at start to hydrate the in-memory
 * [com.meowo.chat.domain.Mailbox]; after that the command handlers work purely in memory and this
 * only sees the handful of rows that actually change (a new mail, a read mark, a reply mark).
 *
 * Lives in its own file rather than the economy database so no context depends on another's
 * implementation (architecture red line 2) — same reasoning as `MineLedger`.
 *
 * The table is keyed by mail id, so a row is written exactly once; state changes are updates rather
 * than deletes, which is why a read marker survives a restart.
 *
 * ## Every write goes through one worker
 *
 * The mailbox is filled from the **main thread** — a player runs `/meo msg` and the recipient is
 * offline — and SQLite on a tick thread is a stall the whole server feels. So every statement is
 * handed to a single serial worker and the caller gets a [CompletableFuture], exactly the model
 * [ChatLogStore] uses. The reads are on the same worker for the same reason: the connection is one
 * connection, and one connection touched by two threads is a corrupted connection.
 *
 * The rejection policy **throws**, so a saturated queue produces a failed future rather than a
 * future that never completes. See [ChatLogStore] for the full argument; the short version is that
 * a `RejectedExecutionHandler` which returns normally tells the caller the task was accepted, and
 * the caller then waits forever for work nobody will do.
 */
class MailStore(path: Path) : AutoCloseable {

    /**
     * How many rows were dropped because the storage worker could not keep up.
     *
     * An [AtomicInteger] rather than a `var`: the counter is incremented from the rejection path,
     * which runs on **the caller's** thread, and `++` on a plain `Int` is read-modify-write — two
     * concurrent rejections could lose one between them, and the number an operator reads at the end
     * of a bad minute is exactly the number they would act on.
     *
     * Declared **before** the worker because the worker's rejection handler refers to it: a forward
     * reference inside an initializer is a cycle the compiler has to resolve, and there is no reason
     * to make it.
     */
    private val droppedCount = AtomicInteger(0)

    /**
     * Set once [close] starts, so a submission that races the close is not counted as a lost row.
     */
    private val shuttingDown = AtomicBoolean(false)

    /**
     * One serial worker with a **bounded** queue.
     *
     * Bounded because unbounded is not a plan: a server that outruns SQLite would grow a backlog
     * until the heap ran out. Bounded turns the pressure into a counted drop, and the drop is
     * counted rather than swallowed so an operator can see it happened.
     */
    private val worker = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(QUEUE_LIMIT),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "meowo-chat-mail").apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { _, _ ->
            // Counted only while the worker is still running: a submission that arrives after
            // `close()` is not a row lost to a busy queue.
            if (!shuttingDown.get()) droppedCount.incrementAndGet()
            throw java.util.concurrent.RejectedExecutionException("mail store queue is full ($QUEUE_LIMIT tasks)")
        },
    )

    private val connection: Connection

    /** How many rows were dropped because the storage worker could not keep up. */
    val dropped: Int get() = droppedCount.get()

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
                    CREATE TABLE IF NOT EXISTS mail (
                        id         TEXT PRIMARY KEY,
                        recipient  TEXT NOT NULL,
                        sender     TEXT NOT NULL,
                        sender_nm  TEXT NOT NULL,
                        body       TEXT NOT NULL,
                        sent_at    INTEGER NOT NULL,
                        is_read    INTEGER NOT NULL DEFAULT 0,
                        replied    INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                // Mailbox listings are always "newest first for this player", and ageing keeps a
                // bounded box from scanning history that will never be shown again.
                s.execute("CREATE INDEX IF NOT EXISTS mail_recipient ON mail(recipient, sent_at DESC)")
            }
        } catch (e: Exception) {
            connection.close()
            shuttingDown.set(true)
            worker.shutdownNow()
            throw e
        }
    }

    /** Everything held for one recipient, oldest first. */
    fun loadFor(recipient: UUID): CompletableFuture<List<Mail>> = submit {
        connection.prepareStatement(
            "SELECT id, sender, sender_nm, body, sent_at, is_read, replied FROM mail " +
                "WHERE recipient=? ORDER BY sent_at ASC",
        ).use { st ->
            st.setString(1, recipient.toString())
            st.executeQuery().use { rs ->
                val out = ArrayList<Mail>()
                while (rs.next()) {
                    // A row written by a newer build must not make this one fail to start.
                    val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                    val sender = runCatching { UUID.fromString(rs.getString(2)) }.getOrNull() ?: continue
                    out += Mail(
                        id = id,
                        from = sender,
                        fromName = rs.getString(3),
                        text = rs.getString(4),
                        atEpochMillis = rs.getLong(5),
                        read = rs.getInt(6) != 0,
                        replied = rs.getInt(7) != 0,
                    )
                }
                out
            }
        }
    }

    /** Recipients that still hold something, so start can hydrate only those boxes. */
    fun recipients(): CompletableFuture<Set<UUID>> = submit {
        connection.createStatement().use { s ->
            s.executeQuery("SELECT DISTINCT recipient FROM mail").use { rs ->
                val out = HashSet<UUID>()
                while (rs.next()) runCatching { UUID.fromString(rs.getString(1)) }.getOrNull()?.let(out::add)
                out
            }
        }
    }

    fun insert(mail: Mail, recipient: UUID): CompletableFuture<Unit> = submit {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO mail (id, recipient, sender, sender_nm, body, sent_at, is_read, replied) " +
                "VALUES (?,?,?,?,?,?,?,?)",
        ).use { st ->
            st.setString(1, mail.id.toString())
            st.setString(2, recipient.toString())
            st.setString(3, mail.from.toString())
            st.setString(4, mail.fromName)
            st.setString(5, mail.text)
            st.setLong(6, mail.atEpochMillis)
            st.setInt(7, if (mail.read) 1 else 0)
            st.setInt(8, if (mail.replied) 1 else 0)
            st.executeUpdate()
        }
        Unit
    }

    fun markRead(recipient: UUID, ids: Set<UUID>): CompletableFuture<Unit> = submit {
        if (ids.isEmpty()) return@submit Unit
        connection.prepareStatement("UPDATE mail SET is_read=1 WHERE recipient=? AND id=?").use { st ->
            for (id in ids) {
                st.setString(1, recipient.toString())
                st.setString(2, id.toString())
                st.addBatch()
            }
            st.executeBatch()
        }
        Unit
    }

    fun markReplied(recipient: UUID, sender: UUID): CompletableFuture<Unit> = submit {
        connection.prepareStatement("UPDATE mail SET replied=1, is_read=1 WHERE recipient=? AND sender=?").use { st ->
            st.setString(1, recipient.toString())
            st.setString(2, sender.toString())
            st.executeUpdate()
        }
        Unit
    }

    /** Drops rows beyond a box's cap so the table cannot outgrow memory forever. */
    fun trim(recipient: UUID, keep: Int): CompletableFuture<Unit> = submit {
        connection.prepareStatement(
            "DELETE FROM mail WHERE recipient=? AND id NOT IN " +
                "(SELECT id FROM mail WHERE recipient=? ORDER BY sent_at DESC LIMIT ?)",
        ).use { st ->
            st.setString(1, recipient.toString())
            st.setString(2, recipient.toString())
            st.setInt(3, keep)
            st.executeUpdate()
        }
        Unit
    }

    /**
     * Deletes every row this player is on either side of, and says how many went.
     *
     * This is the durable half of "forget". Without it the rows stayed in `mail.db` and the next
     * start-up re-hydrated them from there — so a mailbox the plugin had "forgotten" came back,
     * words and all, after a restart.
     *
     * **Both directions**: the mail addressed *to* them, and the mail they *sent* to other people.
     * The chat log deletes both directions for the same reason, and the reasoning is the same — a
     * person's own words are theirs wherever they landed.
     *
     * **Synchronous on purpose**: the caller is a player standing in front of a `/meo forget`, and a
     * forget that answers "done" before the rows are actually gone is the version of this feature
     * nobody should ship. The table is indexed by recipient and holds at most `chat.mail-capacity`
     * rows per player, so the wait is bounded and small.
     */
    fun forget(player: UUID): Int = try {
        submit {
            connection.prepareStatement("DELETE FROM mail WHERE recipient=? OR sender=?").use { st ->
                st.setString(1, player.toString())
                st.setString(2, player.toString())
                st.executeUpdate()
            }
        }.get(5, TimeUnit.SECONDS)
    } catch (error: Exception) {
        throw IllegalStateException("could not clear the mailbox for $player", error)
    }

    fun count(): CompletableFuture<Int> = submit {
        connection.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM mail").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /**
     * Hands a block to the worker and returns a future for its result.
     *
     * The rejection path returns an **already-failed** future rather than throwing into the
     * caller's chat handler, and rather than handing back a future that never completes.
     */
    private fun <T> submit(block: () -> T): CompletableFuture<T> = try {
        CompletableFuture.supplyAsync(java.util.function.Supplier(block), worker)
    } catch (rejected: java.util.concurrent.RejectedExecutionException) {
        CompletableFuture.failedFuture(rejected)
    }

    override fun close() {
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

    private companion object {
        /** Bounded so a stalled disk cannot grow the backlog without limit. */
        const val QUEUE_LIMIT = 1024
    }
}
