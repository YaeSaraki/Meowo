package com.meowo.platform

import com.meowo.shared.event.EventBus
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletionException
import java.util.logging.Level
import com.meowo.api.EconomyException

/** The only scheduler boundary used by contexts. No Bukkit object is read by the database worker. */
class PlatformHost(val plugin: JavaPlugin, val events: EventBus, val commands: CommandRouter, val messages: Messages) {
    private val travellers = mutableMapOf<java.util.UUID, java.util.UUID>()
    /**
     * Where everybody was before whatever moved them, shared by every context.
     *
     * One instance for the whole server on purpose: a context may not import another, and `/back`
     * has to return a player to the spot they left whether they left by `/home`, `/spawn` or
     * `/tpa`. A journal per context would quietly record only one of the three.
     */
    val travelJournal = TravelJournal()

    fun claimTravel(player: java.util.UUID, ticket: java.util.UUID): Boolean {
        if (player in travellers) return false
        travellers[player] = ticket
        return true
    }
    fun releaseTravel(player: java.util.UUID, ticket: java.util.UUID) { if (travellers[player] == ticket) travellers.remove(player) }
    @Volatile var currency = CurrencyFormat()
    fun money(amount: java.math.BigDecimal) = currency.format(amount)
    fun mainThread(active: () -> Boolean = { true }, action: () -> Unit) {
        if (!plugin.isEnabled) return
        if (Bukkit.isPrimaryThread()) { if (active()) action(); return }
        try { Bukkit.getScheduler().runTask(plugin, Runnable { if (plugin.isEnabled && active()) action() }) }
        catch (e: org.bukkit.plugin.IllegalPluginAccessException) { if (plugin.isEnabled) throw e }
    }
    fun <T> reply(sender: CommandSender, future: CompletionStage<T>, active: () -> Boolean, success: (T) -> Unit) {
        future.whenComplete { value, failure ->
            if (failure != null) {
                val cause = if (failure is CompletionException) failure.cause ?: failure else failure
                if (cause !is EconomyException) plugin.logger.log(Level.SEVERE, "Economy operation failed", cause)
                mainThread(active) {
                    if (sender !is Player || sender.isOnline) messages.send(sender,
                        if (cause is EconomyException) "error-${cause.code.name.lowercase(java.util.Locale.ROOT)}" else "internal-error")
                }
            } else mainThread(active) { if (sender !is Player || sender.isOnline) success(value) }
        }
    }
}
