package com.meowo.avatar

import com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent
import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent
import com.meowo.api.AvatarAPI
import com.meowo.api.event.AvatarChanged
import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.command.CommandSender
import org.bukkit.entity.Phantom
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.ServicePriority
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The player's own body: whether the night phantoms are looking for them, and how big they are.
 *
 * ## "No night phantoms" **cancels** the vanilla mechanic
 *
 * The vanilla insomnia mechanic picks a player who has not slept for long enough and sends a phantom
 * after them. This switch says "not you" — the mechanic stops choosing this player, and the world keeps
 * its rules for everybody else.
 *
 * It is a **cancel**, not a replacement. Earlier this context summoned a phantom of its own to follow the
 * player, which is a different thing altogether: it put a hostile mob into the world, it had to be
 * disarmed (no target, no AI, no burning, no singing) to keep it from attacking the person who asked for
 * it, and on a peaceful server it could not be made at all — the API refuses monsters there, so the
 * feature silently became a different animal. Cancelling needs none of that: no entity is created, so
 * there is nothing to defend against and nothing the server may refuse.
 *
 * ## How the cancel is done
 *
 * Two ways, and the first one is the one that normally fires:
 *
 * 1. **Paper's `PhantomPreSpawnEvent`** — fired *before* the phantom exists, and it carries the player
 *    the phantom is being spawned **for**. Cancelling it stops the spawn without creating anything, and
 *    `setShouldAbortSpawn(true)` also ends that player's retry loop instead of letting the server try
 *    again a few times.
 * 2. **`CreatureSpawnEvent`** — the plain Bukkit event, which fires for the same spawn but only once the
 *    entity exists. The phantom remembers who it was spawned for, so the owner is still known. This is
 *    the backup Paper's own documentation asks for, and it is what keeps the feature working on a server
 *    that does not fire the pre-spawn event.
 *
 * ## The size is the `scale` attribute, not a fake player
 *
 * `Attribute.SCALE` is the vanilla attribute behind a scaled entity. Setting it changes the real body:
 * the hitbox, the reach and the way other players see it, all at once. A fake player would have had none
 * of those, and would have been a second copy of somebody walking around the server.
 *
 * ## What lives for how long
 *
 * The two choices live as long as the server runs: a player who logs out and comes back gets their size
 * again. They do **not** survive a restart or a `/meowo reload` — the context is rebuilt from nothing,
 * and the size is put back to normal on the way out so nobody is left three times their own height.
 *
 * ## Threading
 *
 * Everything here is on the main thread: event handlers and command routes, both delivered on it.
 */
