package com.meowo.platform

import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Where each player was, before whatever moved them.
 *
 * ## What it is for
 *
 * `/back` has to answer "where were you before that happened", and the honest answer differs by
 * cause: after a death it is where you fell, after a `/home` or `/tpa` it is where you stood
 * before you asked. EssentialsX keeps this in a `BackManager` in memory only, and that is the
 * right shape — a stale "return point" from last week is worse than none, because the player will
 * pay for it.
 *
 * ## Why it lives in `platform`
 *
 * The arrival is recorded by whichever context performed the travel — home, spawn, teleport — and
 * the `/back` command itself lives in the teleport context. A context importing another context is
 * a red line, so the shared journal belongs in the layer both already depend on.
 *
 * ## Recording
 *
 * [departed] is called **before** a move and [died] when a player dies; the first of them to fire
 * for a given cause wins, and both clear the other's record. `/back` records too, which is what
 * makes going back reversible — at the cost of one fee per hop, which is what bounds it.
 */
class TravelJournal(private val capacity: Int = 4096) {

    private val lastDeparture = ConcurrentHashMap<UUID, Location>()
    private val lastDeath = ConcurrentHashMap<UUID, Location>()

    /** Where the player was standing just before the move that is about to happen. */
    fun departed(player: UUID, from: Location) {
        remember(lastDeparture, player, from)
        // A fresh departure supersedes an older death: the player is alive and walking, and a
        // `/back` should now mean "before that", not "where you died an hour ago".
        lastDeath.remove(player)
    }

    /** Where the player fell. Recorded even mid-air or inside a vehicle, which is the point. */
    fun died(player: UUID, where: Location) {
        remember(lastDeath, player, where)
        lastDeparture.remove(player)
    }

    /**
     * The destination for `/back`, or null when there is nothing to go back to.
     *
     * Returns a **clone**: a `Location` handed out and then mutated by a caller would corrupt the
     * record for every later attempt.
     */
    fun destinationFor(player: UUID): Location? =
        (lastDeath[player] ?: lastDeparture[player])?.clone()

    /**
     * Whether there is anything to go back to at all.
     *
     * Both maps are consulted. The previous form — `lastDeath.containsKey(...) ?: lastDeparture
     * .containsKey(...)` — could only ever return its left operand, because `containsKey` yields a
     * non-null `Boolean` and an elvis never falls through a non-null value. A player whose only
     * record was a departure (the ordinary case: `/spawn`, `/home` and `/tpa` record departures,
     * not deaths) was therefore told there was nothing to go back to.
     */
    fun has(player: UUID): Boolean = lastDeath.containsKey(player) || lastDeparture.containsKey(player)

    fun forget(player: UUID) {
        lastDeath.remove(player)
        lastDeparture.remove(player)
    }

    /**
     * Forgets a departure record — but only the one that is still [from].
     *
     * ## Why the removal is conditional
     *
     * A context that starts a move records a departure, and a move that never happens must take the
     * record back: the player is standing exactly where they were, so `/back` would charge them a
     * fee to travel zero metres.
     *
     * But between "the flight was recorded" and "the flight was aborted" another context may have
     * recorded a departure of its own — a `/tpa` that actually went somewhere, say. Clearing the
     * record then would take away a `/back` that has nothing to do with the flight that failed. So
     * the record is only removed when it is still the one that was written, and the comparison is on
     * the place rather than on the exact moment: a player who turned around during a countdown
     * still failed to travel, and that must not keep the stale record alive.
     *
     * @return whether a record was removed.
     */
    fun forgetDeparture(player: UUID, from: Location): Boolean {
        val current = lastDeparture[player] ?: return false
        if (current.world != from.world) return false
        if (current.x != from.x || current.y != from.y || current.z != from.z) return false
        return lastDeparture.remove(player, current)
    }

    fun clear() {
        lastDeath.clear()
        lastDeparture.clear()
    }

    fun trackedPlayers(): Int = lastDeath.size + lastDeparture.size

    /**
     * Bounded so a long-running server cannot accumulate a `Location` per player forever; the
     * eldest is evicted exactly as `ConversationMemory` does for conversations.
     */
    private fun remember(map: ConcurrentHashMap<UUID, Location>, player: UUID, where: Location) {
        if (map.size >= capacity) map.keys.firstOrNull()?.let { map.remove(it) }
        map[player] = where.clone()
    }
}
