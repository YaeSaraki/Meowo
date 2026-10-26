package com.meowo.pomodoro.infrastructure.persistence

import com.meowo.pomodoro.application.repository.FocusLogRepository
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Records every WORK round the player completes. Sits on the same SQLite file as
 * [SqlitePresetRepository]; the two repositories share the worker thread pool by living in
 * separate `init` blocks, but in practice the focus log only writes once per round so contention
 * is negligible.
 *
 * The `preset_name` column is a snapshot of the name at completion time. A player who renames a
 * custom preset should still see their old focus rounds under the name they earned them under.
 */
class SqliteFocusLogRepository(path: Path) : FocusLogRepository {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "meowo-pomodoro-storage").apply { isDaemon = true }
    }
    private val db: Connection

    init {
        path.toAbsolutePath().parent.toFile().mkdirs()
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try {
            db.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL")
                s.execute("PRAGMA busy_timeout=5000")
                s.execute(
                    """CREATE TABLE IF NOT EXISTS focus_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner TEXT NOT NULL,
                        preset_id TEXT NOT NULL,
                        preset_name TEXT NOT NULL,
                        work_session INTEGER NOT NULL CHECK(work_session > 0),
                        duration_seconds INTEGER NOT NULL CHECK(duration_seconds > 0),
                        finished_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
                    )"""
                )
                s.execute("CREATE INDEX IF NOT EXISTS focus_log_owner ON focus_log(owner, finished_at DESC)")
            }
        } catch (e: Exception) {
            db.close(); worker.shutdown(); throw e
        }
    }

    private fun <T> submit(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(java.util.function.Supplier(block), worker)

    @Synchronized
    override fun logWork(owner: UUID, presetId: String, presetName: String, workSession: Int, durationSeconds: Int): CompletableFuture<Unit> = submit {
        require(durationSeconds > 0) { "duration must be positive" }
        db.prepareStatement(
            "INSERT INTO focus_log(owner,preset_id,preset_name,work_session,duration_seconds) VALUES(?,?,?,?,?)"
        ).use { s ->
            s.setString(1, owner.toString())
            s.setString(2, presetId)
            s.setString(3, presetName)
            s.setInt(4, workSession)
            s.setInt(5, durationSeconds)
            s.executeUpdate()
        }
        Unit
    }

    @Synchronized
    override fun totalSeconds(owner: UUID): CompletableFuture<Long> = submit {
        db.prepareStatement("SELECT COALESCE(SUM(duration_seconds),0) FROM focus_log WHERE owner=?").use { s ->
            s.setString(1, owner.toString())
            s.executeQuery().use { r -> r.next(); r.getLong(1) }
        }
    }

    @Synchronized
    override fun secondsOnDate(owner: UUID, date: String): CompletableFuture<Long> = submit {
        db.prepareStatement("SELECT COALESCE(SUM(duration_seconds),0) FROM focus_log WHERE owner=? AND date(finished_at)=?").use { s ->
            s.setString(1, owner.toString())
            s.setString(2, date)
            s.executeQuery().use { r -> r.next(); r.getLong(1) }
        }
    }

    override fun close() {
        val close = submit { db.close() }
        worker.shutdown()
        try {
            close.get(10, TimeUnit.SECONDS)
        } finally {
            worker.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
