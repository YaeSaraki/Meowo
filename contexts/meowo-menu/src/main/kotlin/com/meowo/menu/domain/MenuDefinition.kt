package com.meowo.menu.domain

/** Immutable menu layout, independent of Bukkit. */
data class MenuDefinition(val size: Int = 27, val balanceSlot: Int = 13) {
    init { require(size in 9..54 && size % 9 == 0); require(balanceSlot in 0 until size) }
}
