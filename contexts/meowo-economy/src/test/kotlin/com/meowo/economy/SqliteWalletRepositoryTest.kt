package com.meowo.economy

import com.meowo.api.EconomyException
import com.meowo.economy.domain.model.*
import com.meowo.economy.infrastructure.persistence.SqliteWalletRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.*

class SqliteWalletRepositoryTest {
    @TempDir lateinit var dir: Path
    @Test fun `restart persists balances and rejected withdrawals do not add ledger rows`() {
        val path = dir.resolve("wallets.db"); val id = UUID.randomUUID()
        SqliteWalletRepository(path).use { repo ->
            assertEquals(Money.ZERO,repo.getOrCreate(id).balance)
            repo.change(id,Operation.DEPOSIT) { it.change(Operation.DEPOSIT,Money.fromMinor(120)) }
            assertThrows(EconomyException::class.java) { repo.change(id,Operation.WITHDRAW) { it.change(Operation.WITHDRAW,Money.fromMinor(121)) } }
            assertThrows(IllegalArgumentException::class.java) { repo.change(id,Operation.SET) { Wallet(UUID.randomUUID()) } }
        }
        SqliteWalletRepository(path).use { assertEquals(120,it.getOrCreate(id).balance.minor) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM transactions").use { it.next(); assertEquals(1,it.getInt(1)) } } }
    }
    @Test fun `ledger failure rolls back wallet update`() {
        val path = dir.resolve("wallets.db"); val id = UUID.randomUUID()
        SqliteWalletRepository(path).use { repo ->
            repo.getOrCreate(id)
            DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use { it.execute("CREATE TRIGGER reject_ledger BEFORE INSERT ON transactions BEGIN SELECT RAISE(FAIL, 'injected failure'); END") } }
            assertThrows(Exception::class.java) { repo.change(id,Operation.DEPOSIT) { it.change(Operation.DEPOSIT,Money.fromMinor(10)) } }
            assertEquals(Money.ZERO,repo.getOrCreate(id).balance)
        }
    }
    @Test fun `independent database connections do not lose concurrent updates`() {
        val path = dir.resolve("wallets.db"); val id = UUID.randomUUID()
        SqliteWalletRepository(path).use { first -> SqliteWalletRepository(path).use { second ->
            val pool=Executors.newFixedThreadPool(2)
            try {
                listOf(first,second).map { repo -> pool.submit { repeat(50) { repo.change(id,Operation.DEPOSIT) { it.change(Operation.DEPOSIT,Money.fromMinor(1)) } } } }.forEach { it.get(10,TimeUnit.SECONDS) }
                assertEquals(100,first.getOrCreate(id).balance.minor)
            } finally { pool.shutdownNow() }
        } }
    }
}
