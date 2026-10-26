package com.meowo.economy.domain

import com.meowo.api.EconomyException
import com.meowo.economy.domain.model.*
import com.meowo.economy.domain.repository.WalletRepository
import com.meowo.economy.domain.service.EconomyDomainService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class WalletTest {
    @Test fun `deposit withdraw and set preserve identity without mutating original`() {
        val original = Wallet(UUID.randomUUID())
        val funded = original.change(Operation.DEPOSIT, Money.fromMinor(100))
        val spent = funded.change(Operation.WITHDRAW, Money.fromMinor(40))
        assertEquals(Money.ZERO,original.balance); assertEquals(original.player,spent.player)
        assertEquals(60,spent.balance.minor); assertEquals(0,spent.change(Operation.SET,Money.ZERO).balance.minor)
        assertThrows(EconomyException::class.java) { original.change(Operation.WITHDRAW,Money.fromMinor(1)) }
    }
    @Test fun `domain service delegates atomic update to repository`() {
        val id = UUID.randomUUID(); var stored = Wallet(id)
        val repo = object : WalletRepository {
            override fun getOrCreate(player: UUID) = stored
            override fun change(player: UUID, operation: Operation, update: (Wallet)->Wallet): Transaction {
                val before = stored; stored = update(stored)
                return Transaction(UUID.randomUUID(),player,before.balance,stored.balance,operation)
            }
            override fun close() {}
        }
        val domain = EconomyDomainService(repo)
        assertEquals(Money.ZERO,domain.balance(id))
        val tx = domain.change(id,Operation.DEPOSIT,Money.fromMinor(100))
        assertEquals(id,tx.player); assertEquals(Money.ZERO,tx.before); assertEquals(domain.balance(id),tx.after)
    }
}
