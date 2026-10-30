package com.meowo.core

import com.meowo.api.event.PlayerJoined
import com.meowo.core.config.ConfigCommand
import com.meowo.core.config.ConfigVersionMerger
import com.meowo.core.config.GlobalConfig
import com.meowo.api.UIAPI
import com.meowo.ui.UILoader
import com.meowo.ui.UIService
import org.bukkit.plugin.ServicePriority
import com.meowo.economy.EconomyContext
import com.meowo.economy.domain.model.EconomyPolicy
import com.meowo.teleport.TeleportContext
import com.meowo.home.HomeContext
import com.meowo.menu.MenuContext
import com.meowo.render.RenderContext
import com.meowo.spawn.SpawnContext
import com.meowo.sit.SitContext
import com.meowo.hat.HatContext
import com.meowo.chat.ChatContext
import com.meowo.agents.AgentsContext
import com.meowo.avatar.AvatarContext
import com.meowo.invsee.InvseeContext
import com.meowo.pomodoro.PomodoroContext
import com.meowo.mine.MineContext
import com.meowo.nametag.NametagContext
import com.meowo.shadowbox.ShadowBoxContext
import com.meowo.shop.ShopContext
import com.meowo.webapi.WebApiContext
import com.meowo.platform.*
import com.meowo.shared.event.EventBus
import com.meowo.shared.lifecycle.ContextManager
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.event.*
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStream
import java.util.logging.Level

/** Composition root. Only this module knows concrete contexts. */
/**
 * The composition root.
 *
 * [ui] is injectable so a test can stand in for the UI service: TabooLib cannot build a menu under
 * MockBukkit, so a test that wants to see what a screen was asked for needs a double here rather than a real
 * renderer.
 */
