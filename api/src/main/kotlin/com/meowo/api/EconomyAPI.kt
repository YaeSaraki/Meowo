package com.meowo.api

import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletionStage

/** All operations are asynchronous; callbacks may run on the economy worker, never assume Bukkit's main thread. */
interface EconomyAPI {
    fun balance(player: UUID): CompletionStage<BigDecimal>
    fun deposit(player: UUID, amount: BigDecimal): CompletionStage<BigDecimal>
    fun withdraw(player: UUID, amount: BigDecimal): CompletionStage<BigDecimal>
    fun setBalance(player: UUID, amount: BigDecimal): CompletionStage<BigDecimal>
}

enum class EconomyError { INVALID_AMOUNT, INSUFFICIENT_FUNDS, LIMIT_EXCEEDED, SELF_TRANSFER, TRANSFER_PENDING, UNAVAILABLE }
class EconomyException(val code: EconomyError) : IllegalArgumentException(code.name)
