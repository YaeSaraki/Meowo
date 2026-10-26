package com.meowo.economy.interfaces

import com.meowo.api.*
import com.meowo.platform.*
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.math.BigDecimal
import java.util.UUID
import java.util.Locale

/** Parsing and permissions only; amounts and atomic transfers are validated by the economy. */
class EconomyCommand(private val host: PlatformHost, private val economy: EconomyAPI,
    private val settlements: SettlementAPI, private val active: () -> Boolean) : Route {
    override val permission = "meowo.economy.balance"
    private val actions = listOf("balance", "pay", "daily", "treasury", "give", "take", "set")
    override fun execute(sender: CommandSender, args: List<String>) {
        val action = args.firstOrNull()?.lowercase(Locale.ROOT) ?: "balance"
        if (action !in actions) { host.messages.send(sender, "eco-usage"); return }
        if (action != "balance" && !sender.hasPermission("meowo.economy.$action")) { host.messages.send(sender, "no-permission"); return }
        if (action == "daily" || action == "treasury") {
            if (args.size != 1) { host.messages.send(sender, "eco-usage"); return }
            if (action == "treasury") host.reply(sender, settlements.treasury(), active) { host.messages.send(sender, "treasury", host.money(it)) }
            else if (sender !is Player) host.messages.send(sender, "player-only")
            else host.reply(sender, settlements.claimDaily(sender.uniqueId), active) {
                if (it.signum() > 0) host.messages.send(sender, "daily-paid", host.money(it)) else host.messages.send(sender, "daily-empty")
            }
            return
        }
        if ((action == "balance" && args.size > 2) || (action != "balance" && args.size != 3)) { host.messages.send(sender, "eco-usage"); return }
        if (action == "pay" && sender !is Player) { host.messages.send(sender, "player-only"); return }
        val target = args.getOrNull(1)
        if (action == "balance" && target != null && !sender.hasPermission("meowo.economy.balance.others")) {
            if (sender !is Player || (target != sender.uniqueId.toString() && !target.equals(sender.name, true))) {
                host.messages.send(sender, "no-permission"); return
            }
        }
        val player = if (target == null) (sender as? Player)?.uniqueId else resolve(target)
        if (player == null) { host.messages.send(sender, "unknown-player"); return }
        if (action == "balance") {
            host.reply(sender, economy.balance(player), active) { host.messages.send(sender, "balance", target ?: sender.name, host.money(it)) }
            return
        }
        if (!Regex("[0-9]{1,12}([.][0-9]{1,2})?").matches(args[2])) { host.messages.send(sender, "error-invalid_amount"); return }
        val amount = BigDecimal(args[2])
        if (action == "pay") {
            val payer = sender as Player
            val payerName = payer.name
            val payment = settlements.transfer(payer.uniqueId, player, amount)
            host.reply(payer, payment, active) {
                host.messages.send(payer, "pay-sent", target!!, host.money(it.amount), host.money(it.senderBalance))
                host.messages.send(payer, "eco-pay-tax", host.money(it.tax), host.money(it.total))
            }
            payment.thenAccept { receipt -> host.mainThread(active) {
                Bukkit.getPlayer(player)?.let { host.messages.send(it, "pay-received", payerName, host.money(receipt.amount)) }
            } }
        } else {
            val future = when (action) { "give" -> economy.deposit(player, amount); "take" -> economy.withdraw(player, amount); else -> economy.setBalance(player, amount) }
            host.reply(sender, future, active) { host.messages.send(sender, "balance", target!!, host.money(it)) }
        }
    }
    private fun resolve(input: String): UUID? = runCatching { UUID.fromString(input) }.getOrNull()
        ?: Bukkit.getPlayerExact(input)?.uniqueId ?: Bukkit.getOfflinePlayerIfCached(input)?.uniqueId
    override fun complete(sender: CommandSender, args: List<String>): List<String> {
        val candidates = when (args.size) {
            1 -> actions.filter { it == "balance" || sender.hasPermission("meowo.economy.$it") }
            2 -> if (args[0] in listOf("daily", "treasury")) emptyList() else Bukkit.getOnlinePlayers().map { it.name }
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(args.lastOrNull().orEmpty(), true) }
    }
}
