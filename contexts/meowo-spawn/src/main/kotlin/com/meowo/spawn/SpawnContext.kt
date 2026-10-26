package com.meowo.spawn

import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * `/spawn` returns a player to the world spawn; `/setspawn` moves that point.
 *
 * Free by design — going home is not a taxed action. Abuse protection follows the mainstream set:
 * a warm-up the player must stand through, a cooldown, a bounded safe-destination scan, and short
 * post-teleport invulnerability. Every part is configurable and can be switched off.
 *
 * All state is main-thread confined and cleared on both `quit` and `close`, so a pending warm-up can
 * never fire against a disconnected player or a disabled context.
 */
class SpawnContext(
    private val host: PlatformHost,
    private val settings: SpawnSettings,
    /** Injected so tests can observe the teleport without a world. */
    private val move: (Player, Location) -> Boolean = { player, location ->
        player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
    },
    /**
     * How the real arrival is made. Injected so a test can drive the arrival synchronously; the
     * default is what [start] installs.
     */
    private var arriveWith: ((Player, Location, (Boolean) -> Unit) -> Unit)? = null,
    /**
     * How a missing chunk is obtained. Injected because a test server's `getChunkAtAsync` never
     * completes on its own — it is driven by the server's chunk system, which only exists in a
     * running server.
     */
    private val loadChunk: (org.bukkit.World, Int, Int) -> java.util.concurrent.CompletableFuture<Boolean> =
        { world, x, z ->
            world.getChunkAtAsync(x, z, true).thenApply { true }
        },
    /**
     * Injected so tests can watch the ordering without a world. The real one loads the chunk and
     * teleports in one step; see [go].
     */
    private var arriveAsync: (Player, Location, (Boolean) -> Unit) -> Unit = { _, _, _ -> }
) : Context, Listener {
    override val id = "spawn"
    private val active = AtomicBoolean(false)
    /** Warm-up timers by player. Presence means "this player is counting down". */
    private val pending = mutableMapOf<UUID, BukkitTask>()
    /** Epoch millis of the last accepted request, for the cooldown. */
    private val cooldowns = mutableMapOf<UUID, Long>()
    /** Players we made invulnerable, so we know whose prior state must be restored. */
    private val shielded = mutableSetOf<UUID>()
    private val resources = mutableListOf<AutoCloseable>()

    override fun start() {
        active.set(true)
        // `teleportAsync` loads the destination chunk off the tick before moving, which is the whole
        // reason a world spawn nobody has walked to can still be reached. Assigned here rather than
        // as a default argument so `active` is in scope.
        // A caller that supplied its own arrival keeps it: the injected `move` is how a test
        // observes the teleport, and replacing it here made every arrival depend on Paper's
        // chunk-loading future, which a test server never completes on its own.
        if (arriveWith != null) arriveAsync = { player, location, done -> arriveWith?.invoke(player, location, done) ?: done(false) }
        else arriveAsync = { player, location, done ->
            player.teleportAsync(location).whenComplete { moved, error ->
                if (error != null) host.plugin.logger.log(Level.WARNING, "spawn teleport failed", error)
                host.mainThread(active::get) { done(moved == true && error == null) }
            }
        }
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("spawn", "spawn", object : Route {
            override val permission = "meowo.spawn"
            override fun execute(sender: CommandSender, args: List<String>) = go(sender)
            override fun complete(sender: CommandSender, args: List<String>): List<String> = emptyList()
        })
        resources += host.commands.register("setspawn", "spawn", object : Route {
            override val permission = "meowo.spawn.set"
            override fun execute(sender: CommandSender, args: List<String>) = set(sender)
        })
    }

    private fun go(sender: CommandSender) {
        if (sender !is Player) { host.messages.send(sender, "player-only"); return }
        if (!stateAllows(sender)) return
        val id = sender.uniqueId
        if (id in pending) { host.messages.send(sender, "spawn-busy"); return }
        cooldownLeft(id)?.let { host.messages.send(sender, "spawn-cooldown", it); return }

        val world = if (settings.world.isEmpty()) sender.world else Bukkit.getWorld(settings.world)
        if (world == null) { host.messages.send(sender, "spawn-missing-world", settings.world); return }
        // Resolving the destination can now need a chunk load, which is asynchronous, so `go`
        // finishes on the continuation rather than inline. Everything after this point is the same
        // as it was; only the shape of the entry point changed.
        resolveDestination(sender, world) { destination ->
            if (destination == null) return@resolveDestination
            goOn(sender, destination)
        }
    }

    /** The part of [go] that used to run inline, once a destination is known. */
    private fun goOn(sender: Player, destination: Location) {
        val id = sender.uniqueId
        // `/back` should return a player to the field they ran `/meo spawn` from.
        host.travelJournal.departed(id, sender.location)
        // Charged at request time on purpose: cancelling and re-issuing must not bypass the cooldown.
        cooldowns[id] = System.currentTimeMillis()
        if (settings.warmupSeconds <= 0) { arrive(sender, destination); return }
        pending[id] = Bukkit.getScheduler().runTaskLater(
            host.plugin, Runnable { arrive(sender, destination) }, settings.warmupSeconds * 20L,
        )
        host.messages.send(sender, "spawn-warmup", settings.warmupSeconds)
    }

    /** Resolves the destination up front so an unusable spawn is reported before the player waits. */
    /**
     * Resolves the destination, loading the spawn chunk if nobody has been there.
     *
     * A world spawn is very often somewhere no player is standing — it was set months ago, or the
     * server has never generated that column. Refusing on the grounds that the chunk is unloaded
     * made `/meo spawn` fail in exactly the case it exists for, so the chunk is loaded
     * asynchronously first.
     */
    private fun resolveDestination(player: Player, world: org.bukkit.World, done: (Location?) -> Unit) {
        val spawn = world.spawnLocation
        if (!settings.safety) {
            if (world.isChunkLoaded(spawn.blockX shr 4, spawn.blockZ shr 4)) return done(spawn)
            // Safety off still means "do not scan for a floor", not "do not travel".
            (pendingChunkLoad ?: loadChunk)(world, spawn.blockX shr 4, spawn.blockZ shr 4).whenComplete { _, error ->
                if (error != null) {
                    host.plugin.logger.log(Level.WARNING, "Could not load the spawn chunk", error)
                    host.mainThread(active::get) { host.messages.send(player, "spawn-unloaded") }
                    host.mainThread(active::get) { done(null) }
                } else {
                    host.mainThread(active::get) { done(spawn) }
                }
            }
            return
        }
        SafeDestination.findAsync(
            spawn, settings.safeSearchRange, settings.centerAlign,
            load = { w, x, z -> (pendingChunkLoad ?: loadChunk)(w, x, z) },
        )
            .thenApply { outcome ->
                when (outcome) {
                    is SafeDestination.Outcome.Found -> outcome.location
                    is SafeDestination.Outcome.ChunkNotLoaded,
                    is SafeDestination.Outcome.Unsafe,
                    -> { host.messages.send(player, "spawn-unsafe"); null }
                }
            }
            .whenComplete { location, error ->
                if (error != null) host.plugin.logger.log(Level.WARNING, "spawn resolution failed", error)
                host.mainThread(active::get) { done(location) }
            }
    }

    private fun set(sender: CommandSender) {
        if (sender !is Player) { host.messages.send(sender, "player-only"); return }
        if (sender.isDead) { host.messages.send(sender, "spawn-state"); return }
        val world = sender.world
        if (!world.setSpawnLocation(sender.location.clone())) {
            host.messages.send(sender, "spawn-set-failed"); return
        }
        host.messages.send(sender, "spawn-set", world.name)
        if (settings.safety && SafeDestination.find(world.spawnLocation, settings.safeSearchRange, true) !is SafeDestination.Outcome.Found) {
            host.messages.send(sender, "spawn-set-unsafe")
        }
    }

    private fun arrive(player: Player, destination: Location) {
        val id = player.uniqueId
        // Drop the guard before moving: PlayerTeleportEvent extends PlayerMoveEvent, so our own
        // teleport would otherwise look like the player walking away.
        pending.remove(id)
        if (!active.get() || !player.isOnline) return
        if (!stateAllows(player)) return
        // Asynchronous, so an unloaded destination is loaded off the tick rather than refused.
        // Everything after the move happens in the callback, so the player's experience is
        // unchanged: the teleport simply may not have finished yet when the command returns.
        try {
            arriveAsync(player, destination) { moved -> onArrived(player, moved) }
        } catch (error: Exception) {
            host.plugin.logger.log(Level.SEVERE, "Spawn teleport failed", error)
            onArrived(player, false)
        }
    }

    /** Lets a test route the arrival through its own transport and observe it synchronously. */
    fun useMove(move: (Player, Location) -> Boolean) {
        arriveWith = { player, location, done -> done(move(player, location)) }
    }

    /** Synchronous chunk loading, for a test server that never completes the async form. */
    fun loadChunksSynchronously() {
        pendingChunkLoad = { world, x, z ->
            world.getChunkAt(x, z)
            java.util.concurrent.CompletableFuture.completedFuture(true)
        }
    }

    private var pendingChunkLoad: ((org.bukkit.World, Int, Int) -> java.util.concurrent.CompletableFuture<Boolean>)? = null

    private fun onArrived(player: Player, moved: Boolean) {
        if (!active.get() || !player.isOnline) return
        if (moved) {
            host.messages.send(player, "spawn-done")
            shield(player)
        } else {
            // The chunk would not load, or another plugin cancelled PlayerTeleportEvent; say so
            // instead of pretending it worked.
            host.messages.send(player, "spawn-blocked")
        }
    }

    private fun stateAllows(player: Player): Boolean {
        if (!active.get() || player.isDead || player.gameMode == GameMode.SPECTATOR) {
            host.messages.send(player, "spawn-state"); return false
        }
        return true
    }

    private fun cooldownLeft(id: UUID): Long? {
        val last = cooldowns[id] ?: return null
        val elapsed = System.currentTimeMillis() - last
        val wait = settings.cooldownSeconds * 1000L - elapsed
        return if (wait > 0) (wait + 999) / 1000 else null
    }

    private fun cancelWarmup(id: UUID, key: String) {
        val task = pending.remove(id) ?: return
        task.cancel()
        Bukkit.getPlayer(id)?.let { player -> if (active.get() && player.isOnline) host.messages.send(player, key) }
    }

    /** Restores the exact prior state: a player who was already invulnerable is left alone. */
    private fun shield(player: Player) {
        if (settings.invulnerabilitySeconds <= 0) return
        if (player.isInvulnerable) return
        if (!shielded.add(player.uniqueId)) return
        player.isInvulnerable = true
        host.plugin.server.scheduler.runTaskLater(
            host.plugin, Runnable { unshield(player.uniqueId) }, settings.invulnerabilitySeconds * 20L,
        )
    }

    private fun unshield(id: UUID) {
        if (!shielded.remove(id)) return
        Bukkit.getPlayer(id)?.isInvulnerable = false
    }

    /** Block-level comparison: turning the head or drifting inside a block must not cancel. */
    @EventHandler fun move(event: PlayerMoveEvent) {
        if (event.player.uniqueId !in pending) return
        if (event is PlayerTeleportEvent) return
        val to = event.to ?: return
        if (event.from.blockX != to.blockX || event.from.blockY != to.blockY || event.from.blockZ != to.blockZ)
            cancelWarmup(event.player.uniqueId, "spawn-moved")
    }

    /** There is no PlayerDamageEvent in the API: the generic entity event is the hook to use. */
    @EventHandler(ignoreCancelled = true) fun damage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        cancelWarmup(player.uniqueId, "spawn-damaged")
    }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        pending.remove(id)?.cancel()
        cooldowns.remove(id)
        // The entity is going away, so there is no state left to restore.
        shielded.remove(id)
    }

    override fun close() {
        active.set(false)
        pending.values.toList().forEach { it.cancel() }; pending.clear()
        cooldowns.clear()
        // Restore immediately: leaving players invulnerable across a context reload would be a real bug.
        shielded.toList().forEach { id -> Bukkit.getPlayer(id)?.isInvulnerable = false }
        shielded.clear()
        resources.asReversed().forEach { it.close() }; resources.clear()
    }
}
