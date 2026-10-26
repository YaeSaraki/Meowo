package com.meowo.render

import com.meowo.platform.*
import com.meowo.render.application.RenderService
import com.meowo.render.domain.RenderLimitException
import com.meowo.render.infrastructure.PacketEntityRenderer
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.*
import org.bukkit.event.server.PluginDisableEvent
import java.util.logging.Level

class RenderContext(private val host: PlatformHost, private val limit: Int) : Context, Listener {
    override val id = "render"
    private var service: RenderService? = null
    private val resources = mutableListOf<AutoCloseable>()
    override fun start() {
        check(Bukkit.getPluginManager().isPluginEnabled("packetevents")) { "Install PacketEvents 2.14.0 to enable render" }
        val renderer = RenderService(PacketEntityRenderer(), limit)
        service = renderer
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("render", "render", object : Route {
            override val permission = "meowo.render"
            override fun execute(sender: CommandSender, args: List<String>) {
                if (sender !is Player) { host.messages.send(sender, "player-only"); return }
                try {
                    when {
                        args == listOf("clear") -> { renderer.clear(sender.uniqueId); host.messages.send(sender, "render-cleared") }
                        args.size == 2 && args[0] == "spawn" -> {
                            val count = args[1].toIntOrNull() ?: 0
                            val loc = sender.location
                            renderer.spawn(sender.uniqueId, count, loc.x, loc.y, loc.z)
                            host.messages.send(sender, "render-spawned", count)
                        }
                        else -> host.messages.send(sender, "render-usage")
                    }
                } catch (_: RenderLimitException) { host.messages.send(sender, "render-limit", limit) }
                catch (e: Exception) { host.plugin.logger.log(Level.SEVERE, "Fake entity operation failed", e); host.messages.send(sender, "internal-error") }
            }
            override fun complete(sender: CommandSender, args: List<String>) = if (args.size == 1) listOf("spawn", "clear").filter { it.startsWith(args[0]) } else emptyList()
        })
    }
    @EventHandler fun quit(event: PlayerQuitEvent) { service?.forget(event.player.uniqueId) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun teleport(event: PlayerTeleportEvent) { service?.clear(event.player.uniqueId) }
    @EventHandler fun respawn(event: PlayerRespawnEvent) { service?.clear(event.player.uniqueId) }
    @EventHandler fun dependencyDisabled(event: PluginDisableEvent) { if (event.plugin.name.equals("packetevents", true)) close() }
    override fun close() {
        resources.asReversed().forEach { it.close() }; resources.clear()
        val previous = service; service = null; previous?.close()
    }
}
