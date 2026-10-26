package com.meowo.home

import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Dedicated serial worker. Paid location edits retain their old value until the tax is settled. */
class HomeStore(path: Path) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r,"meowo-home-storage").apply { isDaemon=true } }
    private val db: java.sql.Connection
    init {
        path.toAbsolutePath().parent.toFile().mkdirs(); Class.forName("org.sqlite.JDBC")
        db=DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try { db.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA busy_timeout=5000")
            it.execute("CREATE TABLE IF NOT EXISTS homes(owner TEXT NOT NULL,slot INTEGER NOT NULL CHECK(slot BETWEEN 1 AND 1000),data TEXT NOT NULL,ticket TEXT UNIQUE,previous TEXT,PRIMARY KEY(owner,slot))")
            val legacy=it.executeQuery("SELECT sql FROM sqlite_master WHERE name='homes'").use { rs -> rs.next() && rs.getString(1).contains("BETWEEN 1 AND 10)") }
            if(legacy) {
                it.execute("BEGIN IMMEDIATE")
                try {
                    it.execute("ALTER TABLE homes RENAME TO homes_old")
                    it.execute("CREATE TABLE homes(owner TEXT NOT NULL,slot INTEGER NOT NULL CHECK(slot BETWEEN 1 AND 1000),data TEXT NOT NULL,ticket TEXT UNIQUE,previous TEXT,PRIMARY KEY(owner,slot))")
                    it.execute("INSERT INTO homes SELECT * FROM homes_old")
                    it.execute("DROP TABLE homes_old")
                    it.execute("COMMIT")
                } catch(error:Exception) { it.execute("ROLLBACK");throw error }
            }
        } } catch(e: Exception) { db.close(); worker.shutdown(); throw e }
    }
    private fun <T> submit(block: ()->T): CompletableFuture<T> = CompletableFuture.supplyAsync(java.util.function.Supplier(block),worker)
    fun all(): CompletableFuture<List<Home>> = submit {
        db.createStatement().use { s -> s.executeQuery("SELECT data FROM homes WHERE ticket IS NULL ORDER BY owner,slot").use { r ->
            buildList { while(r.next()) add(Home.decode(r.getString(1))) }
        } }
    }
    fun pending(): CompletableFuture<List<UUID>> = submit { db.createStatement().use { s ->
        s.executeQuery("SELECT ticket FROM homes WHERE ticket IS NOT NULL").use { r -> buildList { while(r.next()) add(UUID.fromString(r.getString(1))) } }
    } }
    private fun current(owner: UUID,slot: Int): Pair<String,String?>? = db.prepareStatement("SELECT data,ticket FROM homes WHERE owner=? AND slot=?").use { s ->
        s.setString(1,owner.toString());s.setInt(2,slot);s.executeQuery().use { r -> if(r.next()) r.getString(1) to r.getString(2) else null }
    }
    private fun <T> atomic(block: ()->T): T {
        db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try { val result=block();db.createStatement().use { it.execute("COMMIT") };return result }
        catch(e:Exception) { try { db.createStatement().use { it.execute("ROLLBACK") } } catch(r:Exception) {e.addSuppressed(r)};throw e }
    }
    fun stage(home: Home, expected: Home?, ticket: UUID, limit: Int) = submit { atomic {
        val old=current(home.owner,home.slot)
        check(old?.first == expected?.encode() && old?.second == null) { "Home changed while editing" }
        if(old==null) db.prepareStatement("SELECT COUNT(*) FROM homes WHERE owner=?").use { s ->
            s.setString(1,home.owner.toString());s.executeQuery().use { it.next();check(it.getInt(1)<limit && home.slot<=limit) { "Home limit exceeded" } }
        }
        db.prepareStatement("INSERT INTO homes(owner,slot,data,ticket,previous) VALUES (?,?,?,?,?) ON CONFLICT(owner,slot) DO UPDATE SET data=excluded.data,ticket=excluded.ticket,previous=excluded.previous").use { s ->
            s.setString(1,home.owner.toString());s.setInt(2,home.slot);s.setString(3,home.encode());s.setString(4,ticket.toString());s.setString(5,expected?.encode());s.executeUpdate()
        }; Unit
    } }
    fun finish(ticket: UUID, committed: Boolean) = submit { atomic {
        if(committed) db.prepareStatement("UPDATE homes SET ticket=NULL,previous=NULL WHERE ticket=?").use { it.setString(1,ticket.toString());it.executeUpdate() }
        else {
            db.prepareStatement("DELETE FROM homes WHERE ticket=? AND previous IS NULL").use { it.setString(1,ticket.toString());it.executeUpdate() }
            db.prepareStatement("UPDATE homes SET data=previous,ticket=NULL,previous=NULL WHERE ticket=? AND previous IS NOT NULL").use { it.setString(1,ticket.toString());it.executeUpdate() }
        }; Unit
    } }
    fun update(home: Home,expected: Home) = submit { atomic {
        check(current(home.owner,home.slot) == (expected.encode() to null)) { "Home changed while editing" }
        db.prepareStatement("UPDATE homes SET data=? WHERE owner=? AND slot=?").use { s -> s.setString(1,home.encode());s.setString(2,home.owner.toString());s.setInt(3,home.slot);s.executeUpdate() };Unit
    } }
    fun delete(home: Home) = submit { atomic {
        check(current(home.owner,home.slot) == (home.encode() to null)) { "Home changed while editing" }
        db.prepareStatement("DELETE FROM homes WHERE owner=? AND slot=?").use { s ->s.setString(1,home.owner.toString());s.setInt(2,home.slot);s.executeUpdate() };Unit
    } }
    override fun close() {
        val closed=submit { db.close() };worker.shutdown()
        closed.get(10,TimeUnit.SECONDS);check(worker.awaitTermination(10,TimeUnit.SECONDS)) { "Home worker failed to stop" }
    }
}