class MeowoRuntime(
    private val plugin: JavaPlugin,
    private val ui: () -> UIAPI? = { null },
) : AutoCloseable, Listener {


    private val events = EventBus { event, error -> plugin.logger.log(Level.SEVERE, "Subscriber failed: ${event.javaClass.simpleName}", error) }
    /**
     * The language state the runtime resolved.
     *
     * Exposed because a UI double in a test must resolve text through the *same* instance: a separate one
     * keeps whatever language it was created with, so a locale reload would not be visible to it.
     */
    val messages = Messages(plugin)
    private val commands = CommandRouter(messages)
    private val host = PlatformHost(plugin, events, commands, messages)
    private val contexts = ContextManager { id, error -> plugin.logger.log(Level.SEVERE, "Context $id failed", error) }
    private val routes = mutableListOf<AutoCloseable>()

    /**
     * The router and the live context list, read-only.
     *
     * Both exist for diagnostics, and for the one check that needs the two together: whether every
     * route's permission is actually declared in plugin.yml. A node that is used but undeclared
     * resolves against the default group, which is invisible from inside the product — every filter
     * reads the same wrong answer and agrees with every other one.
     */
    val router: CommandRouter get() = commands
    val activeContexts: Set<String> get() = contexts.ids()
    private var settings: GlobalConfig? = null
    @Volatile private var economyPolicy = EconomyPolicy()
    /**
     * Top-level command → argument prefix handed to the router. A subcommand mirrored at the root
     * keeps its own name as the prefix, so `/eco balance` and `/meowo eco balance` share one route.
     *
     * `/help` and `/reload` are deliberately absent: they would shadow the vanilla `/help` and the
     * `/reload` that server-management plugins normally own. They stay behind `/meowo` and `/meo`.
     *
     * **Every** other command declared in `plugin.yml` must appear here. A descriptor entry with no
     * matching alias is a command Bukkit resolves and then finds no executor for, so it answers
     * `/back` with the descriptor's `usage:` line and nothing else — the route exists, the command
     * is registered, and the two never meet. `back` is the command players reach for after dying,
     * which is the worst one to leave in that state.
     *
     * `internal` rather than `private` so the test can read the table instead of restating it: the
     * check that every declared command is reachable at the root is only worth anything if it uses
     * the same list the runtime does.
     */
    internal val aliases = mapOf("pay" to listOf("eco", "pay"), "tpa" to listOf("tpa"),
        "tpaccept" to listOf("tpa", "accept"), "tpdeny" to listOf("tpa", "deny"), "tpacancel" to listOf("tpa", "cancel"),
        "back" to listOf("back"),
        "meo" to emptyList(),
        "info" to listOf("info"), "eco" to listOf("eco"), "menu" to listOf("menu"),
        "render" to listOf("render"), "spawn" to listOf("spawn"), "setspawn" to listOf("setspawn"),
        "sit" to listOf("sit"), "ride" to listOf("ride"), "hat" to listOf("hat"),
        "mail" to listOf("mail"), "msg" to listOf("msg"),
        "shadowbox" to listOf("shadowbox"), "home" to listOf("home"), "invsee" to listOf("invsee"), "homes" to listOf("home", "gui"), "sethome" to listOf("home", "set"), "delhome" to listOf("home", "delete"), "afk" to listOf("afk"), "mine" to listOf("mine"), "pomodoro" to listOf("pomodoro"), "pomo" to listOf("pomodoro"), "sell" to listOf("sell"),
        // The avatar features: the menu's two cells run `/phantom` and `/size`, so both names have
        // to be root commands and not only routes under `/meowo`.
        "phantom" to listOf("phantom"), "size" to listOf("size"))
    fun start() {
        plugin.saveDefaultConfig()
        // After saveDefaultConfig, so the file exists on a fresh install before anything reads it, and
        // before GlobalConfig.read, so a key added by this release is already in the file the operator
        // will open. Only ever *adds* missing keys — see ConfigVersionMerger.
        syncConfiguration()
        val initial = GlobalConfig.read(plugin.dataFolder.resolve("config.yml"))
        reportConfigIssues(initial)
        messages.load(initial.locale, initial.languageMode)
        // The UI module: one menu code path for the whole plugin, laid out by `ui.yml`. The shipped file is
        // the fallback, the operator's copy overrides it per screen, so a new screen added here appears
        // without anyone regenerating a config file.
        val uiService = ui() ?: UIService(
            host,
            UILoader.load(
                requireNotNull(javaClass.getResourceAsStream("/ui.yml")) { "ui.yml is missing from the jar" },
                plugin.dataFolder.resolve("ui.yml"),
            ),
        )
        Bukkit.getServicesManager().register(UIAPI::class.java, uiService, plugin, ServicePriority.Normal)
        routes += AutoCloseable {
            Bukkit.getServicesManager().unregister(UIAPI::class.java, uiService)
            // The real service owns caches to drop; an injected double may not be closeable at all.
            (uiService as? AutoCloseable)?.close()
        }
        val help = HelpPage(messages, commands)
        routes += commands.register("help", "core", object : Route {
            override val permission = "meowo.use"
            override fun execute(sender: CommandSender, args: List<String>) {
                // A non-number is a typo, not page 1: silently showing page 1 would look like the
                // argument was understood.
                if (args.size > 1 || (args.size == 1 && args[0].toIntOrNull() == null)) {
                    messages.send(sender, "help-usage", help.pageCount(sender).coerceAtLeast(1)); return
                }
                help.send(sender, args.firstOrNull()?.toIntOrNull() ?: 1)
            }
            // Only page numbers this reader actually has: completing page 4 of 2 is worse than
            // completing nothing.
            override fun complete(sender: CommandSender, args: List<String>): List<String> =
                if (args.size == 1) help.pageNumbers(sender) else emptyList()
        })
        // The read-only configuration self-check. It exists because a config file has exactly
        // one reader, and everything that reader did not understand used to stay invisible.
        val configCommand = ConfigCommand(messages, plugin.dataFolder.resolve("config.yml")) { settings ?: initial }
        routes += commands.register("config", "core", object : Route {
            override val permission = "meowo.admin"
            override fun execute(sender: CommandSender, args: List<String>) = configCommand.run(sender, args)
            override fun complete(sender: CommandSender, args: List<String>) = configCommand.complete(sender, args)
        })
        routes += commands.register("info", "core", route("meowo.use") {
            messages.send(it, "info", plugin.description.version, contexts.ids().joinToString(", "), events.subscriberCount)
        })
        routes += commands.register("reload", "core", route("meowo.admin") { sender ->
            try {
                // The files are brought up to the shipped version before they are read, so a reload
                // after a jar update behaves like a restart: the operator's files gain the new keys
                // and the values the plugin reads come from the same place.
                // The layout is the exception: the menu that is on screen was built from the old
                // one, so a new `ui.yml` waits for a restart — hence the flag.
                syncConfiguration(layoutNeedsRestart = true)
                val next = GlobalConfig.read(plugin.dataFolder.resolve("config.yml"))
                reportConfigIssues(next)
                messages.load(next.locale, next.languageMode)
                applySettings(next)
                // `shop` joins the two that need a wallet, so turning economy off takes it down with them.
                messages.send(sender, if (contexts.ids() == (if ("economy" in next.contexts) next.contexts else next.contexts - setOf("teleport", "home", "shop"))) "reloaded" else "reload-partial")
            } catch (e: Exception) { plugin.logger.log(Level.WARNING, "Reload rejected", e); messages.send(sender, "reload-failed") }
        })
        val command = requireNotNull(plugin.getCommand("meowo")) { "Missing meowo command in plugin.yml" }
        command.setExecutor(commands); command.tabCompleter = commands
        aliases.forEach { (name, prefix) ->
            requireNotNull(plugin.getCommand(name)).let { alias ->
                alias.setExecutor { sender, _, label, args -> commands.onCommand(sender, alias, label, (prefix + args).toTypedArray()) }
                alias.tabCompleter = org.bukkit.command.TabCompleter { sender, _, label, args ->
                    commands.onTabComplete(sender, alias, label, (prefix + args).toTypedArray())
                }
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        applySettings(initial)
        plugin.logger.info("Meowo ready; contexts=${contexts.ids()}")
    }
    private fun route(permission: String, action: (CommandSender) -> Unit) = object : Route {
        override val permission = permission
        override fun execute(sender: CommandSender, args: List<String>) = action(sender)
    }

    /**
     * Brings every versioned configuration file up to the shipped version, and says so.
     *
     * ### Why this covers more than `config.yml`
     *
     * The plugin ships two files the operator can edit: `config.yml` (the options) and `ui.yml` (the menu
     * layouts). Both are configuration, so both are governed by the same version — the one the shipped
     * `config.yml` declares, read through [ConfigVersionMerger.globalVersion].
     *
     * Only `config.yml` used to be. A release that added a button to a screen changed the plugin's
     * configuration without changing the version that governs it, so the button never reached a server
     * with its own `ui.yml`: the loader lets the operator's screen win outright, so the new button was
     * simply not there. Nothing failed and nothing was logged. The version was one file's version
     * wearing a global name.
     *
     * The language bundles are **not** merged, on purpose — see [ConfigVersionMerger]. They hold wording
     * rather than settings, and the jar's copy already fills every key the operator's file lacks.
     *
     * Silent when nothing was written: a server that is already current should not see a line on every
     * start. When something *was* added it is named, because an option that appears in a file without
     * anybody being told is the same invisible-default problem the merge exists to solve.
     */
    private fun syncConfiguration(layoutNeedsRestart: Boolean = false) {
        val version = javaClass.getResourceAsStream("/config.yml")
            ?.use { ConfigVersionMerger.globalVersion(it) }
            ?: return
        // The options. The operator's file exists by now — `saveDefaultConfig` created it.
        javaClass.getResourceAsStream("/config.yml")?.use {
            mergeInto("config.yml", plugin.dataFolder.resolve("config.yml"), it, version)
        }
        // The layouts. Merged only into a file the operator actually has: the shipped copy stays the
        // fallback, and writing a copy of it would freeze every future layout change for that server.
        javaClass.getResourceAsStream("/ui.yml")?.use {
            if (mergeInto("ui.yml", plugin.dataFolder.resolve("ui.yml"), it, version) && layoutNeedsRestart) {
                // The file is up to date now, but the menu that is on screen was built from the old
                // one. Swapping the UI service would split the menus that are already open, so the
                // new layout waits for a restart — and the operator is told so, because a log that
                // says "upgraded" and a menu that did not change is how this looks like a bug.
                //
                // At start-up there is nothing to wait for: the layout is loaded right after this,
                // so the note would be wrong there.
                plugin.logger.info("ui.yml: the new layout takes effect after a restart of the server or the plugin.")
            }
        }
    }

    /**
     * Merges one shipped file into the operator's copy and reports what happened.
     *
     * A file that is already current, or that the operator never made, produces no output at all: the
     * first is the normal case and the second is not a change.
     *
     * Returns whether anything was written.
     */
    private fun mergeInto(label: String, file: File, shipped: InputStream, version: Int): Boolean {
        val result = ConfigVersionMerger.merge(file, shipped, version)
        if (!result.changed) return false
        if (result.added.isNotEmpty()) {
            plugin.logger.info(
                "$label upgraded ${result.fromVersion} → ${result.toVersion}; " +
                    "added ${result.added.size} new key(s), existing values untouched: " +
                    result.added.take(8).joinToString(", ") + (if (result.added.size > 8) " …" else ""),
            )
        } else {
            plugin.logger.info("$label upgraded ${result.fromVersion} → ${result.toVersion}; no new keys.")
        }
        // A shape mismatch is a warning, not an info: the operator has a scalar where the shipped file has a
        // block, so the new nested keys under it could not be added. Their value was preserved, which is the
        // right call, but somebody has to decide what that key is now.
        if (result.skipped.isNotEmpty()) {
            plugin.logger.warning(
                "$label: ${result.skipped.size} key(s) have a different shape than the shipped file and " +
                    "were left exactly as they are, so their new sub-keys were not added: " +
                    result.skipped.take(8).joinToString(", ") + (if (result.skipped.size > 8) " …" else "") +
                    ". Compare them with the shipped $label.",
            )
        }
        return true
    }
    /**
     * Says what the plugin did not understand about the operator's file.
     *
     * A wrong value is repaired rather than fatal, so the operator has to be **told** that it was
     * repaired — otherwise the repair is invisible and the setting they wrote is quietly not in
     * effect. The line is in the log at start-up and after every reload, and `/meowo config
     * warnings` lists the same things.
     */
    private fun reportConfigIssues(config: GlobalConfig) {
        if (config.issues.isEmpty()) {
            if (config.debug) plugin.logger.info("config.yml: no issues.")
            return
        }
        plugin.logger.warning("config.yml: ${config.issues.size} issue(s) found. The plugin started with the defaults for these.")
        config.issues.forEach { plugin.logger.warning("  - ${it.describe()}") }
        if (config.debug) {
            plugin.logger.info("config.yml: effective configuration follows")
            config.effective.forEach { (key, value) -> plugin.logger.info("  $key = $value") }
        }
    }

    private fun applySettings(next: GlobalConfig) {
        economyPolicy = next.economy
        host.currency = next.currency
        if (settings?.home != next.home || "economy" !in next.contexts || settings?.locale != next.locale || settings?.languageMode != next.languageMode || settings?.currency != next.currency) contexts.disable("home")
        // Teleport must stop before economy so every accepted reservation can be refunded.
        if (settings?.teleport != next.teleport || "economy" !in next.contexts) contexts.disable("teleport")
        // Close menu on display changes so stale titles and pending responses cannot survive reload.
        if (settings?.locale != next.locale || settings?.languageMode != next.languageMode || settings?.currency != next.currency ) contexts.disable("menu")
        if (settings?.renderLimit != next.renderLimit) contexts.disable("render")
        if (settings?.spawn != next.spawn) contexts.disable("spawn")
        if (settings?.nametag != next.nametag) contexts.disable("nametag")
        if (settings?.mine != next.mine) contexts.disable("mine")
        if (settings?.nametag != next.nametag) contexts.disable("nametag")
        if (settings?.hat != next.hat) contexts.disable("hat")
        if (settings?.chat != next.chat) contexts.disable("chat")
        if (settings?.shadowBox != next.shadowBox) contexts.disable("shadowbox")
        if (settings?.sit != next.sit) contexts.disable("sit")
        if (settings?.agents != next.agents) contexts.disable("agents")
        if (settings?.avatar != next.avatar) contexts.disable("avatar")
        if (settings?.invsee != next.invsee) contexts.disable("invsee")
        if (settings?.pomodoro != next.pomodoro) contexts.disable("pomodoro")
        if (settings?.shop != next.shop) contexts.disable("shop")
        if (settings?.webapi != next.webapi) contexts.disable("webapi")
        contexts.ids().filter { it !in next.contexts }.reversed().forEach(contexts::disable)
        if ("economy" in next.contexts && !contexts.isEnabled("economy")) contexts.enable(EconomyContext(host) { economyPolicy })
        if ("menu" in next.contexts && !contexts.isEnabled("menu")) contexts.enable(MenuContext(host))
        if ("render" in next.contexts && !contexts.isEnabled("render")) contexts.enable(RenderContext(host, next.renderLimit))
        if ("teleport" in next.contexts && contexts.isEnabled("economy") && !contexts.isEnabled("teleport")) contexts.enable(TeleportContext(host, next.teleport))
        if ("spawn" in next.contexts && !contexts.isEnabled("spawn")) contexts.enable(SpawnContext(host, next.spawn))
        if ("sit" in next.contexts && !contexts.isEnabled("sit")) contexts.enable(SitContext(host, next.sit))
        if ("hat" in next.contexts && !contexts.isEnabled("hat")) contexts.enable(HatContext(host, next.hat))
        // chat last of the simple ones: it needs the ServicesManager to be populated.
        if ("chat" in next.contexts && !contexts.isEnabled("chat")) contexts.enable(ChatContext(host, next.chat))
        if ("shadowbox" in next.contexts && !contexts.isEnabled("shadowbox")) contexts.enable(ShadowBoxContext(host, next.shadowBox))
        if ("nametag" in next.contexts && !contexts.isEnabled("nametag")) contexts.enable(NametagContext(host, next.nametag))
        if ("mine" in next.contexts && !contexts.isEnabled("mine")) contexts.enable(MineContext(host, next.mine))
        if ("home" in next.contexts && contexts.isEnabled("economy") && !contexts.isEnabled("home")) contexts.enable(HomeContext(host, next.home))
        // After chat: it claims speakers, and a speaker claim replaces whoever held it last.
        if ("agents" in next.contexts && !contexts.isEnabled("agents")) contexts.enable(AgentsContext(host, next.agents))
        if ("avatar" in next.contexts && !contexts.isEnabled("avatar")) contexts.enable(AvatarContext(host, next.avatar))
        if ("invsee" in next.contexts && !contexts.isEnabled("invsee")) contexts.enable(InvseeContext(host, next.invsee))
        if ("pomodoro" in next.contexts && !contexts.isEnabled("pomodoro")) contexts.enable(PomodoroContext(host, next.pomodoro))
        // After economy: the shop pays out of a wallet, and a shop with no wallet is a dead button.
        if ("shop" in next.contexts && contexts.isEnabled("economy") && !contexts.isEnabled("shop")) contexts.enable(ShopContext(host, next.shop))
        // Independent of everything else: it only reads the server and serves it over HTTP.
        if ("webapi" in next.contexts && !contexts.isEnabled("webapi")) contexts.enable(WebApiContext(host, next.webapi))
        settings = next
    }
    @EventHandler fun join(event: PlayerJoinEvent) { events.publish(PlayerJoined(event.player.uniqueId)) }
    override fun close() {
        HandlerList.unregisterAll(this)
        contexts.close()
        events.close()
        routes.asReversed().forEach { it.close() }; routes.clear()
        (listOf("meowo") + aliases.keys).forEach { name -> plugin.getCommand(name)?.let { it.setExecutor(null); it.tabCompleter = null } }
    }
}
