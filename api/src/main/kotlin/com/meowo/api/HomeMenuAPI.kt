package com.meowo.api

import java.util.UUID

/** Main-thread menu ports; implementations retain ownership of home operations. */
interface HomeMenuAPI {
    fun capacity(owner: UUID): Int
    fun entries(owner: UUID): List<HomeMenuEntry>
}
data class HomeMenuEntry(val slot: Int,val name: String,val color: Int,val item: String,
    val world: String,val x: Double,val y: Double,val z: Double,val visible: Boolean,val style: String)
interface MenuAPI { fun openHomes(owner: UUID,page: Int = 0) }
