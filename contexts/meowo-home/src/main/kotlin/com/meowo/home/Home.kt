package com.meowo.home

import java.util.UUID
import java.util.Base64
import java.util.Locale
import kotlin.math.*

/** Immutable data crosses the storage worker boundary; Bukkit locations never do. */
data class Home(val owner: UUID, val slot: Int, val world: UUID, val worldName: String,
    val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float,
    val name: String, val color: Int, val visible: Boolean = true, val style: String = "bowtie",
    val item: String = "WHITE_BED") {
    init {
        require(slot in 1..1000 && listOf(x,y,z).all { it.isFinite() } && yaw.isFinite() && pitch.isFinite())
        require(color in 0..0xFFFFFF && validName(name) && style in setOf("default", "bowtie"))
    }
    val markerId: UUID get() = UUID.nameUUIDFromBytes("meowo:home:$owner:$slot".toByteArray(Charsets.UTF_8))
    fun distance(px: Double, pz: Double): Double = hypot(x-px,z-pz)
    fun direction(px: Double, pz: Double, yaw: Float): String {
        val relative = ((Math.toDegrees(atan2(-(x-px),z-pz))-yaw+540) % 360)-180
        return listOf("↑","↗","→","↘","↓","↙","←","↖")[floor((relative+22.5+360)%360/45).toInt()%8]
    }
    fun encode(): String = listOf(owner,slot,world,b64(worldName),x,y,z,yaw,pitch,b64(name),color,visible,style,item).joinToString("|")
    companion object {
        fun validName(name: String): Boolean = name.isNotBlank() && name.codePointCount(0,name.length) <= 24 &&
            name.none { it.isISOControl() || it in "§&|" }
        private fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun unb64(value: String) = String(Base64.getUrlDecoder().decode(value),Charsets.UTF_8)
        fun decode(data: String): Home {
            val p=data.split('|'); require(p.size == 14)
            return Home(UUID.fromString(p[0]),p[1].toInt(),UUID.fromString(p[2]),unb64(p[3]),p[4].toDouble(),p[5].toDouble(),p[6].toDouble(),
                p[7].toFloat(),p[8].toFloat(),unb64(p[9]),p[10].toInt(),p[11].toBooleanStrict(),p[12],p[13])
        }
        val COLORS = linkedMapOf("white" to 0xE8E8E8,"orange" to 0xE6A45F,"magenta" to 0xC58BC2,"light_blue" to 0x8CC6DE,
            "yellow" to 0xE8D47E,"lime" to 0xB2CD86,"pink" to 0xE3A7BC,"gray" to 0x838C94,"light_gray" to 0xB8C1C6,
            "cyan" to 0x76B8B7,"purple" to 0xA393CB,"blue" to 0x7E9CCA,"brown" to 0xAC8B75,"green" to 0x8CAF8D,
            "red" to 0xD88787,"black" to 0x545D68)
        val ITEMS = listOf("WHITE_BED","RED_BED","BLUE_BED","OAK_DOOR","COMPASS","BELL","LANTERN","CAMPFIRE","DIAMOND",
            "EMERALD","CHEST","CRAFTING_TABLE","ENCHANTING_TABLE","BOOK","WHEAT","CHERRY_SAPLING")
        fun parseColor(text: String): Int? = COLORS[text.lowercase(Locale.ROOT)] ?: text.removePrefix("#").takeIf { Regex("[0-9a-fA-F]{6}").matches(it) }?.toInt(16)
    }
}
