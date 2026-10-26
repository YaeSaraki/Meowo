package com.meowo.render.infrastructure

import com.meowo.render.domain.*
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import org.bukkit.Bukkit
import java.util.Optional
import java.util.UUID

/** PacketEvents shields the context from obfuscated/version-specific NMS constructors. */
class PacketEntityRenderer : FakeEntityRenderer {
    override fun spawn(viewer: UUID, entity: FakeEntity) {
        val player = Bukkit.getPlayer(viewer) ?: return
        val packet = WrapperPlayServerSpawnEntity(entity.id, Optional.of(entity.uuid), EntityTypes.ARMOR_STAND,
            Vector3d(entity.x, entity.y, entity.z), 0f, 0f, 0f, 0, Optional.empty())
        PacketEvents.getAPI().playerManager.sendPacket(player, packet)
    }
    override fun clear(viewer: UUID, ids: List<Int>) {
        val player = Bukkit.getPlayer(viewer) ?: return
        if (ids.isNotEmpty()) PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerDestroyEntities(*ids.toIntArray()))
    }
}
