package com.meowo.home

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.world.waypoint.*
import com.github.retrooper.packetevents.protocol.color.Color
import com.github.retrooper.packetevents.util.Either
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint
import org.bukkit.entity.Player
import kotlin.math.floor

/** The owner receives exact-position waypoint packets; no world entity or public waypoint exists. */
class PacketHomeNavigator : HomeNavigator {
    companion object {
        fun waypoint(home: Home) = TrackedWaypoint(Either.createLeft(home.markerId),
            WaypointIcon(if(home.style=="bowtie") WaypointIcon.ICON_STYLE_BOWTIE else WaypointIcon.ICON_STYLE_DEFAULT,Color(home.color)),
            Vec3iWaypointInfo(Vector3i(floor(home.x).toInt(),floor(home.y).toInt(),floor(home.z).toInt())))
    }
    private fun supported(player: Player): Boolean {
        val user=PacketEvents.getAPI().playerManager.getUser(player) ?: return false
        return !user.clientVersion.isOlderThan(com.github.retrooper.packetevents.protocol.player.ClientVersion.V_1_21_6)
    }
    override fun track(player: Player,home: Home) {
        if(!supported(player)) return
        PacketEvents.getAPI().playerManager.sendPacket(player,WrapperPlayServerWaypoint(WrapperPlayServerWaypoint.Operation.TRACK,waypoint(home)))
    }
    override fun untrack(player: Player,home: Home) {
        if(!supported(player)) return
        PacketEvents.getAPI().playerManager.sendPacket(player,WrapperPlayServerWaypoint(WrapperPlayServerWaypoint.Operation.UNTRACK,waypoint(home)))
    }
}
