package com.meowo.api

import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletionStage

/** Monetary settlement port; no Bukkit objects cross the database boundary. */
interface SettlementAPI {
    fun transfer(from: UUID, to: UUID, amount: BigDecimal): CompletionStage<TransferReceipt>
    fun teleportFee(player: UUID): CompletionStage<BigDecimal>
    fun reserveTeleport(ticket: UUID, player: UUID): CompletionStage<TeleportCharge>
    fun reserveTeleport(ticket: UUID, requester: UUID, payer: UUID): CompletionStage<TeleportCharge>
    fun settleTeleport(ticket: UUID, success: Boolean): CompletionStage<TeleportCharge>
    fun cancelTeleport(ticket: UUID): CompletionStage<Void>
    fun claimDaily(player: UUID): CompletionStage<BigDecimal>
    fun treasury(): CompletionStage<BigDecimal>
    fun reserveTax(ticket: UUID, player: UUID, assessment: TaxAssessment): CompletionStage<TeleportCharge>
    fun chargeStatus(ticket: UUID): CompletionStage<TeleportCharge?>
}
data class TransferReceipt(val amount: BigDecimal, val senderBalance: BigDecimal, val recipientBalance: BigDecimal,
    val tax: BigDecimal = BigDecimal("0.00")) {
    val total: BigDecimal get() = amount.add(tax)
}
enum class ChargeState { PENDING, COMMITTED, REFUNDED }
/** player is the payer; requester is the player being teleported. fee includes both taxes. */
data class TeleportCharge(val ticket: UUID, val player: UUID, val fee: BigDecimal, val state: ChargeState,
    val requester: UUID = player, val teleportTax: BigDecimal = fee, val paymentTax: BigDecimal = BigDecimal("0.00"))
