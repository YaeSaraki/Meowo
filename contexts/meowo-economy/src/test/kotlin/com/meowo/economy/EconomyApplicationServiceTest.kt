package com.meowo.economy

import com.meowo.api.*
import com.meowo.api.event.BalanceChanged
import com.meowo.economy.application.EconomyApplicationService
import com.meowo.economy.domain.model.*
import com.meowo.economy.domain.repository.WalletRepository
import com.meowo.economy.domain.service.EconomyDomainService
import com.meowo.shared.event.EventBus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.*

class EconomyApplicationServiceTest {
    private class FakeRepository : WalletRepository {
        val wallets = mutableMapOf<UUID,Wallet>(); var fail = false
        var beforeChange: () -> Unit = {}
        override fun getOrCreate(player: UUID) = wallets.getOrPut(player) { Wallet(player) }
        override fun change(player: UUID, operation: Operation, update: (Wallet)->Wallet): Transaction {
            beforeChange()
            val before = getOrCreate(player); val after = update(before)
            if (fail) error("disk failure")
            wallets[player] = after
            return Transaction(UUID.randomUUID(),player,before.balance,after.balance,operation)
        }
        override fun close() {}
    }
    @Test fun `events observe committed balance and failed operations emit no events`() {
        val id = UUID.randomUUID(); val repo = FakeRepository(); val failures = mutableListOf<Exception>()
        val bus = EventBus { _, e -> failures += e }; val events = mutableListOf<BalanceChanged>()
        bus.subscribe(BalanceChanged::class.java) { assertEquals(it.after,repo.wallets[id]!!.balance.decimal()); events += it }
        bus.subscribe(BalanceChanged::class.java) { error("subscriber failure") }
        val app = EconomyApplicationService(EconomyDomainService(repo),bus)
        app.use {
            assertEquals(BigDecimal("2.00"),it.deposit(id,BigDecimal("2")).get(5,TimeUnit.SECONDS))
            assertThrows(ExecutionException::class.java) { it.withdraw(id,BigDecimal("3")).get(5,TimeUnit.SECONDS) }
            repo.fail=true
            assertThrows(ExecutionException::class.java) { it.deposit(id,BigDecimal.ONE).get(5,TimeUnit.SECONDS) }
            repo.fail=false
            assertEquals(BigDecimal("1.00"),it.withdraw(id,BigDecimal.ONE).get(5,TimeUnit.SECONDS))
            assertEquals(BigDecimal("0.00"),it.setBalance(id,BigDecimal.ZERO).get(5,TimeUnit.SECONDS))
        }
        assertEquals(3,events.size); assertEquals(3,failures.size)
        val error = assertThrows(ExecutionException::class.java) { app.balance(id).get(5,TimeUnit.SECONDS) }
        assertEquals(EconomyError.UNAVAILABLE,(error.cause as EconomyException).code)
        bus.close()
    }
    @Test fun `parallel deposits serialize and shutdown drains accepted work`() {
        val id = UUID.randomUUID(); val repo = FakeRepository(); val bus = EventBus { _, e -> throw e }
        val app = EconomyApplicationService(EconomyDomainService(repo),bus)
        val results = (1..200).map { app.deposit(id,BigDecimal("0.01")) }
        app.close(); assertTrue(results.all { it.isDone })
        assertEquals(BigDecimal("2.00"),repo.wallets[id]!!.balance.decimal()); bus.close()
    }
    @Test fun `shutdown timeout still closes storage after in flight commit and closed bus does not fail payment`() {
        val started=CountDownLatch(1); val release=CountDownLatch(1); val closed=CountDownLatch(1)
        val repo=FakeRepository(); repo.beforeChange={ started.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
        val bus=EventBus { _, e -> throw e }
        val app=EconomyApplicationService(EconomyDomainService(repo),bus,{ closed.countDown() },java.time.Duration.ofMillis(10))
        val payment=app.deposit(UUID.randomUUID(),BigDecimal.ONE)
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { app.close() }
            assertEquals(1,closed.count)
            bus.close()
        } finally { release.countDown() }
        assertEquals(BigDecimal("1.00"),payment.get(5,TimeUnit.SECONDS))
        assertTrue(closed.await(5,TimeUnit.SECONDS))
        app.close()
    }

}
