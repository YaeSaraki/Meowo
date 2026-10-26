package com.meowo.chat.domain

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-player risk, filled in by an out-of-band 风纪 AI through `setRisk`.
 *
 * This is the reason the AI never sits in the chat path: the gate reads a number here, and the AI
 * may take ten seconds to decide it. A remote call in front of every message would stall the whole
 * server's chat for the slowest auditor.
 *
 * Scores decay on their own so a player who misbehaved once is not condemned forever.
 *
 * ## Why every read-modify-write is atomic
 *
 * The score is read by the gate on the chat path and written by the 风纪 side from another
 * thread — the AI's judgement arrives asynchronously, which is the whole design. The old entry held
 * a plain `var`, so two updates could interleave and one of them would be lost: a player who was
 * judged twice would be judged once.
 *
 * Each entry is now updated under its own lock, and the per-player updates go through
 * `ConcurrentHashMap.compute`, so "read the score, add to it, write it back" is one step.
 */
class RiskRegistry(
    private val ceiling: Int = 100,
    private val decayMillis: Long = 60_000L,
) {
    /**
     * One player's score.
     *
     * The lock is per entry, not global: two players' scores have nothing to do with each other,
     * and a global lock would make every chat line wait for every other player's judgement.
     */
    private class Entry(initial: Int, val lastTouched: AtomicLong, private val decayMillis: Long) {
        private var value: Int = initial

        /** The score, with the decay applied. */
        @Synchronized
        fun read(now: Long): Int {
            val idle = now - lastTouched.get()
            if (idle < decayMillis || value <= 0) return value
            // One point per decay window keeps the fall linear and obvious to reason about.
            val steps = (idle / decayMillis).toInt()
            val decayed = (value - steps).coerceAtLeast(0)
            value = decayed
            lastTouched.set(now)
            return decayed
        }

        /** Sets the score outright and restarts the decay clock. */
        @Synchronized
        fun write(newValue: Int, now: Long) {
            value = newValue
            lastTouched.set(now)
        }

        /** Adds to the score and returns the result. */
        @Synchronized
        fun add(delta: Int, now: Long): Int {
            value += delta
            lastTouched.set(now)
            return value
        }
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()

    fun risk(id: UUID): Int {
        val entry = entries[id] ?: return 0
        val decayed = entry.read(System.currentTimeMillis())
        if (decayed <= 0) entries.remove(id, entry)
        return decayed
    }

    fun set(id: UUID, value: Int) {
        if (value <= 0) { entries.remove(id); return }
        val now = System.currentTimeMillis()
        entries.compute(id) { _, old ->
            if (old == null) Entry(value.coerceAtMost(ceiling), AtomicLong(now), decayMillis)
            else { old.write(value.coerceAtMost(ceiling), now); old }
        }
    }

    /**
     * Adds to a player's score without losing an update.
     *
     * The read and the write are one atomic step, so two judgements arriving together both count.
     */
    fun bump(id: UUID, delta: Int) {
        val now = System.currentTimeMillis()
        entries.compute(id) { _, old ->
            val entry = old ?: Entry(0, AtomicLong(now), decayMillis)
            // A score that falls to zero is no score: the entry goes, exactly as a `set` of zero
            // would have removed it.
            if (entry.add(delta, now) <= 0) null else entry
        }
    }

    fun forget(id: UUID) { entries.remove(id) }

    fun clear() = entries.clear()
}
