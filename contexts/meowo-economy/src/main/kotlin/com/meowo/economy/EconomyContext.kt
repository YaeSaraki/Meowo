package com.meowo.economy

import com.meowo.api.EconomyAPI
import com.meowo.api.SettlementAPI
import com.meowo.economy.domain.model.EconomyPolicy
import com.meowo.economy.application.SettlementApplicationService
import com.meowo.api.event.PlayerJoined
import com.meowo.economy.application.EconomyApplicationService
import com.meowo.economy.domain.service.EconomyDomainService
import com.meowo.economy.infrastructure.persistence.SqliteWalletRepository
import com.meowo.economy.interfaces.EconomyCommand
import com.meowo.platform.PlatformHost
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

class EconomyContext(private val host: PlatformHost, private val policy: () -> EconomyPolicy = { EconomyPolicy() }) : Context {
    override val id = "economy"
    private var repository: SqliteWalletRepository? = null
    private var application: EconomyApplicationService? = null
    private val resources = mutableListOf<AutoCloseable>()
    private val active = AtomicBoolean(false)
    override fun start() {
        val repo = SqliteWalletRepository(host.plugin.dataFolder.toPath().resolve("economy/wallets.db"))
        repository = repo
        val service = EconomyApplicationService(EconomyDomainService(repo), host.events, repo::close)
        application = service
        val settlements = SettlementApplicationService(repo, service, host.events, policy)
        // Recovery is queued first, before any wallet, command or login operation.
        settlements.recover().whenComplete { _, error -> if (error != null) host.plugin.logger.log(Level.SEVERE, "Teleport escrow recovery failed", error) }
        active.set(true)
        Bukkit.getServicesManager().register(SettlementAPI::class.java, settlements, host.plugin, ServicePriority.Normal)
        resources += AutoCloseable { Bukkit.getServicesManager().unregister(SettlementAPI::class.java, settlements) }
        Bukkit.getServicesManager().register(EconomyAPI::class.java, service, host.plugin, ServicePriority.Normal)
        resources += AutoCloseable { Bukkit.getServicesManager().unregister(EconomyAPI::class.java, service) }
        resources += host.commands.register("eco", "economy", EconomyCommand(host, service, settlements, active::get))
        registerWithVault(service)
        resources += host.events.subscribe(PlayerJoined::class.java) { event ->
            service.balance(event.player).thenCompose { settlements.claimDaily(event.player) }.whenComplete { paid, error ->
                if (error != null) host.plugin.logger.log(Level.SEVERE, "Daily subsidy failed for ${event.player}", error)
                else if (paid.signum() > 0) host.mainThread(active::get) {
                    Bukkit.getPlayer(event.player)?.let { host.messages.send(it, "daily-paid", host.money(paid)) }
                }
            }
        }
        Bukkit.getOnlinePlayers().forEach { host.events.publish(PlayerJoined(it.uniqueId)) }
    }
    /**
     * Hands the wallet to Vault, so shop plugins can charge players through it.
     *
     * **Everything here goes through reflection, on purpose.** Vault is a `compileOnly` dependency
     * and its classes are absent at runtime unless the operator installs it. Naming
     * `net.milkbowl.vault.economy.Economy` in a method body still links the class when that method
     * is verified, and the failure surfaces as a `NoClassDefFoundError` that takes the whole
     * economy context — and every test that touches it — down with it. That is not the "soft
     * degradation" the design asks for; it is a hard dependency wearing a soft dependency's name.
     *
     * So the class is loaded by name and only touched once it is known to be there.
     */
    private fun registerWithVault(service: EconomyApplicationService) {
        val economyClass = try {
            Class.forName("net.milkbowl.vault.economy.Economy")
        } catch (missing: ClassNotFoundException) {
            host.plugin.logger.warning(
                "Vault not installed: Meowo's wallet will not be reachable from shop plugins. " +
                    "Everything inside Meowo keeps working. Install Vault to let shops charge players.",
            )
            return
        } catch (missing: NoClassDefFoundError) {
            host.plugin.logger.warning("Vault is present but unusable (${missing.message}); skipping registration.")
            return
        }
        try {
            val provider = MeowoEconomyProvider(service, host.currency.name)
            // AbstractEconomy implements Economy, so the instance is assignable once loaded.
            check(economyClass.isInstance(provider)) { "MeowoEconomyProvider does not implement Vault's Economy" }
            // Erasing to the raw ServicesManager keeps the compiler from needing the class type it
            // is precisely trying not to link against.
            @Suppress("UNCHECKED_CAST")
            val services = Bukkit.getServicesManager() as org.bukkit.plugin.ServicesManager
            services.register(economyClass as Class<Any>, provider as Any, host.plugin, ServicePriority.Highest)
            resources += AutoCloseable { services.unregister(economyClass as Class<Any>, provider as Any) }
            host.plugin.logger.info("Meowo registered with Vault as an economy provider; shop plugins can charge players.")
        } catch (error: Throwable) {
            // A failure to offer the wallet to Vault must not stop Meowo's own economy working.
            host.plugin.logger.log(
                Level.WARNING,
                "Could not register with Vault; shops will not be able to charge Meowo's wallets. " +
                    "Meowo's own economy is unaffected.",
                error,
            )
        }
    }

    override fun close() {
        active.set(false)
        resources.asReversed().forEach { it.close() }; resources.clear()
        // Drain accepted work before closing JDBC; never close a connection under a running task.
        val service = application
        application = null
        val repo = repository
        repository = null
        if (service != null) service.close() else repo?.close()
    }
}
