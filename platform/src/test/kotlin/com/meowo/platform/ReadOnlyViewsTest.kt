package com.meowo.platform

import com.meowo.api.ChargeState
import com.meowo.api.EconomyAPI
import com.meowo.api.EconomyException
import com.meowo.api.HomeMenuAPI
import com.meowo.api.HomeMenuEntry
import com.meowo.api.SettlementAPI
import com.meowo.api.TaxAssessment
import com.meowo.api.TaxReason
import com.meowo.api.TeleportCharge
import com.meowo.api.TransferReceipt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class ReadOnlyViewsTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val ticket = UUID.randomUUID()

    /** Records every method the projection was able to reach. Should stay at the reader-only ones. */
    private class SpyEconomy : EconomyAPI {
        val called = mutableListOf<String>()
        override fun balance(player: UUID): CompletionStage<BigDecimal> {
            called += "balance"
            return CompletableFuture.completedFuture(BigDecimal("100.00"))
        }

        override fun deposit(player: UUID, amount: BigDecimal): CompletionStage<BigDecimal> {
            called += "deposit"
            return CompletableFuture.completedFuture(amount)
        }

        override fun withdraw(player: UUID, amount: BigDecimal): CompletionStage<BigDecimal> {
            called += "withdraw"
            return CompletableFuture.completedFuture(amount)
        }

        override fun setBalance(player: UUID, amount: BigDecimal): CompletionStage<BigDecimal> {
            called += "setBalance"
            return CompletableFuture.completedFuture(amount)
        }
    }

    private class SpySettlement(private val payer: UUID) : SettlementAPI {
        val called = mutableListOf<String>()
        override fun transfer(from: UUID, to: UUID, amount: BigDecimal): CompletionStage<TransferReceipt> {
            called += "transfer"
            return CompletableFuture.failedFuture(EconomyException(com.meowo.api.EconomyError.UNAVAILABLE))
        }

        override fun teleportFee(player: UUID): CompletionStage<BigDecimal> {
            called += "teleportFee"
            return CompletableFuture.completedFuture(BigDecimal("5.00"))
        }

        override fun reserveTeleport(ticket: UUID, player: UUID): CompletionStage<TeleportCharge> {
            called += "reserveTeleport"
            return CompletableFuture.failedFuture(EconomyException(com.meowo.api.EconomyError.UNAVAILABLE))
        }

        override fun reserveTeleport(ticket: UUID, requester: UUID, payer: UUID): CompletionStage<TeleportCharge> {
            called += "reserveTeleport"
            return CompletableFuture.failedFuture(EconomyException(com.meowo.api.EconomyError.UNAVAILABLE))
        }

        override fun settleTeleport(ticket: UUID, success: Boolean): CompletionStage<TeleportCharge> {
            called += "settleTeleport"
            return CompletableFuture.failedFuture(EconomyException(com.meowo.api.EconomyError.UNAVAILABLE))
        }

        override fun cancelTeleport(ticket: UUID): CompletionStage<Void> {
            called += "cancelTeleport"
            return CompletableFuture.failedFuture(EconomyException(com.meowo.api.EconomyError.UNAVAILABLE))
        }

        override fun claimDaily(player: UUID): CompletionStage<BigDecimal> {
            called += "claimDaily"
            return CompletableFuture.failedFuture(EconomyException(com.meowo.api.EconomyError.UNAVAILABLE))
        }

        override fun treasury(): CompletionStage<BigDecimal> {
            called += "treasury"
            return CompletableFuture.completedFuture(BigDecimal("5000.00"))
        }

        override fun reserveTax(ticket: UUID, player: UUID, assessment: TaxAssessment): CompletionStage<TeleportCharge> {
            called += "reserveTax"
            return CompletableFuture.failedFuture(EconomyException(com.meowo.api.EconomyError.UNAVAILABLE))
        }

        override fun chargeStatus(ticket: UUID): CompletionStage<TeleportCharge?> {
            called += "chargeStatus"
            return CompletableFuture.completedFuture(TeleportCharge(ticket, payer, BigDecimal("5.00"), ChargeState.PENDING))
        }
    }

    // ── the guarantee ────────────────────────────────────────────────────────────────────────

    /**
     * The point of the whole file.
     *
     * A reader is not a full API with the inconvenient parts removed at runtime; it is a type that
     * simply does not declare them. If somebody adds a `withdraw` to [BalanceReader] this fails, and
     * the failure names the exact thing that was weakened.
     */
    @Test fun `the readers declare no write method`() {
        val writes = setOf(
            "deposit", "withdraw", "setBalance", "transfer", "claimDaily", "reserveTax",
            "reserveTeleport", "settleTeleport", "cancelTeleport",
        )
        for (type in listOf(BalanceReader::class, SettlementReader::class, HomeReader::class)) {
            val declared = type.java.methods.map { it.name }.toSet()
            assertTrue(
                declared.none { it in writes },
                "${type.simpleName} exposes a write: ${declared.intersect(writes)}",
            )
        }
    }

    @Test fun `the readers declare what they are allowed to offer`() {
        assertEquals(setOf("balance"), BalanceReader::class.java.methods.map { it.name }.toSet())
        assertEquals(
            setOf("teleportFee", "treasury", "chargeStatus"),
            SettlementReader::class.java.methods.map { it.name }.toSet(),
        )
    }

    // ── the projection behaves like the thing behind it ─────────────────────────────────────

    @Test fun `balance reads through and nothing else is reachable`() {
        val spy = SpyEconomy()
        val reader = ReadOnly.balance(spy)
        assertEquals(BigDecimal("100.00"), reader.balance(alice).toCompletableFuture().join())
        assertEquals(listOf("balance"), spy.called)
    }

    @Test fun `settlement reads through without touching a single write`() {
        val spy = SpySettlement(alice)
        val reader = ReadOnly.settlement(spy)
        assertEquals(BigDecimal("5.00"), reader.teleportFee(alice).toCompletableFuture().join())
        assertEquals(BigDecimal("5000.00"), reader.treasury().toCompletableFuture().join())
        val charge = reader.chargeStatus(ticket).toCompletableFuture().join()
        assertEquals(ChargeState.PENDING, charge!!.state)
        assertEquals(listOf("teleportFee", "treasury", "chargeStatus"), spy.called)
    }

    @Test fun `a home is reduced to a fact, with no menu slot attached`() {
        val api = object : HomeMenuAPI {
            override fun capacity(owner: UUID) = 5
            override fun entries(owner: UUID) = listOf(
                HomeMenuEntry(0, "农场", 0x00FF00, "GRASS_BLOCK", "world", 1.0, 64.0, 2.0, true, "solid"),
            )
        }
        val entry = ReadOnly.homes(api).entries(alice).single()
        assertEquals("农场", entry.name)
        assertEquals(64.0, entry.y)
        // The menu slot, the colour and the visibility flag are rendering concerns; a cat saying
        // "your farm is at y=64" must not have a slot index in its hand.
        assertEquals(listOf("name", "world", "x", "y", "z"), entry.javaClass.declaredFields.map { it.name }.sorted())
    }

    @Test fun `capacity still comes from the API it was asked for`() {
        val api = object : HomeMenuAPI {
            override fun capacity(owner: UUID) = 7
            override fun entries(owner: UUID) = emptyList<HomeMenuEntry>()
        }
        assertEquals(7, ReadOnly.homes(api).capacity(alice))
    }
}
