package com.meowo.shadowbox

import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

/**
 * What the operator may change about the shadow box.
 *
 * [material] is the one setting that can be wrong in a way the plugin must survive: a mistyped or
 * future-incompatible name, or a perfectly valid block that simply has no container tile entity.
 * Neither is a reason to refuse `/meowo reload`, so a value that cannot hold an inventory falls
 * back to the default and is reported. Only a wrong *type* is a configuration error.
 */
data class ShadowBoxSettings(
    val material: Material = Material.SHULKER_BOX,
    /** What the operator actually wrote, kept only so the context can report a rejected value. */
    val requestedMaterial: String = material.name,
    /**
     * Whether `give` needs [ShadowBoxContext]&#39;s `meowo.shadowbox.give`.
     *
     * **True by default, and that is the safe way round.** Minting boxes is the one action here
     * that hands out items, so it is the one that must not be open to everybody; the two key
     * bindings stay open to everyone either way. Turning this *off* is what makes `/shadowbox
     * give` reachable without a permission, so the default is the gated side.
     */
    val givePermission: Boolean = true,
) {
    companion object {
        /**
         * Resolves a configured material name.
         *
         * Accepts anything that is a real item whose block state is a container with a usable
         * inventory. A real [ItemStack] is asked rather than a hardcoded list, so a material that
         * gains or loses its tile entity in a future version is judged by the API and not by a stale
         * table.
         *
         * [org.bukkit.inventory.meta.BlockStateMeta#hasBlockState] is deliberately **not** part of
         * the test: it only reports stored NBT, and a brand new item legitimately has none while its
         * block state is still a perfectly good empty container. MockBukkit answers false for every
         * fresh item, which would make the check reject materials that work in production.
         */
        fun materialOf(name: String, fallback: Material = Material.SHULKER_BOX): Material {
            val requested = Material.matchMaterial(name.trim().uppercase()) ?: return fallback
            if (!requested.isItem) return fallback
            val meta = ItemStack(requested).itemMeta as? BlockStateMeta ?: return fallback
            val container = meta.blockState as? Container ?: return fallback
            if (container.inventory.size <= 0) return fallback
            return requested
        }
    }
}
