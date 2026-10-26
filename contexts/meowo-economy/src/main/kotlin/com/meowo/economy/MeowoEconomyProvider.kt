package com.meowo.economy

import com.meowo.api.EconomyError
import com.meowo.api.EconomyException
import com.meowo.economy.application.EconomyApplicationService
import net.milkbowl.vault.economy.AbstractEconomy
import net.milkbowl.vault.economy.EconomyResponse
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * Exposes Meowo's wallets to every plugin that speaks Vault.
 *
 * ## Why this is a provider and not a consumer
 *
 * The goal is shop interoperability. Shop plugins do not know Meowo exists — QuickShop, ShopGUI+
 * and the rest all call Vault and nothing else. So for a shop to be able to charge a player, Meowo
 * has to **be** the economy Vault hands out. Consuming Vault instead would make Meowo spend some
 * other plugin's balance, which leaves the shops still unable to see Meowo's.
 *
 * No other economy plugin is required: this registers as the provider, so Vault has one to hand
 * out. Installing EssentialsX as well would create two providers and make which one a shop uses
 * depend on plugin load order, so the operator should run one or the other, not both.
 *
 * ## Vault is synchronous; Meowo is not
 *
 * Every Vault call is a blocking `double`-in-`double`-out method, while the wallet is a
 * transactional `CompletionStage`. The bridge is a bounded wait on the caller's thread. It is safe
 * here and only here: these callers are shop commands and console dispatchers, never the tick
 * thread. A hop onto the pool with a synchronous caller would deadlock it, which is why the wait
 * is bounded and the failure mode is a Vault error rather than a hang.
 *
 * ## Money never becomes a double
 *
 * Vault speaks `double`; the ledger speaks `BigDecimal`. Conversion happens at this boundary and
 * nowhere else, and the scale is [FRACTIONAL_DIGITS] so a value that survives the round trip is
 * exactly the value that was stored.
 */
