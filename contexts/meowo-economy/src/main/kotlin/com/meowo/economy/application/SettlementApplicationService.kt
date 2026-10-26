package com.meowo.economy.application

import com.meowo.api.*
import com.meowo.api.event.BalanceChanged
import com.meowo.economy.domain.model.*
import com.meowo.economy.domain.repository.*
import com.meowo.shared.event.EventBus
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class SettlementApplicationService(
    private val repository: SettlementRepository,
    private val worker: EconomyApplicationService,
    private val events: EventBus,
    private val policy: () -> EconomyPolicy,
    private val clock: Clock = Clock.systemUTC(),
) : SettlementAPI {
    private fun <T> publish(result: Settlement<T>): T {
        result.changes.forEach { events.publish(BalanceChanged(it.id, it.player, it.before.decimal(), it.after.decimal(), it.operation.name)) }
        return result.value
    }
    fun recover() = worker.submit { repository.recoverPending().forEach {
        events.publish(BalanceChanged(it.id, it.player, it.before.decimal(), it.after.decimal(), it.operation.name))
    } }
    override fun transfer(from: UUID, to: UUID, amount: BigDecimal) = worker.submit { publish(repository.transfer(from, to, Money.of(amount), policy())) }
    override fun teleportFee(player: UUID) = worker.balance(player).thenApply { policy().fee(Money.of(it)).decimal() }
    override fun reserveTeleport(ticket: UUID, player: UUID) = worker.submit { publish(repository.reserve(ticket, player, policy())) }
    override fun reserveTeleport(ticket: UUID, requester: UUID, payer: UUID) = worker.submit { publish(repository.reserve(ticket, requester, payer, policy())) }
    override fun settleTeleport(ticket: UUID, success: Boolean) = worker.submit { publish(repository.settle(ticket, success)) }
    override fun cancelTeleport(ticket: UUID) = worker.submit { publish(repository.cancel(ticket)) }.thenAccept { }
    override fun claimDaily(player: UUID) = worker.submit {
        val settings = policy()
        if (!settings.dailyEnabled) BigDecimal("0.00") else
            publish(repository.claimDaily(player, LocalDate.now(clock.withZone(settings.zone)), settings.dailyAmount)).decimal()
    }
    override fun reserveTax(ticket: UUID, player: UUID, assessment: TaxAssessment) = worker.submit {
        publish(repository.reserveTax(ticket, player, assessment))
    }
    override fun chargeStatus(ticket: UUID) = worker.submit { repository.chargeStatus(ticket) }
    override fun treasury() = worker.submit { repository.treasury().decimal() }
}
