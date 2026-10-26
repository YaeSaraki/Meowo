package com.meowo.economy.domain.service

import com.meowo.economy.domain.model.*
import com.meowo.economy.domain.repository.WalletRepository
import java.util.UUID

/** Coordinates atomic storage with the wallet's business rules. */
class EconomyDomainService(private val repository: WalletRepository) {
    fun balance(player: UUID): Money = repository.getOrCreate(player).balance
    fun change(player: UUID, operation: Operation, amount: Money): Transaction =
        repository.change(player, operation) { it.change(operation, amount) }
}
