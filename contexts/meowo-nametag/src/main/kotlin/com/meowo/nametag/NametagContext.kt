package com.meowo.nametag

import com.meowo.api.FocusAPI
import com.meowo.nametag.application.NametagService
import com.meowo.nametag.domain.NametagState
import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.entity.EntityDamageEvent
import io.papermc.paper.event.entity.EntityKnockbackEvent
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom nametags: a small dot after each player's name that shows whether they are away.
 *
 * Two inputs drive it — idle time and a manual `/afk` — and both land in the same
 * [NametagService], which owns the scoreboard team the marker is drawn with. The context only
 * handles Bukkit events, the sweep schedule and cleanup.
 *
 * The marker is a **team suffix**, not `Player#customName`: the API states that the custom-name
 * accessors "have no effect on players, they will always display their name", so that route
 * silently renders nothing. See docs/minimax/14.
 *
 * Vanilla has no "away" state, so nothing here changes gameplay: the dot is decoration, and it is
 * removed the moment the player leaves or the context closes.
 */
class NametagContext(
    private val host: PlatformHost,
    private val settings: NametagSettings = NametagSettings(),
    /** Injected so tests do not need a real scoreboard manager. */
    private val scoreboard: () -> org.bukkit.scoreboard.Scoreboard? = { Bukkit.getScoreboardManager()?.mainScoreboard },
    /**
     * The pomodoro context, resolved on every sweep rather than captured at construction.
     *
     * A constructor parameter would freeze whatever was registered at that instant, and
     * `config.yml` lets an operator enable the two contexts in any order — `MeowoRuntime` starts
     * nametag before pomodoro. Resolving lazily means a study mark appears on the first sweep after
     * pomodoro comes up, and disappears again when it goes away.
     */
    private val focus: () -> FocusAPI? = { Bukkit.getServicesManager().load(FocusAPI::class.java) },
) : Context, Listener {
    override val id = "nametag"
    private val active = AtomicBoolean(false)
    private lateinit var service: NametagService
    private var sweep: BukkitTask? = null
    private val resources = mutableListOf<AutoCloseable>()

    override fun start() {
        service = NametagService(settings) { id -> focus()?.isStudying(id) ?: false }
        active.set(true)
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("afk", "nametag", object : Route {
            override val permission = "meowo.nametag.afk"
            override fun execute(sender: CommandSender, args: List<String>) = toggleAfk(sender)
        })
        scoreboard()?.let { service.install(it) }
        if (settings.enabled) {
            val now = System.currentTimeMillis()
            Bukkit.getOnlinePlayers().forEach {
                service.track(it, now)
                // Resolved through the same path the sweep uses, so a player who is already mid-pomodoro
                // is not briefly painted green on join before the first sweep corrects it.
                val state = service.stateOf(it.uniqueId, now, focus()?.isStudying(it.uniqueId) ?: false)
                service.apply(it, state)
                // Record it, so the first sweep does not re-apply and spam a second team change.
                service.markApplied(it.uniqueId, state)
            }
            sweep = Bukkit.getScheduler().runTaskTimer(
                host.plugin, Runnable { if (active.get()) { service.sweep(System.currentTimeMillis()); refreshTitles() } },
                settings.checkIntervalSeconds * 20L, settings.checkIntervalSeconds * 20L,
            )
        }
    }

    private fun toggleAfk(sender: CommandSender) {
        if (sender !is Player) { host.messages.send(sender, "player-only"); return }
        if (!active.get()) return
        val now = System.currentTimeMillis()
        val away = service.toggleManual(sender, now)
        // Apply straight away: waiting up to a second for the sweep feels broken.
        service.apply(sender, away)
        if (away) showAwayTitle(sender) else host.messages.clearTitle(sender)
        host.messages.send(sender, if (away) "nametag-afk-on" else "nametag-afk-off")
    }

    /**
     * Movement means changing block, not turning on the spot: a player idling while looking around
     * must still count as idle, and this also filters out the constant stream of same-block moves.
     */
    @EventHandler fun move(event: PlayerMoveEvent) {
        if (!active.get()) return
        val to = event.to ?: return
        val from = event.from
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        val player = event.player
        if (!service.isGuarded(player.uniqueId)) { activity(player); return }
        // Walking is not an exit. But being carried a long way is proof that something happened,
        // and that ends the away state — the plugin never moves an away player.
        if (service.hasDrifted(player)) activity(player)
    }

    @EventHandler fun join(event: PlayerJoinEvent) {
        if (!active.get()) return
        val now = System.currentTimeMillis()
        service.track(event.player, now)
        val state = service.stateOf(event.player.uniqueId, now, focus()?.isStudying(event.player.uniqueId) ?: false)
        service.apply(event.player, state)
        // Record it, so the first sweep does not re-apply and spam a second team change.
        service.markApplied(event.player.uniqueId, state)
    }

    /**
     * Nothing may hurt an AFK player. `isInvulnerable` already covers the common causes; cancelling
     * as well keeps the promise when another plugin hands out damage directly.
     */
    @EventHandler(ignoreCancelled = true) fun damage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (active.get() && service.isGuarded(player.uniqueId)) {
            event.isCancelled = true
            player.noDamageTicks = 20
        }
    }

    /**
     * Knockback is applied separately from damage, so it needs its own hook — otherwise a hit that we
     * cancel the damage for still slides the player and ends the AFK.
     *
     * Paper's own `io.papermc.paper.event.entity.EntityKnockbackEvent`, **not** the Bukkit one: the
     * latter is deprecated for removal and the server logs a performance warning for every listener
     * registered against it. The replacement carries the same `Cause` enum and a mutable knockback
     * vector, so cancelling is unchanged.
     */
    @EventHandler(ignoreCancelled = true) fun knockback(event: EntityKnockbackEvent) {
        val player = event.entity as? Player ?: return
        if (active.get() && service.isGuarded(player.uniqueId)) event.isCancelled = true
    }

    /**
     * Re-sends the away title. A title fades out on its own, so it has to be refreshed while the
     * player stays away; otherwise it is gone after a second and the hint is lost.
     */
    private fun refreshTitles() {
        val now = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            if (service.isAfk(player.uniqueId, now)) showAwayTitle(player, fadeIn = 0)
        }
    }

    /** [fadeIn] is 0 on a refresh: re-sending with a fade-in would restart it every second and pulse. */
    private fun showAwayTitle(player: Player, fadeIn: Int = TITLE_FADE_IN) {
        host.messages.showTitle(
            player,
            host.messages.text(player, "nametag-away-title"),
            host.messages.text(player, "nametag-away-hint"),
            fadeIn, TITLE_STAY, TITLE_FADE_OUT,
        )
    }

    /**
     * Any deliberate action. The idle clock is reset **always**; only a player who was already away
     * additionally has their marker and title cleared.
     *
     * Returning early before the reset was a real bug: chatting while still inside the idle window
     * counted as nothing, so an actively talking player popped into the away state three minutes
     * later and the title appeared "for no reason".
     */
    private fun activity(player: Player) {
        if (!active.get()) return
        val wasAway = service.registerActivity(player, System.currentTimeMillis())
        if (!wasAway) return
        service.apply(player, false)
        host.messages.clearTitle(player)
    }

    /** Swinging an arm is a deliberate action, even without moving. */
    @EventHandler fun swing(event: PlayerAnimationEvent) = activity(event.player)

    /** Switching hotbar slot counts as being at the keyboard. */
    @EventHandler fun held(event: PlayerItemHeldEvent) = activity(event.player)

    /** Eating and drinking. */
    @EventHandler fun consume(event: PlayerItemConsumeEvent) = activity(event.player)

    /** Crouching is the deliberate "I am back" gesture. */
    @EventHandler fun sneak(event: PlayerToggleSneakEvent) {
        if (event.isSneaking) activity(event.player)
    }

    /** Saying anything counts. */
    @EventHandler fun chat(event: AsyncChatEvent) = activity(event.player)

    @EventHandler fun quit(event: PlayerQuitEvent) {
        // The player is going away, but the team entry is server-side state and must be removed so a
        // reconnect never inherits a stale marker.
        service.forget(event.player, reset = true)
    }

    private companion object {
        const val TITLE_FADE_IN = 10
        const val TITLE_STAY = 200
        const val TITLE_FADE_OUT = 20
    }

    override fun close() {
        active.set(false)
        sweep?.cancel(); sweep = null
        if (::service.isInitialized) {
            // Leaving players must not keep a marker that points at a disabled context.
            Bukkit.getOnlinePlayers().forEach { service.forget(it, reset = true) }
            service.forgetAll()
            service.uninstall()
        }
        resources.asReversed().forEach { it.close() }; resources.clear()
    }
}
