package com.meowo.economy.domain.model

import java.util.UUID

enum class Operation { DEPOSIT, WITHDRAW, SET, PAY_SENT, PAY_RECEIVED, TP_RESERVE, TP_REFUND, DAILY_SUBSIDY }
/** Wallet invariants are enforced by Money, including the upper bound and no overdraft. */
data class Wallet(val player: UUID, val balance: Money = Money.ZERO) {
    fun change(operation: Operation, amount: Money): Wallet = copy(balance = when (operation) {
        Operation.DEPOSIT -> balance + amount
        Operation.WITHDRAW -> balance - amount
        Operation.SET -> amount
        else -> throw IllegalArgumentException("Settlement operations require an atomic ledger transaction")
    })
}
data class Transaction(val id: UUID, val player: UUID, val before: Money, val after: Money, val operation: Operation)
