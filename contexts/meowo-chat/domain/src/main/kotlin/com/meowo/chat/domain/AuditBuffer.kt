package com.meowo.chat.domain

import java.util.ArrayDeque
import java.util.UUID

/**
 * A bounded ring of recent messages for the 风纪 AI to review out of band.
 *
 * Bounded on purpose: an unbounded log of everything anybody said is a memory leak with extra
 * steps, and a fixed capacity also caps what an operator has to keep in mind when reading it.
 * When it wraps, the oldest entries are dropped silently — a moderation window that throws would
 * be worse than a short one.
 */
class AuditBuffer(private val capacity: Int = 512) {

    private val entries = ArrayDeque<ChatMessage>()

    init {
        require(capacity in 16..65_536) { "audit capacity must be 16..65536" }
    }

    @Synchronized
    fun record(message: ChatMessage) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(message)
    }

    /** Most recent first. A null player returns everybody's messages. */
    @Synchronized
    fun history(player: UUID?, limit: Int): List<ChatMessage> {
        val wanted = limit.coerceAtLeast(0)
        if (wanted == 0) return emptyList()
        val out = ArrayList<ChatMessage>(wanted)
        // ArrayDeque has no indexed access, so walk it backwards instead of copying it.
        val backwards = entries.descendingIterator()
        while (backwards.hasNext()) {
            val entry = backwards.next()
            if (player == null || entry.player == player) {
                out += entry
                if (out.size == wanted) break
            }
        }
        return out
    }

    @Synchronized fun size(): Int = entries.size

    @Synchronized fun clear() = entries.clear()
}