class AvatarContext(
    private val host: PlatformHost,
    private val settings: AvatarSettings = AvatarSettings(),
    /**
     * How the player is told what changed.
     *
     * A parameter for the same reason the other contexts have one: `Messages` needs the plugin's own
     * language resources inside a jar, and a test fixture is a bare class next to a descriptor file, so
     * with the real sender fixed in place this could not be tested at all. The default is exactly what
     * every other context does, and a test can pass a recorder instead.
     *
     * It is named `send` on purpose: the language-key test scans the sources for send-shaped calls, and
     * a seam called anything else would quietly take this context's keys out of its reach.
     */
    private val send: (Player, String, List<Any>) -> Unit = { player, key, args ->
        host.messages.send(player, key, *args.toTypedArray())
    },
    /**
     * The player's own `scale` attribute, or null when the server has none.
     *
     * A parameter for the same reason the others are: MockBukkit predates the `scale` attribute and
     * cannot install one, so a test that went through `getAttribute` would be testing the mock rather
     * than the feature. The default is exactly what a live server answers.
     */
    private val scaleAttribute: (Player) -> AttributeInstance? = { it.getAttribute(Attribute.SCALE) },
) : Context, Listener, AvatarAPI {

    override val id = "avatar"
    private val active = AtomicBoolean(false)
    private val resources = mutableListOf<AutoCloseable>()

    /** The size each player chose. Absent means "the default", not "no size". */
    private val scales = ConcurrentHashMap<UUID, Double>()

    /**
     * The players who asked the night phantoms to leave them alone.
     *
     * The **request** is what is remembered, not the absence of a phantom: the phantoms are the world's
     * business, and all this context does is say "not this one".
     */
    private val shielded = ConcurrentHashMap.newKeySet<UUID>()

    override fun start() {
        if (!active.compareAndSet(false, true)) return
        if (!settings.enabled) {
            // Still starts. A disabled feature that refuses to start is indistinguishable in the log
            // from a broken one, and `contexts.avatar` in the config is the real switch either way.
            host.plugin.logger.info("avatar: disabled in config; /phantom and /size were not registered")
            return
        }
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("phantom", id, object : Route {
            override val permission = "meowo.phantom"

            override fun execute(sender: CommandSender, args: List<String>) = toggle(sender, args.firstOrNull())
        })
        resources += host.commands.register("size", id, object : Route {
            override val permission = "meowo.size"

            override fun execute(sender: CommandSender, args: List<String>) = resize(sender, args)

            override fun complete(sender: CommandSender, args: List<String>): List<String> =
                if (args.size == 1) listOf("up", "down", "reset").filter { it.startsWith(args[0], true) } else emptyList()
        })
        Bukkit.getServicesManager().register(AvatarAPI::class.java, this, host.plugin, ServicePriority.Normal)
        resources += AutoCloseable { Bukkit.getServicesManager().unregister(AvatarAPI::class.java, this) }
    }

    // ── /phantom ─────────────────────────────────────────────────────────────────────────────

    /**
     * The night phantoms, on or off.
     *
     * `on` and `off` say which way it goes; with no word at all it **switches**, because that is what a
     * menu cell that says "switch it" has to do.
     */
    private fun toggle(sender: CommandSender, word: String? = null) {
        val player = sender as? Player ?: run {
            host.messages.send(sender, "player-only"); return
        }
        if (!active.get()) return
        val wanted = when (word?.lowercase()) {
            null -> !shielded.contains(player.uniqueId)
            "on" -> true
            "off" -> false
            else -> {
                send(player, "avatar-phantom-usage", emptyList()); return
            }
        }
        if (wanted) {
            shielded.add(player.uniqueId)
            send(player, "avatar-phantom-on", emptyList())
        } else {
            shielded.remove(player.uniqueId)
            send(player, "avatar-phantom-off", emptyList())
        }
        // Somebody may be looking at a menu cell that says which way the switch is; the change has to
        // reach that cell, and the cell is drawn by another context.
        host.events.publish(AvatarChanged(player.uniqueId))
    }

    // ── /size ────────────────────────────────────────────────────────────────────────────────

    /**
     * The player's size.
     *
     * `up` and `down` move one step; `reset` goes back to the default; a number sets an exact size and
     * is snapped to the grid, because a size the grid does not contain could not be reached again by
     * pressing the button once.
     */
    private fun resize(sender: CommandSender, args: List<String>) {
        val player = sender as? Player ?: run {
            host.messages.send(sender, "player-only"); return
        }
        if (!active.get()) return
        val current = scaleOf(player.uniqueId)
        val verb = args.firstOrNull()?.lowercase()
        val next = when (verb) {
            null -> {
                send(player, "avatar-size-current", listOf(settings.scale.format(current))); return
            }
            "up" -> settings.scale.step(current, 1)
            "down" -> settings.scale.step(current, -1)
            "reset" -> settings.scale.default
            else -> {
                val wanted = verb.toDoubleOrNull()
                if (wanted == null) {
                    send(player, "avatar-size-usage", emptyList()); return
                }
                settings.scale.snap(wanted)
            }
        }
        if (!applyScale(player, next)) {
            send(player, "avatar-size-unavailable", emptyList()); return
        }
        if (verb == "reset") {
            send(player, "avatar-size-reset", listOf(settings.scale.format(next)))
        } else {
            send(player, "avatar-size-changed", listOf(settings.scale.format(next)))
        }
        host.events.publish(AvatarChanged(player.uniqueId))
    }

    /**
     * Writes the size onto the player.
     *
     * The attribute is the whole truth: a size the server cannot store is a size the menu would still
     * claim, so the claim is only made once the attribute accepted it.
     */
    private fun applyScale(player: Player, value: Double): Boolean {
        val attribute = scaleAttribute(player) ?: return false
        attribute.baseValue = value
        scales[player.uniqueId] = value
        return true
    }

    // ── the night phantoms ───────────────────────────────────────────────────────────────────

    /**
     * The phantom that is being spawned, **before** it exists.
     *
     * The event carries the player the phantom is for, so there is no guessing: the one who asked for
     * quiet is named outright. Cancelling stops the spawn; `setShouldAbortSpawn(true)` additionally ends
     * the server's retry loop for that player, so a shielded player is not re-tested a few more times
     * inside the same tick.
     *
     * The parameter is the **parent** event on purpose. `PhantomPreSpawnEvent` does not declare its own
     * handler list — it shares the one its parent declares, together with the other pre-spawn events —
     * so a listener typed on the subclass would be handed the other events too and fail to cast. Typed
     * on the parent, the handler sees them all and simply ignores the ones that are not about a phantom.
     */
    @EventHandler
    fun onPreCreatureSpawn(event: PreCreatureSpawnEvent) {
        if (event !is PhantomPreSpawnEvent) return
        val target = event.spawningEntity as? Player ?: return
        if (!shielded.contains(target.uniqueId)) return
        event.isCancelled = true
        event.setShouldAbortSpawn(true)
    }

    /**
     * The backup path: the same spawn, seen once the phantom already exists.
     *
     * Paper says to listen for `CreatureSpawnEvent` as well, and the reason is that the pre-spawn event
     * does not fire for every case — a server that replaces entity registrations, for one. The phantom
     * remembers the player it was spawned for, so the owner is still known here.
     */
    @EventHandler(ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason != CreatureSpawnEvent.SpawnReason.NATURAL) return
        val phantom = event.entity as? Phantom ?: return
        val owner = phantom.spawningEntity ?: return
        if (!shielded.contains(owner)) return
        event.isCancelled = true
    }

    // ── events ───────────────────────────────────────────────────────────────────────────────

    /**
     * A player who comes back gets their size again.
     *
     * The size is an attribute on the player, and it does not survive the walk out of the door.
     * The phantom switch needs nothing: it is a rule this context keeps, not a mark on the player.
     */
    @EventHandler fun join(event: PlayerJoinEvent) {
        scales[event.player.uniqueId]?.let { applyScale(event.player, it) }
    }

    // ── the API ──────────────────────────────────────────────────────────────────────────────

    override fun phantomsBlocked(player: UUID): Boolean = shielded.contains(player)

    override fun scaleOf(player: UUID): Double = scales[player] ?: settings.scale.default

    override fun close() {
        active.set(false)
        // The size is a mark on the **player**, not on this context: leaving it on across a reload
        // would leave somebody three times their own height with nothing to change it back.
        for (id in scales.keys) {
            Bukkit.getPlayer(id)?.let { player ->
                scaleAttribute(player)?.baseValue = settings.scale.default
            }
        }
        scales.clear()
        shielded.clear()
        resources.asReversed().forEach { it.close() }; resources.clear()
    }
}
