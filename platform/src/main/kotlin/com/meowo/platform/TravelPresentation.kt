package com.meowo.platform

import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/** Shared by paid TPA and home travel. All calls and animations stay on the tick thread. */
class TravelPresentation(private val host: PlatformHost, private val active: () -> Boolean) : AutoCloseable {
    private val animations = mutableMapOf<UUID, BukkitTask>()
    private val arrivalTasks = mutableMapOf<UUID, BukkitTask>()
    fun applyTravelEffects(player: Player, countdownTicks: Int) {
        val durations = listOf(
            PotionEffectType.NAUSEA to countdownTicks + 20,
            PotionEffectType.DARKNESS to countdownTicks + 20,
            PotionEffectType.SLOWNESS to countdownTicks.coerceAtLeast(1),
        )
        for ((type, duration) in durations) {
            val existing = player.getPotionEffect(type)
            if (existing != null && (existing.isInfinite || existing.duration >= duration)) continue
            // Extend short effects without weakening them or changing their display settings.
            player.addPotionEffect(PotionEffect(type, duration, existing?.amplifier ?: 0,
                existing?.isAmbient ?: true, existing?.hasParticles() ?: false, existing?.hasIcon() ?: false))
        }
    }

    fun animate(player: Player, durationTicks: Int) {
        animations.remove(player.uniqueId)?.cancel()
        arrivalTasks.remove(player.uniqueId)?.cancel()
        spawnRuneChain(player, 0)
        var tick = 2
        animations[player.uniqueId] = Bukkit.getScheduler().runTaskTimer(host.plugin, Runnable {
            if (!active() || !player.isOnline || player.isDead || tick >= durationTicks) {
                animations.remove(player.uniqueId)?.cancel()
                return@Runnable
            }
            spawnRuneChain(player, tick)
            tick += 2
        }, 2L, 2L)
    }
    private fun spawnRuneChain(player: Player, tick: Int) {
        val origin = player.location
        val glyph = Particle.DustOptions(Color.WHITE, 0.30f)
        val thread = Particle.DustOptions(Color.WHITE, 0.14f)
        TeleportGeometry.runeChain(tick).forEach { point ->
            origin.world.spawnParticle(Particle.DUST, origin.x + point.x, origin.y + point.y, origin.z + point.z,
                1, 0.0, 0.0, 0.0, 0.0, if (point.ink == RuneInk.INK) glyph else thread)
        }
    }
    fun arrive(player: Player) {
        arrivalTasks.remove(player.uniqueId)?.cancel()
        // Keep both geometry and palette fixed to the actual landing point.
        val origin = player.location.clone()
        val palette = BiomePalette.forBiome(origin.block.biome.key().asString())
        fun frame(tick: Int) {
            val fade = ((TeleportGeometry.ARRIVAL_TICKS - tick) / 16f).coerceIn(0.35f, 1f)
            val star = Particle.DustOptions(Color.fromRGB(palette.primary), 0.26f * fade)
            val ring = Particle.DustOptions(Color.fromRGB(palette.highlight), 0.17f * fade)
            TeleportGeometry.magicCircle(tick).forEach { point -> origin.world.spawnParticle(Particle.DUST,
                origin.x + point.x, origin.y + point.y, origin.z + point.z, 1, 0.0, 0.0, 0.0, 0.0,
                if (point.ink == RuneInk.INK) star else ring) }
        }
        frame(0)
        var tick = 3
        arrivalTasks[player.uniqueId] = Bukkit.getScheduler().runTaskTimer(host.plugin, Runnable {
            if (!active() || !player.isOnline || player.isDead || tick > TeleportGeometry.ARRIVAL_TICKS) {
                arrivalTasks.remove(player.uniqueId)?.cancel()
                return@Runnable
            }
            frame(tick)
            tick += 3
        }, 3L, 3L)
    }
    fun stopWaiting(player: UUID) { animations.remove(player)?.cancel() }
    fun stop(player: UUID) { stopWaiting(player); arrivalTasks.remove(player)?.cancel() }
    override fun close() {
        animations.values.forEach { it.cancel() }; animations.clear()
        arrivalTasks.values.forEach { it.cancel() }; arrivalTasks.clear()
    }
}
