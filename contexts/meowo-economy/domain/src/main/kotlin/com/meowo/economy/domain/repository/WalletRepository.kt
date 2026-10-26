package com.meowo.economy.domain.repository

import com.meowo.economy.domain.model.*
import java.util.UUID

/** Implementations must atomically persist the wallet and ledger entry, rolling back on any error. */
interface WalletRepository : AutoCloseable {
    fun getOrCreate(player: UUID): Wallet
    fun change(player: UUID, operation: Operation, update: (Wallet) -> Wallet): Transaction
}
