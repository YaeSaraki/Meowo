package com.meowo.nametag.application

import org.bukkit.Location
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes an AFK player behave like nobody is standing there.
 *
 * ## Why protection and not a real "fake offline"
 *
 * The literal fake-offline approach — remove the player entity and inject an NPC in its place, the
 * way AfkDummy does — needs `PlayerList#placeNewPlayer` and friends, i.e. NMS. That is ruled out by
 * [red line 01 §8](../01-架构红线.md), and its own changelog shows the cost (NMS position resets,
 * rotation sync, Netty leak fixes, duplicate-entity detection).
 *
 * So this is the mainstream approach used by AFK Shield, AdvancedAFK, SmartAFK and friends:
 * immunity, a position lock, and clearing mob targets.
 *
 * ## Why the lock is an anchor and not `setCollidable(false)`
 *
 * That was the first attempt and it does not work. Paper's own developer, on PaperMC/Paper#11440:
 *
 * > "setCollidable and collidable exemptions are **purely server concepts**, the player doesn't
 * > know it's not supposed to collide with a certain entity. The only way I can see that this
 * > could be fixed would be for the server to send team packets with collision set to never."
 *
 * and on the same thread: "Player movement from collisions happens **on client side** and can't be
 * disabled." It has been inert for players since SPIGOT-6399 (1.19).
 *
 * The team escape hatch is not usable here either: `Team.Option.COLLISION_RULE` only governs
 * collisions **between members of the same team**, and a player may only be in one team — we
 * already use it for the away marker, and we cannot drag every mob and every player in as well.
 *
 * So the lock is an **anchor**: the away position is remembered, and drift is corrected on the
 * sweep. This is mechanism-agnostic — it catches a player push, a boat, a piston, water, a wind
 * charge, anything.
 *
 * ## Movement ends the away state by leaving, not by being snapped back
 *
 * Three versions of this, all recorded in docs/minimax/14:
 *
 * 1. Snap the player back on every step — walking could then never end the away state.
 * 2. Tell "a shove" from "a walk" by velocity — unreliable, walking carries speed of its own.
 * 3. Teleport back past a leash — worked, but moving the player is intrusive.
 *
 * What is left is the simplest option: **never move the player**. Walking is not an exit, but being
 * carried [LEASH_BLOCKS] away from where they went away clearly means something happened, so that
 * simply ends the away state. No teleport, no rubber band, and the player is always in control of
 * where they stand.
 */
class AfkGuard(
    /** How far to look when clearing mob targets, in blocks. */
    private val mobSweepRadius: Int = 8,
) {
    /** Where the player stood when they went away; drift is corrected back to here. */
    private val anchors = ConcurrentHashMap<UUID, Location>()

    /** Prior state, so leaving AFK restores exactly what the player had. */
    private val priorInvulnerable = ConcurrentHashMap<UUID, Boolean>()
    private val priorCollidable = ConcurrentHashMap<UUID, Boolean>()

    /** Puts the player behind the away shield and remembers what to put back. */
    fun engage(player: Player) {
        priorInvulnerable.putIfAbsent(player.uniqueId, player.isInvulnerable)
        priorCollidable.putIfAbsent(player.uniqueId, player.isCollidable)
        player.isInvulnerable = true
        // Kept alongside the anchor: harmless, and it does help against non-player entities.
        player.isCollidable = false
        anchors.putIfAbsent(player.uniqueId, player.location.clone())
    }

    /** Takes the shield off and restores the prior flags, including "was already invulnerable". */
    fun release(player: Player) {
        anchors.remove(player.uniqueId)
        priorInvulnerable.remove(player.uniqueId)?.let { player.isInvulnerable = it }
        priorCollidable.remove(player.uniqueId)?.let { player.isCollidable = it }
    }

    /**
     * True once the player has been carried further than [LEASH_BLOCKS] from where they went away.
     *
     * This is deliberately a question, not an action: the caller ends the away state. The plugin
     * never moves an away player, so this can only ever report, never relocate them.
     */
    fun hasDrifted(player: Player, leash: Double = LEASH_BLOCKS): Boolean {
        val anchor = anchors[player.uniqueId] ?: return false
        if (player.world != anchor.world) return false
        return player.location.distanceSquared(anchor) > leash * leash
    }

    fun releaseAll(players: Iterable<Player>) = players.forEach(::release)

    /**
     * Drops every mob that is currently hunting [player]. Mobs re-acquire targets on their own, so
     * this has to run on a tick rather than once when the player goes away.
     */
    fun clearMobTargets(player: Player) {
        val reach = mobSweepRadius.toDouble()
        player.world.getNearbyEntities(player.location, reach, reach, reach)
            .filterIsInstance<Mob>()
            .filter { it.target === player }
            .forEach { it.target = null }
    }

    /** True while the player is shielded, so event handlers can decide to null a knockback. */
    fun isGuarded(id: UUID): Boolean = priorInvulnerable.containsKey(id)

    private companion object {
        /** How far an away player may be carried before the away state simply ends, in blocks. */
        const val LEASH_BLOCKS = 5.0
    }
}
