package com.meowo.agents.domain

import java.util.UUID

/**
 * A session is keyed by **(player, cat)**, never by player alone.
 *
 * Without the second half, NestCat would know what the player asked EconCat and would start
 * mentioning it — which is both a privacy leak and unsettling to play against. Isolation is the
 * whole point of the key.
 */
data class ConversationKey(val player: UUID, val cat: String) {
}

/** Who produced a line inside one (player, cat) window. */
enum class Speaker {
    /** The player. */
    PLAYER,

    /** The cat named by the [ConversationKey]. */
    CAT,
}

/**
 * One line of a session.
 *
 * Only [who] and [text] are kept. The cat is not repeated here because the [ConversationKey] has
 * already fixed it — carrying it again is a second source of truth that could disagree with the
 * window a turn was filed under.
 */
data class Turn(val who: Speaker, val text: String)

/**
 * What a cat is allowed to remember, and how long.
 *
 * The in-memory window is the fast path; [SessionJournal] is the durable one. Keeping them behind
 * one interface is what lets the tests stay pure — a test passes [ConversationMemory] and needs no
 * database, and production swaps in the SQLite implementation without the runtime knowing.
 */
interface SessionJournal {
    fun append(key: ConversationKey, turn: Turn)
    fun recent(key: ConversationKey, limit: Int = 8): List<Turn>

    /** Clears the **cache** only. Durable rows age out on their own retention, not on a logout. */
    fun forgetPlayer(player: UUID)

    /** Drops everything, cache and durable rows both. Used when the server closes. */
    fun clear()
}

/**
 * A bounded, per-key window of recent turns.
 *
 * Bounded by **eviction**, not by a soft cap: a long-running server would otherwise accumulate a
 * window per (player, cat) pair forever. Pure, so the bound is provable without a server.
 *
 * ## Why the window was lengthened
 *
 * It used to hold six turns and the prompt was fed three of them — **one and a half exchanges**. A
 * player who talked to a cat for ten minutes and came back was greeted by a stranger, which reads as
 * the cat not caring rather than as it forgetting. Twenty turns is ten exchanges, which is roughly a
 * sitting; past that the prompt is carrying text nobody will read and the cost is not worth it.
 */
class ConversationMemory(
    private val maxKeys: Int = 2048,
    private val turnsPerKey: Int = 20,
) : SessionJournal {
    init {
        require(maxKeys > 0) { "maxKeys must be positive" }
        require(turnsPerKey > 0) { "turnsPerKey must be positive" }
    }

    private val windows = object : LinkedHashMap<ConversationKey, MutableList<Turn>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConversationKey, MutableList<Turn>>) = size > maxKeys
    }

    val size: Int get() = synchronized(windows) { windows.size }

    override fun append(key: ConversationKey, turn: Turn) = synchronized(windows) {
        val list = windows.getOrPut(key) { mutableListOf() }
        list += turn
        while (list.size > turnsPerKey) list.removeAt(0)
    }

    /** Newest last. Returns an empty list for an unknown key rather than null, so callers cannot NPE. */
    override fun recent(key: ConversationKey, limit: Int): List<Turn> = synchronized(windows) {
        windows[key].orEmpty().takeLast(limit)
    }

    override fun forgetPlayer(player: UUID) { synchronized(windows) {
        windows.keys.removeIf { it.player == player }
    } }

    override fun clear() = synchronized(windows) { windows.clear() }
}