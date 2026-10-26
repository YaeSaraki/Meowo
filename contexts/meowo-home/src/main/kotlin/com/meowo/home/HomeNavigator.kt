package com.meowo.home

import org.bukkit.entity.Player

/** Private waypoint transport, injectable so protocol and navigation rules are testable separately. */
interface HomeNavigator {
    fun track(player: Player, home: Home)
    fun untrack(player: Player, home: Home)
}
class NoHomeNavigator : HomeNavigator {
    override fun track(player: Player,home: Home) {}
    override fun untrack(player: Player,home: Home) {}
}
