package com.meowo.sit

import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Stairs
import org.bukkit.command.CommandSender
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `/sit` and `/ride`, following the mainstream server approach.
 *
 * **Sitting** spawns an invisible, gravity-free, invulnerable armour stand at the player's feet and
 * mounts the player on it. Removing that stand makes the client dismount, so there is no dangling
 * state: the seat either exists and is ours, or it is gone.
 *
 * **Riding** mounts only a nearby visible player. We never remove borrowed vehicles,
 * so we track only the seats we created.
 *
 * Sneak dismounts in both cases.
 *
 * Paper 26.2 note: `Entity#startRiding` / `stopRiding` **no longer exist** — the API is
 * `vehicle.addPassenger(rider)` / `rider.leaveVehicle()`. Every riding tutorial written for
 * 1.21 fails to compile here. See docs/minimax/12.
 */
class SitContext(
    private val host: PlatformHost,
    private val settings: SitSettings = SitSettings(),
) : Context, Listener {
    override val id = "sit"
    private val active = AtomicBoolean(false)
    /** Seat armour stands we own, so they can be removed on dismount, quit and close. */
    private val seats = mutableMapOf<UUID, ArmorStand>()
    /** Seat positions already claimed, so two players cannot end up on the same chair. */
    private val occupiedSeats = mutableSetOf<Location>()
    private val resources = mutableListOf<AutoCloseable>()
    private lateinit var seatKey: NamespacedKey

    // Legacy configurations may specify a larger reach; they cannot enlarge the 3x3 player area.
    private val rideHalfExtent = minOf(settings.targetRange, 3) / 2.0

    override fun start() {
        seatKey = NamespacedKey(host.plugin, "seat")
        active.set(true)
        // Stands survive a crash, so clear any left by a previous process before taking new riders.
        sweepStaleSeats()
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("sit", "sit", object : Route {
            override val permission = "meowo.sit"
            override fun execute(sender: CommandSender, args: List<String>) = toggleSit(sender)
            override fun complete(sender: CommandSender, args: List<String>): List<String> = emptyList()
        })
        resources += host.commands.register("ride", "sit", object : Route {
            override val permission = "meowo.ride"
            override fun execute(sender: CommandSender, args: List<String>) = ride(sender, args)
            override fun complete(sender: CommandSender, args: List<String>): List<String> =
                if (args.size == 1) targets(sender).filter { it.startsWith(args[0], true) } else emptyList()
        })
    }

    /**
     * Removes seats left behind by a previous process. CMI #6527 documents the failure this
     * prevents: after a crash the invisible stand outlives the plugin, the player stays mounted on
     * an unbreakable "ghost chair", and the only remedy is `/kill @e`.
     */
    private fun sweepStaleSeats() {
        if (!::seatKey.isInitialized) return
        for (world in Bukkit.getWorlds()) {
            for (stand in world.getEntitiesByClass(ArmorStand::class.java)) {
                if (stand.getPersistentDataContainer().has(seatKey, PersistentDataType.BYTE)) stand.remove()
            }
        }
    }

    // ── /sit ────────────────────────────────────────────────────────────────────────────────

    private fun toggleSit(sender: CommandSender) {
        if (sender !is Player) { host.messages.send(sender, "player-only"); return }
        if (!active.get()) return
        if (sender.gameMode == GameMode.SPECTATOR) { host.messages.send(sender, "seat-state"); return }
        // A seat we made is ours to remove; any other vehicle is left alone.
        seats.remove(sender.uniqueId)?.let { stand ->
            occupiedSeats.remove(stand.location)
            stand.remove()
            host.messages.send(sender, "seat-stand")
            return
        }
        if (sender.isInsideVehicle) { host.messages.send(sender, "seat-occupied"); return }
        if (!grounded(sender)) { host.messages.send(sender, "seat-airborne"); return }
        if (!sit(sender, sender.location)) { host.messages.send(sender, "seat-occupied"); return }
        host.messages.send(sender, "seat-sat")
    }

    /**
     * Mounts the player on a fresh seat at [at]. Returns false when the spot is already taken or
     * the mount is refused, so callers can report a specific reason instead of silently doing nothing.
     */
    private fun sit(player: Player, at: Location): Boolean {
        val anchor = at.clone()
        if (!occupiedSeats.add(anchor)) return false
        val seat = spawnSeat(anchor)
        if (!seat.addPassenger(player)) {
            seat.remove()
            occupiedSeats.remove(anchor)
            return false
        }
        seats[player.uniqueId] = seat
        return true
    }

    /**
     * Sits on a configured block when the player is looking at one, otherwise on the spot.
     * Without this guard a player could sit on an invisible stand in mid-air and hover.
     */
    private fun grounded(player: Player): Boolean {
        val below = player.location.block.getRelative(0, -1, 0)
        return below.type.isSolid || below.isLiquid
    }

    private fun spawnSeat(at: Location): ArmorStand =
        at.world.spawn(at, ArmorStand::class.java) { stand ->
            stand.isInvisible = true
            stand.isInvulnerable = true
            stand.isSilent = true
            stand.isSmall = true
            // A marker stand has no hitbox and no client presence beyond its rider.
            stand.isMarker = true
            stand.isPersistent = false
            // No isGravity() getter exists, so there is no Kotlin property for this one.
            stand.setGravity(false)
            // Tagged so a crash cannot leave an unbreakable seat behind; see sweepStaleSeats.
            stand.getPersistentDataContainer().set(seatKey, PersistentDataType.BYTE, 1)
        }

    // ── right-click to sit on a block ────────────────────────────────────────────────────────

    /**
     * Sits on a stairs, slab or carpet the player clicks with an empty hand.
     *
     * The empty-hand requirement is deliberate: holding an item means the player meant to use it,
     * and hijacking that click would break chest opening and block placement. The event is only
     * cancelled once we actually sit, so every other right-click behaves exactly as vanilla.
     */
    @EventHandler(ignoreCancelled = true)
    fun interact(event: PlayerInteractEvent) {
        if (!active.get() || !settings.rightClickBlocks) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        // Bukkit fires this event twice per click: once for the main hand and once for the off
        // hand. Without this guard every rejection — and every successful sit — is duplicated.
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!player.inventory.itemInMainHand.type.isAir) return
        if (player.isInsideVehicle) return
        val block = event.clickedBlock ?: return
        val spot = seatSpot(block) ?: return
        val at = seatAnchor(block.location, player.location.yaw, spot)
        seatRefusal(player, block, spot, at)?.let { host.messages.send(player, it); return }
        if (!sit(player, at)) { host.messages.send(player, "seat-taken"); return }
        event.isCancelled = true
        host.messages.send(player, "seat-sat")
    }

    /** Placement for a sittable block kind, or null when the block cannot be sat on. */
    internal fun seatSpot(block: Block): SeatSpot? {
        val type = block.type
        return when {
            // A stair's `facing` is the direction its FULL-block side looks at; the walkable step is
            // therefore on the opposite side, and that is where a chair belongs.
            Tag.STAIRS.isTagged(type) -> SeatSpot(
                settings.stairOffsetY, settings.stairForward, stepSideOf(block),
            )
            Tag.SLABS.isTagged(type) -> SeatSpot(settings.slabOffsetY, settings.slabForward)
            Tag.WOOL_CARPETS.isTagged(type) -> SeatSpot(settings.carpetOffsetY, settings.carpetForward)
            else -> null
        }
    }

    /** The side of a stair carrying the half-height step, or null when it is not readable. */
    private fun stepSideOf(block: Block): BlockFace? =
        (block.blockData as? Stairs)?.facing?.oppositeFace

    /**
     * Returns a language key when this seat must not be used, or null when it is fine.
     *
     * Five refusals, each with its own message so nothing fails silently:
     *  - **too far** — another plugin may extend interaction reach; a chair across the room is a trap.
     *  - **no headroom** — the space above the seat is solid, so the player would sit inside a block.
     *  - **upside-down stair** — the step is a ceiling shelf rather than a seat.
     *  - **wrong side of a stair** — the step is on the far side, so the chair would be buried in the
     *    tall half. Minecraft auto-orients stairs with the step towards the player, so this only
     *    happens when the stair was rotated after placement.
     *  - **already occupied** — another player, or their seat, already claims this space.
     */
    internal fun seatRefusal(player: Player, block: Block, spot: SeatSpot, at: Location): String? {
        if (player.location.distanceSquared(at) > settings.maxDistance * settings.maxDistance) return "seat-too-far"
        if (seatAt(player.uniqueId, at)) return "seat-taken"
        val above = at.clone().add(0.0, 1.0, 0.0)
        if (above.block.type.isSolid) return "seat-blocked"
        if (seatAt(player.uniqueId, above)) return "seat-taken"
        val stairs = block.blockData as? Stairs ?: return null
        if (stairs.half == Bisected.Half.TOP) return "seat-stairs-flipped"
        val side = spot.direction ?: return null
        val vector = side.direction
        val dx = player.location.x - (block.location.x + 0.5)
        val dz = player.location.z - (block.location.z + 0.5)
        // A small dead zone keeps a player standing on top of the block, or clicking from a
        // diagonal, from being told off over a near-zero dot product.
        if (dx * vector.x + dz * vector.z < -0.3) return "seat-stairs-backwards"
        return null
    }

    /** True when [id] already holds a seat of ours, or is standing in one. */
    private fun seatAt(id: UUID, at: Location): Boolean {
        occupiedSeats.forEach { if (it.world == at.world && it.distanceSquared(at) < EPSILON) return true }
        seats[id]?.let { return it.location.world == at.world && it.location.distanceSquared(at) < EPSILON }
        return false
    }

    // ── /ride ───────────────────────────────────────────────────────────────────────────────

    private fun ride(sender: CommandSender, args: List<String>) {
        if (sender !is Player) { host.messages.send(sender, "player-only"); return }
        if (!active.get()) return
        if (args.isEmpty()) { dismount(sender); return }
        if (args.size != 1) { host.messages.send(sender, "seat-ride-usage"); return }
        if (args[0].equals(sender.name, ignoreCase = true)) { host.messages.send(sender, "seat-self"); return }
        if (sender.isDead || sender.gameMode == GameMode.SPECTATOR) { host.messages.send(sender, "seat-ride-state"); return }
        val target = Bukkit.getPlayerExact(args[0])
        val result = resolveMount(sender, target)
        val vehicle = result.player
        if (vehicle == null) { host.messages.send(sender, result.error, args[0]); return }
        val seat = seats[sender.uniqueId]
        if (!vehicle.addPassenger(sender)) { host.messages.send(sender, "seat-failed"); return }
        // Keep an existing seat until the mount succeeds; never delete another player's vehicle.
        seats.remove(sender.uniqueId)
        seat?.remove()
        host.messages.send(sender, "seat-riding", vehicle.name)
    }

    private fun withinRideRange(player: Player, target: Player): Boolean {
        if (target == player || !target.isOnline || target.isDead || !player.canSee(target) || target.world != player.world) return false
        val from = player.location; val to = target.location
        return kotlin.math.abs(from.x - to.x) <= rideHalfExtent &&
            kotlin.math.abs(from.z - to.z) <= rideHalfExtent && kotlin.math.abs(from.y - to.y) <= rideHalfExtent
    }
    private data class MountResult(val player: Player? = null, val error: String = "seat-stack-invalid")

    /** Resolve without mutations, so suggestions and command execution use identical checks. */
    private fun resolveMount(rider: Player, target: Player?): MountResult {
        if (target == null || !withinRideRange(rider, target)) return MountResult(error = "seat-nearby-player")
        if (rider.isDead || rider.gameMode == GameMode.SPECTATOR) return MountResult(error = "seat-ride-state")
        var current: Player = target
        val visited = mutableSetOf<UUID>()
        while (true) {
            if (!visited.add(current.uniqueId)) return MountResult()
            if (current.gameMode == GameMode.SPECTATOR) return MountResult(error = "seat-ride-state")
            val passengers = current.passengers
            if (passengers.any { it == rider }) return MountResult(error = "seat-already")
            if (passengers.isEmpty()) break
            val next = passengers.singleOrNull() as? Player ?: return MountResult()
            if (!withinRideRange(rider, next)) return MountResult()
            current = next
        }
        // Riding any descendant would create a vehicle cycle, even when the selected player
        // is only part of a larger chain managed by another plugin.
        var ancestor: org.bukkit.entity.Entity? = current
        val ancestors = mutableSetOf<UUID>()
        while (ancestor != null) {
            if (ancestor == rider || !ancestors.add(ancestor.uniqueId)) return MountResult()
            ancestor = ancestor.vehicle
        }
        return MountResult(player = current)
    }

    private fun dismount(player: Player) {
        if (!player.isInsideVehicle) { host.messages.send(player, "seat-not-riding"); return }
        standUp(player)
        host.messages.send(player, "seat-dismounted")
    }

    /** Leaves the vehicle, then clears any seat we own so nothing is left behind. */
    private fun standUp(player: Player) {
        releaseSeat(player)
        player.leaveVehicle()
    }

    /** Removes the player's own seat, if any, keeping the occupied-position set in step. */
    private fun releaseSeat(player: Player) = releaseSeatById(player.uniqueId)

    private fun releaseSeatById(id: UUID) {
        seats.remove(id)?.let { stand ->
            occupiedSeats.remove(stand.location)
            stand.remove()
        }
    }

    private fun targets(sender: CommandSender): List<String> {
        val player = sender as? Player ?: return emptyList()
        return Bukkit.getOnlinePlayers().filter { resolveMount(player, it).player != null }.map { it.name }.sorted()
    }

    // ── events ──────────────────────────────────────────────────────────────────────────────

    /** Removing the stand makes the stand dismount, so this is the cleanup hook. */
    @EventHandler fun dismount(event: EntityDismountEvent) {
        val rider = event.entity as? Player ?: return
        releaseSeat(rider)
    }

    @EventHandler fun sneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking || !active.get()) return
        val player = event.player
        if (!player.isInsideVehicle) return
        standUp(player)
        host.messages.send(player, "seat-dismounted")
    }

    /** A chair must not outlive the block it belongs to, or the rider floats over a hole. */
    @EventHandler fun blockBreak(event: BlockBreakEvent) {
        if (!active.get()) return
        val at = event.block.location
        seats.entries.filter { (_, stand) ->
            kotlin.math.abs(stand.location.blockX - at.blockX) <= 1 &&
                kotlin.math.abs(stand.location.blockZ - at.blockZ) <= 1
        }.forEach { (rider, stand) ->
            Bukkit.getPlayer(rider)?.let { player ->
                player.leaveVehicle()
                if (player.isOnline) host.messages.send(player, "seat-gone")
            }
            occupiedSeats.remove(stand.location)
            seats.remove(rider)
            stand.remove()
        }
    }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        releaseSeat(event.player)
        if (event.player.isInsideVehicle) event.player.leaveVehicle()
        seats.remove(id)
    }

    /** Covers a player who reconnects still mounted on a seat that outlived the previous process. */
    @EventHandler fun join(event: PlayerJoinEvent) {
        if (active.get() && event.player.isInsideVehicle) {
            event.player.leaveVehicle()
            host.messages.send(event.player, "seat-dismounted")
        }
    }

    override fun close() {
        active.set(false)
        // Free every rider first: removing the stand is what makes the client dismount.
        seats.keys.toList().forEach { id ->
            Bukkit.getPlayer(id)?.leaveVehicle()
            releaseSeatById(id)
        }
        seats.values.forEach { it.remove() }
        seats.clear()
        occupiedSeats.clear()
        resources.asReversed().forEach { it.close() }; resources.clear()
    }

    private companion object {
        /** Two seats count as the same place when they are within this squared distance. */
        const val EPSILON = 1e-6
    }
}
