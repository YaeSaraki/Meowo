package com.meowo.economy

import com.meowo.api.*
import com.meowo.economy.domain.model.*
import com.meowo.economy.infrastructure.persistence.SqliteWalletRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SettlementRepositoryTest {
    @TempDir lateinit var dir: Path
    private val from = UUID.randomUUID(); private val to = UUID.randomUUID()
    private val day = LocalDate.of(2026,10,4)
    private fun money(value: String) = Money.of(BigDecimal(value))
    private fun set(repo: SqliteWalletRepository, player: UUID, amount: String) = repo.change(player,Operation.SET) { it.change(Operation.SET,money(amount)) }
    @Test fun `pay is atomic and rejects self zero insufficient and recipient overflow`() {
        SqliteWalletRepository(dir.resolve("eco.db")).use { repo ->
            set(repo,from,"100")
            val receipt = repo.transfer(from,to,money("20.55"))
            assertEquals(BigDecimal("79.24"),receipt.value.senderBalance)
            assertEquals(BigDecimal("20.55"),receipt.value.recipientBalance)
            assertEquals(2,receipt.changes.size)
            for ((target, amount) in listOf(from to "1", to to "0", to to "80"))
                assertThrows(EconomyException::class.java) { repo.transfer(from,target,money(amount)) }
            set(repo,to,"999999999999.99")
            assertThrows(EconomyException::class.java) { repo.transfer(from,to,money("1")) }
            assertEquals(money("79.24"),repo.getOrCreate(from).balance)
            assertEquals(money("0.21"),repo.treasury())
            assertEquals(BigDecimal("0.21"),receipt.value.tax)
            assertEquals(BigDecimal("20.76"),receipt.value.total)
        }
    }
    @Test fun `teleport reserve commit and duplicate completion collect tax exactly once`() {
        SqliteWalletRepository(dir.resolve("eco.db")).use { repo ->
            set(repo,from,"12345.67")
            val ticket = UUID.randomUUID()
            val charge = repo.reserve(ticket,from,EconomyPolicy()).value
            assertEquals(BigDecimal("12.35"),charge.fee)
            assertEquals(Money.ZERO,repo.treasury())
            assertEquals(money("12333.32"),repo.getOrCreate(from).balance)
            assertTrue(repo.reserve(ticket,from,EconomyPolicy()).changes.isEmpty())
            assertThrows(EconomyException::class.java) { repo.reserve(UUID.randomUUID(),from,EconomyPolicy()) }
            assertThrows(IllegalArgumentException::class.java) { repo.reserve(ticket,to,EconomyPolicy()) }
            assertEquals(ChargeState.COMMITTED,repo.settle(ticket,true).value.state)
            assertEquals(ChargeState.COMMITTED,repo.settle(ticket,false).value.state)
            assertEquals(money("12.35"),repo.treasury())
            assertTrue(repo.cancel(ticket).changes.isEmpty())
        }
    }
    @Test fun `failed teleport refunds once and restart recovers pending escrow`() {
        val path = dir.resolve("eco.db"); val ticket = UUID.randomUUID()
        SqliteWalletRepository(path).use { repo ->
            set(repo,from,"20")
            repo.reserve(ticket,from,EconomyPolicy())
            assertEquals(ChargeState.REFUNDED,repo.settle(ticket,false).value.state)
            assertTrue(repo.settle(ticket,true).changes.isEmpty())
            assertEquals(money("20"),repo.getOrCreate(from).balance)
            repo.reserve(UUID.randomUUID(),from,EconomyPolicy())
        }
        SqliteWalletRepository(path).use { repo ->
            assertEquals(1,repo.recoverPending().size)
            assertTrue(repo.recoverPending().isEmpty())
            assertEquals(money("20"),repo.getOrCreate(from).balance)
            assertEquals(Money.ZERO,repo.treasury())
            assertTrue(repo.cancel(UUID.randomUUID()).changes.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { repo.settle(UUID.randomUUID(),true) }
        }
    }
    @Test fun `reserved refunds retain headroom during incoming payments and admin sets`() {
        SqliteWalletRepository(dir.resolve("eco.db")).use { repo ->
            set(repo,from,"999999999999.99"); set(repo,to,"1000")
            val ticket = UUID.randomUUID(); repo.reserve(ticket,from,EconomyPolicy())
            assertThrows(EconomyException::class.java) { repo.transfer(to,from,money("1")) }
            assertThrows(EconomyException::class.java) { set(repo,from,"999999999999.99") }
            repo.cancel(ticket)
            assertEquals(Money.fromMinor(Money.MAX_MINOR),repo.getOrCreate(from).balance)
            assertEquals(money("1000"),repo.getOrCreate(to).balance)
        }
    }
    @Test fun `empty pool does not consume eligibility and daily claims persist across restarts`() {
        val path=dir.resolve("eco.db")
        SqliteWalletRepository(path).use { repo ->
            assertEquals(Money.ZERO,repo.claimDaily(to,day,money("1")).value)
            set(repo,from,"3000"); val ticket=UUID.randomUUID()
            repo.reserve(ticket,from,EconomyPolicy()); repo.settle(ticket,true)
            assertEquals(money("1"),repo.claimDaily(to,day,money("1")).value)
            assertEquals(Money.ZERO,repo.claimDaily(to,day,money("1")).value)
            assertEquals(money("2"),repo.treasury())
        }
        SqliteWalletRepository(path).use { repo ->
            assertEquals(Money.ZERO,repo.claimDaily(to,day,money("1")).value)
            assertEquals(money("2"),repo.claimDaily(to,day.plusDays(1),money("100")).value)
            assertEquals(money("3"),repo.getOrCreate(to).balance)
            assertEquals(Money.ZERO,repo.treasury())
        }
    }
    @Test fun `competing daily claims cannot drain treasury twice`() {
        val path=dir.resolve("eco.db")
        SqliteWalletRepository(path).use { first -> SqliteWalletRepository(path).use { second ->
            set(first,from,"5000"); val ticket=UUID.randomUUID()
            first.reserve(ticket,from,EconomyPolicy()); first.settle(ticket,true)
            val pool=Executors.newFixedThreadPool(2)
            try {
                val paid=listOf(first,second).map { repo -> pool.submit<Long> { repo.claimDaily(to,day,money("3")).value.minor } }.sumOf { it.get(5,TimeUnit.SECONDS) }
                assertEquals(300L,paid); assertEquals(money("2"),first.treasury())
            } finally { pool.shutdownNow() }
        } }
    }
    @Test fun `tax ledger failure rolls back commitment and permits refund`() {
        val path=dir.resolve("eco.db")
        SqliteWalletRepository(path).use { repo ->
            set(repo,from,"100"); val ticket=UUID.randomUUID(); repo.reserve(ticket,from,EconomyPolicy())
            DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use { it.execute("CREATE TRIGGER reject_tax BEFORE INSERT ON eco_ledger BEGIN SELECT RAISE(FAIL,'injected'); END") } }
            assertThrows(Exception::class.java) { repo.settle(ticket,true) }
            assertEquals(Money.ZERO,repo.treasury()); repo.cancel(ticket)
            assertEquals(money("100"),repo.getOrCreate(from).balance)
        }
    }
    @Test fun `insufficient balance cannot reserve and free teleports remain idempotent`() {
        SqliteWalletRepository(dir.resolve("eco.db")).use { repo ->
            assertThrows(EconomyException::class.java) { repo.reserve(UUID.randomUUID(),from,EconomyPolicy()) }
            val ticket=UUID.randomUUID()
            repo.reserve(ticket,from,EconomyPolicy(BigDecimal.ZERO,Money.ZERO,Money.ZERO))
            assertThrows(EconomyException::class.java) { repo.reserve(UUID.randomUUID(),from,EconomyPolicy()) }
            repo.settle(ticket,true)
            assertEquals(Money.ZERO,repo.treasury())
        }
    }
    @Test fun `pay requires amount plus tax and treasury ledger failure rolls back both wallets`() {
        val path=dir.resolve("eco.db")
        SqliteWalletRepository(path).use { repo ->
            set(repo,from,"100")
            assertThrows(EconomyException::class.java) { repo.transfer(from,to,money("100")) }
            assertEquals(money("100"),repo.getOrCreate(from).balance)
            assertEquals(Money.ZERO,repo.getOrCreate(to).balance)
            set(repo,from,"101")
            DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use {
                it.execute("CREATE TRIGGER reject_pay BEFORE INSERT ON eco_ledger BEGIN SELECT RAISE(FAIL,'injected'); END")
            } }
            assertThrows(Exception::class.java) { repo.transfer(from,to,money("100")) }
            assertEquals(money("101"),repo.getOrCreate(from).balance)
            assertEquals(Money.ZERO,repo.getOrCreate(to).balance)
            assertEquals(Money.ZERO,repo.treasury())
        }
    }
    @Test fun `sponsored fee uses applicant balance plus pay tax and refunds all to sponsor`() {
        SqliteWalletRepository(dir.resolve("eco.db")).use { repo ->
            set(repo,from,"0"); set(repo,to,"500000")
            val ticket=UUID.randomUUID()
            val charge=repo.reserve(ticket,from,to,EconomyPolicy()).value
            assertEquals(BigDecimal("1.00"),charge.teleportTax)
            assertEquals(BigDecimal("0.01"),charge.paymentTax)
            assertEquals(BigDecimal("1.01"),charge.fee)
            assertEquals(from,charge.requester); assertEquals(to,charge.player)
            assertEquals(money("499998.99"),repo.getOrCreate(to).balance)
            assertEquals(Money.ZERO,repo.getOrCreate(from).balance)
            assertEquals(Money.ZERO,repo.treasury())
            assertTrue(repo.reserve(ticket,from,to,EconomyPolicy()).changes.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { repo.reserve(ticket,to,from,EconomyPolicy()) }
            repo.cancel(ticket); repo.cancel(ticket)
            assertEquals(money("500000"),repo.getOrCreate(to).balance)
            assertEquals(Money.ZERO,repo.treasury())
        }
    }
    @Test fun `sponsored commit audits each tax once and startup refunds unsettled sponsored total`() {
        val path=dir.resolve("eco.db")
        SqliteWalletRepository(path).use { repo ->
            set(repo,from,"12345.67"); set(repo,to,"1000")
            val ticket=UUID.randomUUID()
            val charge=repo.reserve(ticket,from,to,EconomyPolicy()).value
            assertEquals(BigDecimal("12.35"),charge.teleportTax)
            assertEquals(BigDecimal("0.13"),charge.paymentTax)
            assertEquals(BigDecimal("12.48"),charge.fee)
            repo.settle(ticket,true); repo.settle(ticket,true)
            assertEquals(money("12.48"),repo.treasury())
            assertEquals(money("987.52"),repo.getOrCreate(to).balance)
            assertEquals(money("12345.67"),repo.getOrCreate(from).balance)
            repo.reserve(UUID.randomUUID(),from,to,EconomyPolicy())
        }
        SqliteWalletRepository(path).use { repo ->
            assertEquals(1,repo.recoverPending().size)
            assertEquals(money("987.52"),repo.getOrCreate(to).balance)
            assertEquals(money("12.48"),repo.treasury())
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT reason,delta FROM eco_ledger ORDER BY reason").use { r ->
                assertTrue(r.next()); assertEquals("PAY_TAX",r.getString(1)); assertEquals(13,r.getLong(2))
                assertTrue(r.next()); assertEquals("TP_TAX",r.getString(1)); assertEquals(1235,r.getLong(2))
                assertFalse(r.next())
            }
        } }
    }
    @Test fun `old databases migrate without changing balances or losing pending refunds`() {
        val path=dir.resolve("legacy.db"); val ticket=UUID.randomUUID()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use { s ->
            s.execute("CREATE TABLE wallets(player TEXT PRIMARY KEY,balance INTEGER NOT NULL)")
            s.execute("CREATE TABLE teleport_charges(id TEXT PRIMARY KEY,player TEXT NOT NULL,fee INTEGER NOT NULL,state TEXT NOT NULL,created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
            s.execute("CREATE TABLE transfers(id TEXT PRIMARY KEY,sender TEXT NOT NULL,recipient TEXT NOT NULL,amount INTEGER NOT NULL,created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
            s.execute("INSERT INTO wallets VALUES('$from',9900)")
            s.execute("INSERT INTO teleport_charges(id,player,fee,state) VALUES('$ticket','$from',100,'PENDING')")
        } }
        SqliteWalletRepository(path).use { repo ->
            assertEquals(money("99"),repo.getOrCreate(from).balance)
            val refund=repo.settle(ticket,false).value
            assertEquals(BigDecimal("1.00"),refund.teleportTax)
            assertEquals(BigDecimal("0.00"),refund.paymentTax)
            assertEquals(money("100"),repo.getOrCreate(from).balance)
            assertEquals(Money.ZERO,repo.treasury())
        }
        SqliteWalletRepository(path).use { repo ->
            assertTrue(repo.recoverPending().isEmpty())
            assertEquals(money("100"),repo.getOrCreate(from).balance)
        }
    }
    @Test fun `payment tax can be configured and funds daily subsidies`() {
        SqliteWalletRepository(dir.resolve("eco.db")).use { repo ->
            set(repo,from,"200")
            val result=repo.transfer(from,to,money("100"),EconomyPolicy(paymentRate=BigDecimal("0.02")))
            assertEquals(BigDecimal("2.00"),result.value.tax)
            assertEquals(money("2"),repo.treasury())
            assertEquals(money("1"),repo.claimDaily(UUID.randomUUID(),day,money("1")).value)
            assertEquals(money("1"),repo.treasury())
        }
    }

    @Test fun `home taxes use transactional wallet balance enter eco and recover like teleport escrow`() {
        val path=dir.resolve("home-tax.db")
        val assessment=TaxAssessment(TaxReason.HOME_SET,BigDecimal("0.05"),BigDecimal("100"),BigDecimal("2333"))
        val t=UUID.randomUUID()
        SqliteWalletRepository(path).use { repo ->
            set(repo,from,"10000.01")
            assertEquals(BigDecimal("500.01"),repo.reserveTax(t,from,assessment).value.fee)
            assertEquals(BigDecimal("9500.00"),repo.getOrCreate(from).balance.decimal())
            assertEquals(Money.ZERO,repo.treasury());assertEquals(ChargeState.PENDING,repo.chargeStatus(t)?.state)
            assertThrows(EconomyException::class.java) { repo.reserve(UUID.randomUUID(),from,EconomyPolicy()) }
            repo.settle(t,true);repo.settle(t,true)
            assertEquals(money("500.01"),repo.treasury())
            val distance=TaxAssessment(TaxReason.HOME_TRAVEL,minimum=BigDecimal("100"),maximum=BigDecimal("2333"),fixedAmount=BigDecimal("120.001"))
            repo.reserveTax(UUID.randomUUID(),from,distance)
        }
        SqliteWalletRepository(path).use { repo ->
            assertEquals(1,repo.recoverPending().size);assertEquals(BigDecimal("9500.00"),repo.getOrCreate(from).balance.decimal())
            assertEquals(BigDecimal("1.00"),repo.claimDaily(to,day,money("1")).value.decimal())
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { st ->
            st.executeQuery("SELECT reason,delta FROM eco_ledger WHERE reference='$t'").use { assertTrue(it.next());assertEquals("HOME_SET",it.getString(1));assertEquals(50001,it.getLong(2)) }
        } }
    }
}
