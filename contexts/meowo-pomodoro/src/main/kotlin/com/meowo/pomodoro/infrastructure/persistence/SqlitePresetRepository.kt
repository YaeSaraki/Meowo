package com.meowo.pomodoro.infrastructure.persistence

import com.meowo.pomodoro.application.repository.PresetRepository
import com.meowo.pomodoro.domain.Preset
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SQLite-backed player preset storage on a dedicated serial worker, exactly mirroring
 * `meowo-home.HomeStore`'s threading model: writes never run on the tick thread, but the caller's
 * `CompletableFuture` lets the calling code stay asynchronous without blocking.
 *
 * The schema is intentionally narrow: the same row layout also exists in `focus_log` (different
 * table), and the column types match so a future migration to store preset snapshots next to
 * focus log entries is mechanical.
 */
class SqlitePresetRepository(path: Path) : PresetRepository {

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
                    """CREATE TABLE IF NOT EXISTS preset (
                        owner TEXT NOT NULL,
                        preset_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        icon TEXT NOT NULL,
                        enchanted INTEGER NOT NULL CHECK(enchanted IN (0,1)),
                        work_seconds INTEGER NOT NULL CHECK(work_seconds > 0),
                        break_seconds INTEGER NOT NULL CHECK(break_seconds > 0),
                        long_break_seconds INTEGER NOT NULL CHECK(long_break_seconds > 0),
                        sessions INTEGER NOT NULL CHECK(sessions > 0),
                        PRIMARY KEY(owner, preset_id)
                    )"""
                )
            }
        } catch (e: Exception) {
            db.close(); worker.shutdown(); throw e
        }
    }

    private fun <T> submit(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(java.util.function.Supplier(block), worker)

    @Synchronized
    override fun all(owner: UUID): CompletableFuture<List<Preset>> = submit {
        db.prepareStatement("SELECT preset_id,name,icon,enchanted,work_seconds,break_seconds,long_break_seconds,sessions FROM preset WHERE owner=? ORDER BY preset_id").use { s ->
            s.setString(1, owner.toString())
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(r.toPreset(owner = owner, builtin = false))
                }
            }
        }
    }

    @Synchronized
    override fun save(owner: UUID, preset: Preset, limit: Int): CompletableFuture<Preset> = submit {
        require(!preset.builtin) { "PresetRepository stores only custom presets" }
        db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            // The cap is on how many custom presets a player **has**, so editing one of them has to
            // keep working when they are already at the cap — only a **new** id counts against it.
            //
            // Counting the *other* rows says that in one query: an existing id leaves `limit - 1` of
            // them and a new one leaves `limit`, so only the new one can fail the check. Counting
            // every row instead made editing a preset fail exactly when the player was full.
            db.prepareStatement("SELECT COUNT(*) FROM preset WHERE owner=? AND preset_id<>?").use { s ->
                s.setString(1, owner.toString())
                s.setString(2, preset.id)
                s.executeQuery().use { r ->
                    r.next()
                    check(r.getInt(1) < limit) { "preset limit reached" }
                }
            }
            db.prepareStatement(
                """INSERT INTO preset(owner,preset_id,name,icon,enchanted,work_seconds,break_seconds,long_break_seconds,sessions)
                   VALUES(?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(owner,preset_id) DO UPDATE SET
                     name=excluded.name, icon=excluded.icon, enchanted=excluded.enchanted,
                     work_seconds=excluded.work_seconds, break_seconds=excluded.break_seconds,
                     long_break_seconds=excluded.long_break_seconds, sessions=excluded.sessions""".trimIndent()
            ).use { s ->
                s.setString(1, owner.toString())
                s.setString(2, preset.id)
                s.setString(3, preset.name)
                s.setString(4, preset.icon)
                s.setInt(5, if (preset.enchanted) 1 else 0)
                s.setInt(6, preset.workSeconds)
                s.setInt(7, preset.breakSeconds)
                s.setInt(8, preset.longBreakSeconds)
                s.setInt(9, preset.sessions)
                s.executeUpdate()
            }
            db.createStatement().use { it.execute("COMMIT") }
            preset
        } catch (e: Exception) {
            try { db.createStatement().use { it.execute("ROLLBACK") } } catch (rb: Exception) { e.addSuppressed(rb) }
            throw e
        }
    }

    @Synchronized
    override fun delete(owner: UUID, presetId: String): CompletableFuture<Boolean> = submit {
        db.prepareStatement("DELETE FROM preset WHERE owner=? AND preset_id=?").use { s ->
            s.setString(1, owner.toString())
            s.setString(2, presetId)
            s.executeUpdate() > 0
        }
    }

    @Synchronized
    override fun find(owner: UUID, presetId: String): CompletableFuture<Preset?> = submit {
        db.prepareStatement("SELECT preset_id,name,icon,enchanted,work_seconds,break_seconds,long_break_seconds,sessions FROM preset WHERE owner=? AND preset_id=?").use { s ->
            s.setString(1, owner.toString()); s.setString(2, presetId)
            s.executeQuery().use { r -> if (r.next()) r.toPreset(owner = owner, builtin = false) else null }
        }
    }

    @Synchronized
    override fun isFull(owner: UUID, limit: Int): CompletableFuture<Boolean> = submit {
        db.prepareStatement("SELECT COUNT(*) FROM preset WHERE owner=?").use { s ->
            s.setString(1, owner.toString())
            s.executeQuery().use { r -> r.next(); r.getInt(1) >= limit }
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

    private fun java.sql.ResultSet.toPreset(@Suppress("UNUSED_PARAMETER") owner: UUID, builtin: Boolean): Preset = Preset(
        id = getString("preset_id"),
        name = getString("name"),
        icon = getString("icon"),
        enchanted = getInt("enchanted") == 1,
        workSeconds = getInt("work_seconds"),
        breakSeconds = getInt("break_seconds"),
        longBreakSeconds = getInt("long_break_seconds"),
        sessions = getInt("sessions"),
        builtin = builtin,
    )
}
