package com.meowo.economy.application

import com.meowo.api.*
import com.meowo.api.event.BalanceChanged
import com.meowo.economy.domain.model.*
import com.meowo.economy.domain.service.EconomyDomainService
import com.meowo.shared.event.EventBus
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import java.util.concurrent.*

/** Bounded single worker keeps database I/O off the tick thread and preserves commit/event ordering.
 * Events are published after commit; notification failure cannot roll back an already committed payment.
 */
class EconomyApplicationService(
    private val domain: EconomyDomainService,
    private val events: EventBus,
    private val onStopped: () -> Unit = {},
    private val drainTimeout: Duration = Duration.ofSeconds(30),
) : EconomyAPI, AutoCloseable {
    private val cleanupFailure = AtomicReference<Exception?>()
    private val worker = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1024),
        ThreadFactory { Thread(it, "Meowo-Economy").apply { isDaemon = true } }, AbortPolicy()) {
        override fun terminated() {
            try { onStopped() } catch (error: Exception) {
                cleanupFailure.set(error)
                System.getLogger(EconomyApplicationService::class.java.name).log(System.Logger.Level.ERROR, "Economy resource cleanup failed", error)
            } finally { super.terminated() }
        }
    }
    internal fun <T> submit(action: () -> T): CompletableFuture<T> = try {
        CompletableFuture.supplyAsync(action, worker)
    } catch (_: RejectedExecutionException) { CompletableFuture.failedFuture(EconomyException(EconomyError.UNAVAILABLE)) }
    override fun balance(player: UUID) = submit { domain.balance(player).decimal() }
    private fun change(player: UUID, amount: BigDecimal, operation: Operation) = submit {
        val tx = domain.change(player, operation, Money.of(amount))
        events.publish(BalanceChanged(tx.id, player, tx.before.decimal(), tx.after.decimal(), operation.name))
        tx.after.decimal()
    }
    override fun deposit(player: UUID, amount: BigDecimal) = change(player, amount, Operation.DEPOSIT)
    override fun withdraw(player: UUID, amount: BigDecimal) = change(player, amount, Operation.WITHDRAW)
    override fun setBalance(player: UUID, amount: BigDecimal) = change(player, amount, Operation.SET)
    /**
     * A balance change that has already been performed, on the calling thread.
     *
     * For callers whose own contract is synchronous — see [MeowoEconomyProvider], where Vault's
     * `withdrawPlayer` is a blocking `double`-in-`double`-out method a third-party plugin may call
     * from anywhere, including the main thread. Hopping to [worker] and waiting for it would turn
     * a shop purchase into a multi-second server stall, and a wallet change is a few milliseconds
     * of SQLite work: the repository is already `@Synchronized`, so running it inline is both safe
     * and faster than queueing behind whatever else the worker is doing.
     *
     * Events are published after the commit, exactly as on the async path.
     */
    fun changeNow(player: UUID, amount: BigDecimal, operation: Operation): BigDecimal {
        val tx = domain.change(player, operation, Money.of(amount))
        events.publish(BalanceChanged(tx.id, player, tx.before.decimal(), tx.after.decimal(), operation.name))
        return tx.after.decimal()
    }

    /** The current balance, read on the calling thread. Same reasoning as [changeNow]. */
    fun balanceNow(player: UUID): BigDecimal = domain.balance(player).decimal()

    override fun close() {
        worker.shutdown()
        try {
            check(worker.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                "Economy worker is still draining; resources will close when accepted work finishes"
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for economy shutdown", error)
        }
        cleanupFailure.get()?.let { throw IllegalStateException("Economy resource cleanup failed", it) }
    }
}
