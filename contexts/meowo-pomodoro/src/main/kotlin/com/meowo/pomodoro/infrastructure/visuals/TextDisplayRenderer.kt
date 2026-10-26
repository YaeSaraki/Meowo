package com.meowo.pomodoro.infrastructure.visuals

import com.github.retrooper.packetevents.PacketEvents
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * The floating countdown above the player's head — the readout **other players** see.
 *
 * ### What this shows and to whom
 *
 * The display is spawned in the world rather than sent as a per-player packet, so every player who
 * can see the owner sees the timer too: that is the whole point of it. The owner alone has it hidden
 * from their own client, because a `TextDisplay` sitting at head height would otherwise intercept the
 * clicks meant for the block the player is looking at. Everything else is untouched by
 * `hideEntity`, so bystanders read the countdown and the work status as intended.
 *
 * ### Two bugs this file is shaped around
 *
 * **The text was written with `Component.text`, which drops `§` codes.** The strings come from the
 * language bundle and are full of them, so the countdown rendered as a literal `§d◆ §fStandard`
 * instead of a coloured line. It has to go through [LegacyComponentSerializer] — the same trap
 * [PomodoroVisuals] documents for the BossBar and title.
 *
 * **The display used to be teleported every tick.** A teleport is an absolute jump, so a timer
 * re-anchored once a second visibly lagged a walking player by a full second and jittered on every
 * step. It now moves only when the player has actually changed block, so it tracks smoothly and
 * costs nothing while they stand still.
 *
 * ### Deliberate choices carried over
 *
 * **PacketEvents is a soft dependency.** [available] reports false when the plugin is absent, and
 * every method is then a no-op. BossBar and Title are unaffected, so a server without PacketEvents
 * loses one of three readouts rather than the whole feature — the posture
 * `meowo-home.PacketHomeNavigator` takes for its locator.
 *
 * **The entity is not persistent.** `TextDisplay` is a real world entity, and a real entity is
 * written into the chunk it stands in — so a crash, a `/kill`, or a shutdown that never got to run
 * cleanup would leave a frozen timer floating in the world forever, one per session, invisible to the
 * player who caused it. `isPersistent = false` keeps the entity in memory only: the worst a hard
 * crash costs is the readout, never a permanent litter of ghost timers.
 */
class TextDisplayRenderer(private val plugin: Plugin, private val offsetY: Double) : AutoCloseable {

    private val displays = mutableMapOf<UUID, TextDisplay>()

    /** The block the display was last anchored to, so an unmoving player costs no teleports. */
    private val anchors = mutableMapOf<UUID, Anchor>()

    private data class Anchor(val x: Int, val y: Int, val z: Int, val world: UUID)

    /**
     * Whether PacketEvents is loaded *and* initialised. Re-checked on every use rather than cached:
     * PacketEvents can be enabled after this context starts, and a cached `false` would silently
     * disable the readout for the rest of the server's life.
     */
    val available: Boolean
        get() = try {
            PacketEvents.getAPI().isInitialized
        } catch (_: Throwable) {
            // getAPI() throws when PacketEvents never registered itself, which is exactly the
            // "not installed" case; anything else here is equally a reason not to try.
            false
        }

    /** Creates the display for [player] if it does not exist yet. */
    fun ensure(player: Player) {
        if (!available || displays.containsKey(player.uniqueId)) return
        val anchor = anchor(player)
        val entity = player.world.spawn(anchor, TextDisplay::class.java) { display ->
            display.billboard = Display.Billboard.CENTER
            display.teleportDuration = 0
            display.isPersistent = false
            display.text(Component.empty())
        }
        // Hidden from its owner only, so it cannot intercept their block clicks. Other players keep
        // seeing it — that is the feature.
        player.hideEntity(plugin, entity)
        displays[player.uniqueId] = entity
        anchors[player.uniqueId] = anchor.blockKey()
    }

    /**
     * Moves the display to [player]'s head and writes [text].
     *
     * [text] carries `§` colour codes from the language bundle and must be deserialised, not wrapped
     * in a literal `Component.text` — see the class comment.
     */
    fun update(player: Player, text: String) {
        if (!available) return
        val entity = displays[player.uniqueId] ?: run { ensure(player); displays[player.uniqueId] } ?: return
        entity.text(LEGACY.deserialize(text))
        move(player)
    }

    /**
     * Re-anchors the display, but only when the player has moved to a different block.
     *
     * Comparing blocks rather than exact coordinates is deliberate: a player walking within their own
     * block, or turning on the spot, produces a stream of positions that would each cost a teleport
     * without moving the timer perceptibly. Crossing into a new block is the point at which a lag of
     * even one tick becomes visible.
     */
    private fun move(player: Player) {
        val entity = displays[player.uniqueId] ?: return
        val here = anchor(player)
        if (anchors[player.uniqueId] == here.blockKey()) return
        anchors[player.uniqueId] = here.blockKey()
        entity.teleport(here)
    }

    /** `offset-y` above the player's feet, per `pomodoro.text-display.offset-y`. */
    private fun anchor(player: Player) = player.location.clone().add(0.0, offsetY, 0.0)

    private fun Location.blockKey() = Anchor(blockX, blockY, blockZ, world?.uid ?: UNKNOWN_WORLD)

    /** Removes the display for [player]. Safe to call when none exists. */
    fun clear(player: Player) {
        anchors.remove(player.uniqueId)
        displays.remove(player.uniqueId)?.let { entity ->
            runCatching { entity.remove() }
        }
    }

    fun clearAll() {
        displays.values.forEach { entity -> runCatching { entity.remove() } }
        displays.clear()
        anchors.clear()
    }

    override fun close() = clearAll()

    private companion object {
        val LEGACY: LegacyComponentSerializer = LegacyComponentSerializer.legacySection()

        /**
         * A `Location` can in principle have no world (an uninitialised mock, a half-built object).
         * Using a fixed sentinel keeps [blockKey] total so the comparison never NPEs on it.
         */
        val UNKNOWN_WORLD: UUID = UUID(0L, 0L)
    }
}
