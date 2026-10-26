package com.meowo.economy.domain.repository

import com.meowo.api.*
import com.meowo.economy.domain.model.*
import java.time.LocalDate
import java.util.UUID

data class Settlement<T>(val value: T, val changes: List<Transaction> = emptyList())
interface SettlementRepository {
    fun transfer(from: UUID, to: UUID, amount: Money, policy: EconomyPolicy = EconomyPolicy()): Settlement<TransferReceipt>
    fun reserve(ticket: UUID, player: UUID, policy: EconomyPolicy): Settlement<TeleportCharge>
    fun reserve(ticket: UUID, requester: UUID, payer: UUID, policy: EconomyPolicy): Settlement<TeleportCharge>
    fun settle(ticket: UUID, success: Boolean): Settlement<TeleportCharge>
    fun cancel(ticket: UUID): Settlement<Unit>
    fun recoverPending(): List<Transaction>
    fun claimDaily(player: UUID, date: LocalDate, amount: Money): Settlement<Money>
    fun treasury(): Money
    fun reserveTax(ticket: UUID, player: UUID, assessment: TaxAssessment): Settlement<TeleportCharge>
    fun chargeStatus(ticket: UUID): TeleportCharge?
}