class MeowoEconomyProvider(
    private val service: EconomyApplicationService,
    private val currencyName: String = "Meo",
) : AbstractEconomy() {

    private val log get() = java.util.logging.Logger.getLogger(MeowoEconomyProvider::class.java.name)
    private val warnedMainThread = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun isEnabled(): Boolean = true

    override fun getName(): String = "Meowo"

    /** Meowo's wallet is per-player; there are no shared accounts to be an owner or member of. */
    override fun hasBankSupport(): Boolean = false

    override fun fractionalDigits(): Int = FRACTIONAL_DIGITS

    override fun currencyNamePlural(): String = currencyName

    override fun currencyNameSingular(): String = currencyName

    override fun format(amount: Double): String = toDecimal(amount).toPlainString()

    override fun hasAccount(player: OfflinePlayerAlias): Boolean = true

    override fun hasAccount(player: OfflinePlayerAlias, world: String?): Boolean = hasAccount(player)

    override fun hasAccount(playerName: String): Boolean = true

    override fun hasAccount(playerName: String, world: String?): Boolean = true

    /**
     * Every Meowo account is created on first use, so an unknown name is a valid account with a
     * zero balance rather than a failure. A shop checking `hasAccount` before a purchase must not
     * be told "no" for a player who simply has not spent anything yet.
     */
    override fun getBalance(player: OfflinePlayerAlias): Double =
        toDouble(service.balanceNow(player.uniqueId))

    override fun getBalance(player: OfflinePlayerAlias, world: String?): Double = getBalance(player)

    override fun getBalance(playerName: String): Double =
        player(playerName)?.let { getBalance(it) } ?: 0.0

    override fun getBalance(playerName: String, world: String?): Double = getBalance(playerName)

    override fun has(player: OfflinePlayerAlias, amount: Double): Boolean = getBalance(player) >= amount

    override fun has(player: OfflinePlayerAlias, world: String?, amount: Double): Boolean = has(player, amount)

    override fun has(playerName: String, amount: Double): Boolean =
        player(playerName)?.let { has(it, amount) } ?: false

    override fun has(playerName: String, world: String?, amount: Double): Boolean = has(playerName, amount)

    override fun withdrawPlayer(player: OfflinePlayerAlias, amount: Double): EconomyResponse =
        change(player.uniqueId, amount, Flow.WITHDRAW)

    override fun withdrawPlayer(player: OfflinePlayerAlias, world: String?, amount: Double): EconomyResponse =
        change(player.uniqueId, amount, Flow.WITHDRAW)

    override fun withdrawPlayer(playerName: String, amount: Double): EconomyResponse =
        change(playerName, amount, Flow.WITHDRAW)

    override fun withdrawPlayer(playerName: String, world: String?, amount: Double): EconomyResponse =
        change(playerName, amount, Flow.WITHDRAW)

    override fun depositPlayer(player: OfflinePlayerAlias, amount: Double): EconomyResponse =
        change(player.uniqueId, amount, Flow.DEPOSIT)

    override fun depositPlayer(player: OfflinePlayerAlias, world: String?, amount: Double): EconomyResponse =
        change(player.uniqueId, amount, Flow.DEPOSIT)

    override fun depositPlayer(playerName: String, amount: Double): EconomyResponse =
        change(playerName, amount, Flow.DEPOSIT)

    override fun depositPlayer(playerName: String, world: String?, amount: Double): EconomyResponse =
        change(playerName, amount, Flow.DEPOSIT)

    /**
     * Accounts are made on first use, so there is nothing to create ahead of time.
     *
     * Answering `false` here would be a lie that breaks shops: most of them call
     * `createPlayerAccount` on first sight of a name and treat a `false` as a failure, when in
     * fact the account is already there waiting for a balance.
     */
    override fun createPlayerAccount(playerName: String): Boolean = true

    override fun createPlayerAccount(playerName: String, world: String?): Boolean = true

    // ── banks: not supported, and said so rather than pretended ──────────────────────────────

    /**
     * Every "not supported" answer here is a [EconomyResponse.ResponseType.NOT_IMPLEMENTED]
     * rather than a `FAILURE`. Vault distinguishes them on purpose, and a shop that sees
     * `FAILURE` may tell the player the bank name was taken.
     */
    override fun createBank(name: String, player: OfflinePlayerAlias): EconomyResponse = notABank(name)

    override fun createBank(name: String, playerName: String): EconomyResponse = notABank(name)

    override fun deleteBank(name: String): EconomyResponse = notABank(name)

    override fun isBankOwner(name: String, player: OfflinePlayerAlias): EconomyResponse = notABank(name)

    override fun isBankOwner(name: String, playerName: String): EconomyResponse = notABank(name)

    override fun isBankMember(name: String, player: OfflinePlayerAlias): EconomyResponse = notABank(name)

    override fun isBankMember(name: String, playerName: String): EconomyResponse = notABank(name)

    override fun bankBalance(name: String): EconomyResponse = notABank(name)

    override fun bankHas(name: String, amount: Double): EconomyResponse = notABank(name)

    override fun bankWithdraw(name: String, amount: Double): EconomyResponse = notABank(name)

    override fun bankDeposit(name: String, amount: Double): EconomyResponse = notABank(name)

    /** Empty rather than null, so a shop that lists banks shows nothing instead of erroring. */
    override fun getBanks(): MutableList<String> = mutableListOf()

    private fun notABank(name: String): EconomyResponse = EconomyResponse(
        0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Meowo 没有银行功能。",
    )

    // ── the bridge ───────────────────────────────────────────────────────────────────────────

    /** Which way the money moves, from the caller's point of view. */
    private enum class Flow { WITHDRAW, DEPOSIT }

    private fun change(player: UUID, amount: Double, flow: Flow): EconomyResponse = applyChange(player, amount, flow)

    /** An unresolvable name is refused rather than charged to a brand new empty account. */
    private fun change(playerName: String, amount: Double, flow: Flow): EconomyResponse {
        val player = player(playerName) ?: return failure(0.0, amount, "没有这个玩家。")
        return applyChange(player.uniqueId, amount, flow)
    }

    private fun applyChange(player: UUID, amount: Double, flow: Flow): EconomyResponse {
        val value = toDecimal(amount)
        if (value.signum() < 0) return failure(0.0, amount, "金额不能为负。")
        warnIfMainThread(flow)
        val before = try {
            toDouble(service.balanceNow(player))
        } catch (error: Exception) {
            return failure(0.0, amount, "读余额失败：${error.unwrap()?.message}")
        }
        if (flow == Flow.WITHDRAW && value > toDecimal(before)) {
            return EconomyResponse(amount, before, EconomyResponse.ResponseType.FAILURE, "余额不足。")
        }
        // The provider has its own private Operation; the domain one is a different type entirely.
        val domainOperation = if (flow == Flow.WITHDRAW)
            com.meowo.economy.domain.model.Operation.WITHDRAW else com.meowo.economy.domain.model.Operation.DEPOSIT
        val after = try {
            toDouble(service.changeNow(player, value, domainOperation))
        } catch (error: Exception) {
            val code = (error.unwrap() as? EconomyException)?.code
            return failure(before, amount, when (code) {
                EconomyError.INSUFFICIENT_FUNDS -> "余额不足。"
                EconomyError.INVALID_AMOUNT -> "金额不合法。"
                else -> "操作失败：${error.unwrap()?.message}"
            })
        }
        // Vault's constructor is (amount, balance, type, message) — confirmed from the bytecode,
        // not from the parameter names, which the published javadoc gets wrong in places. Getting
        // these the wrong way round makes a shop believe the player's balance is the price.
        return EconomyResponse(amount, after, EconomyResponse.ResponseType.SUCCESS, null)
    }

    /**
     * Vault gives a plugin no way to be told which thread it will be called on, and a shop that
     * calls from the main thread would otherwise be running a database write inside a tick. The
     * write itself is short, so it is done anyway — refusing would break the purchase — but it is
     * logged the first time so the operator can find the plugin doing it.
     */
    private fun warnIfMainThread(flow: Flow) {
        if (!org.bukkit.Bukkit.isPrimaryThread()) return
        if (warnedMainThread.compareAndSet(false, true)) {
            log.warning(
                "A Vault economy call arrived on the server's main thread. Meowo answers it " +
                    "inline because the alternative is a multi-second stall, but the calling plugin " +
                    "should use Vault's asynchronous methods or its own thread pool.",
            )
        }
    }

    private fun failure(balance: Double, amount: Double, message: String): EconomyResponse =
        EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, message)

    /**
     * A bounded wait, because Vault's caller is synchronous.
     *
     * Failing returns a Vault error; it never hangs the command that called us.
     */
    private fun Throwable.unwrap(): Throwable =
        (this as? CompletionException ?: this).let { (it as? ExecutionException)?.cause ?: it }

    /**
     * A name to a player, or nothing.
     *
     * `Bukkit.getOfflinePlayer(String)` **invents** an offline player for a name it has never
     * seen, so a shop with a typo in it would silently charge a fresh empty account and the buyer
     * would keep their money. `getOfflinePlayerIfCached` returns null instead, and the caller is
     * told no such player — which is the only honest answer when a name does not resolve.
     */
    private fun player(name: String): OfflinePlayerAlias? =
        org.bukkit.Bukkit.getOfflinePlayerIfCached(name)

    private fun toDecimal(amount: Double): BigDecimal =
        BigDecimal.valueOf(amount).setScale(FRACTIONAL_DIGITS, RoundingMode.HALF_UP)

    private fun toDouble(value: BigDecimal): Double = value.toDouble()

    companion object {
        /** Matches the scale the wallet stores, so a value survives the round trip exactly. */
        const val FRACTIONAL_DIGITS = 2
    }
}

/** Keeps the Vault signatures readable without importing Bukkit into every line. */
typealias OfflinePlayerAlias = org.bukkit.OfflinePlayer
