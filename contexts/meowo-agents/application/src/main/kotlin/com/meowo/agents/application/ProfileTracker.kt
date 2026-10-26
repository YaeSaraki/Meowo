package com.meowo.agents.application

import com.meowo.agents.domain.BehaviorCounters
import com.meowo.agents.domain.ChatShape
import com.meowo.agents.domain.TraitMath
import com.meowo.agents.infrastructure.PlayerProfileStore
import com.meowo.agents.infrastructure.StoredTraits
import com.meowo.api.event.ChatMessagePublished
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns what a player says into the one row every cat is allowed to read.
 *
 * ## Why this exists
 *
 * The plan's three layers need a writer, and there was none: the counters, [TraitMath] and
 * [PlayerProfileStore] all existed and nothing called them. A player could talk for an hour and no
 * cat would know anything about them — which is what was reported from a live server.
 *
 * ## Why an event and not a `ChatModerator`
 *
 * A `ChatModerator` is inspected for **every message** under a sub-millisecond budget that forbids
 * blocking IO. Registering one just to hear messages would put the agents context back on the hot
 * path the first release deliberately kept it off. [ChatMessagePublished] is a plain
 * `EventBus` subscription: the chat context publishes it once, and this listener's work is an O(1)
 * in-memory increment.
 *
 * ## Writing
 *
 * Nothing is written per message. The counters are flushed on a timer and on quit, because a
 * database write per line would be the very thing the hot path is not allowed to do, and because
 * the row is a *summary* — it does not change meaning between two flushes.
 *
 * ## Why the stored row is read on another thread
 *
 * The counters used to be **seeded** from the stored row the first time a player spoke, and that
 * read happened inside the message handler — on whichever thread the chat context publishes from.
 * That is a database round trip in the middle of the chat path, and it held up the publisher for
 * as long as SQLite took.
 *
 * The seeding is still needed — without it the first flush after a restart writes the new
 * session's counts over a lifetime row — but it no longer happens on the chat path. The row is
 * read on [loader], a thread of this tracker's own, and the result is cached as the **base** of the
 * session. Every later write merges the base with the session's counters, so the row stays the
 * lifetime total whether or not the player has said anything yet.
 */
class ProfileTracker(
    private val store: PlayerProfileStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
    private val flushEveryMs: Long = 600_000L,
    /**
     * How the stored row is read.
     *
     * Injected so a test can *observe* the rule this class exists to keep: the message path never
     * reads the database. A rule that cannot be seen is a rule that quietly stops holding, and
     * this one was broken by a seeding read in the middle of the chat path.
     */
    private val baseReader: (UUID) -> StoredTraits? = { store.traitsOf(it.toString()) },
) : AutoCloseable {

    private val counters = ConcurrentHashMap<UUID, BehaviorCounters>()
    private val firstSeen = ConcurrentHashMap<UUID, Long>()
    private val lastWritten = ConcurrentHashMap<UUID, Long>()
    private val lastFlushedAt = AtomicLong(now())

    /**
     * The stored row as it was **when this session started**.
     *
     * Null inside [Base] means "there was no row", which is not the same as "not read yet" — so
     * the row is wrapped rather than stored as a nullable value in a map that cannot hold one.
     */
    class Base(val traits: StoredTraits?)

    private val bases = ConcurrentHashMap<UUID, Base>()

    /** Players whose base read is already queued, so a burst of messages queues one read. */
    private val pendingLoads = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * Where the base read happens.
     *
     * Its own thread, and its own thread for a reason: the read is a database round trip, and the
     * only thread that must never pay for it is the one that publishes chat messages. A daemon
     * thread that exists only for this costs one parked thread per server.
     */
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "meowo-profile-loader").apply { isDaemon = true }
    }

    /** O(1). Called on the chat path, so it does nothing heavier than an integer increment. */
    fun onMessage(event: ChatMessagePublished) {
        val hour = java.time.Instant.ofEpochMilli(event.atEpochMillis)
            .atZone(zone).hour
        val counter = counters.computeIfAbsent(event.player) { BehaviorCounters() }
        firstSeen.putIfAbsent(event.player, event.atEpochMillis)
        counter.onMessage(
            text = event.text,
            isPublic = event.isPublic,
            hasEmoji = event.hasEmoji,
            hourOfDay = hour,
        )
        // The base read happens here, on the loader, and **not** in the handler: this is the chat
        // path, and a database round trip in it is exactly what it must not contain.
        if (!bases.containsKey(event.player) && pendingLoads.add(event.player)) {
            loader.execute {
                runCatching { baseOf(event.player) }
                pendingLoads.remove(event.player)
            }
        }
    }

    /**
     * The stored row this session builds on.
     *
     * Read at most once per player per session, and always **before** the session's first write —
     * which is what makes the merged row exact rather than doubled.
     */
    private fun baseOf(player: UUID): Base =
        bases.computeIfAbsent(player) { Base(runCatching { baseReader(player) }.getOrNull()) }

    /**
     * The lifetime view of a player: the stored base plus what they have said this session.
     *
     * The base carries only the summary it produced — the histogram behind it was never persisted
     * — so the peak hour falls back to the stored one rather than being recomputed from a
     * histogram nobody kept.
     */
    private fun mergedShape(player: UUID, counter: BehaviorCounters): ChatShape {
        val live = counter.snapshot()
        val base = bases[player]?.traits ?: return live
        val baseSamples = base.samples
        if (baseSamples <= 0) return live
        val total = baseSamples + live.samples
        if (total <= 0) return live
        val baseChars = base.avgLen * baseSamples
        val basePublic = (base.publicPct / 100.0 * baseSamples).toInt()
        val basePrivate = (baseSamples - basePublic).coerceAtLeast(0)
        val baseEmoji = (base.emojiPct / 100.0 * baseSamples).toInt()
        return ChatShape(
            samples = total,
            avgLen = (baseChars + counter.charTotal) / total,
            publicPct = (basePublic + counter.publicCount) * 100.0 / total,
            emojiPct = (baseEmoji + counter.emojiUses) * 100.0 / total,
            peakHour = live.peakHour ?: base.peakHour,
        )
    }

    /** Writes everything changed since the last flush. Returns how many players were written. */
    fun flush(): Int {
        lastFlushedAt.set(now())
        var written = 0
        for ((player, counter) in counters) {
            val at = now()
            if (at - (lastWritten[player] ?: 0L) < 30_000L) continue
            write(player, counter, at)
            written++
        }
        return written
    }

    /** The player's current shape, for the prompt. Null when they have not said enough. */
    fun shapeOf(player: UUID): ChatShape? = counters[player]?.let { mergedShape(player, it) }

    /** The prompt-safe description, or null below [TraitMath.MIN_SAMPLES]. */
    fun traitsOf(player: UUID): String? = counters[player]?.let { TraitMath.traitsOf(mergedShape(player, it), it) }

    fun trackedPlayers(): Int = counters.size

    /** The 24-hour histogram, for a co-presence score against another player. */
    fun hourly(player: UUID): IntArray? = counters[player]?.hourly()

    fun forget(player: UUID) {
        counters.remove(player)
        firstSeen.remove(player)
        lastWritten.remove(player)
        bases.remove(player)
        pendingLoads.remove(player)
    }

    /**
     * One player's counters, taken out of memory and waiting to be written.
     *
     * @param createdAt when the player was first seen, so the written row keeps its real age.
     * @param base the stored row this session builds on, or null when it has not been read yet —
     *   in which case the write reads it itself, on the thread that writes.
     */
    data class Pending(val counter: BehaviorCounters, val createdAt: Long, val base: Base?)

    /**
     * Takes one player's counters out of memory, ready to be written.
     *
     * ## Why the quit path needs two halves
     *
     * The write is a database round trip, and the quit path is the busiest the tick thread will be
     * all session — so the write has to happen on another thread. But a write that starts *after*
     * the counters have been dropped saves nothing and reports nothing, which is the worst kind of
     * silent loss: the plugin believes the player's last minutes were recorded.
     *
     * So the counters are detached here, on the caller's thread, and written later by
     * [writePending]. The two cannot race, because there is nothing left to race over.
     */
    fun detach(player: UUID): Pending? {
        val counter = counters.remove(player) ?: return null
        val created = firstSeen.remove(player) ?: now()
        lastWritten.remove(player)
        // The base is *taken*, not read: this runs on the quit path, and a read here would put a
        // database round trip back onto the thread the split above exists to keep clear.
        val base = bases.remove(player)
        pendingLoads.remove(player)
        return Pending(counter, created, base)
    }

    /**
     * Writes counters detached by [detach]. Safe to call from any thread.
     *
     * The row is written whether or not the periodic flush is due: this is the last chance, and a
     * player who said something in their last minute and then left should not lose it to a
     * thirty-second rate limit.
     */
    fun writePending(player: UUID, pending: Pending, at: Long = now()) {
        write(player, pending.counter, at, pending.base, remember = false)
    }

    fun clear() {
        counters.clear()
        firstSeen.clear()
        lastWritten.clear()
        bases.clear()
        pendingLoads.clear()
    }

    /** Releases the loader thread. The tracker is unusable afterwards. */
    override fun close() {
        clear()
        loader.shutdownNow()
    }

    /**
     * One upsert. Deliberately not a transaction spanning players: a flush that fails halfway has
     * simply saved some rows early, and the rest go on the next one.
     */
    private fun write(
        player: UUID,
        counter: BehaviorCounters,
        at: Long,
        base: Base? = null,
        remember: Boolean = true,
    ) {
        // The base is read here if it was not read before — on the thread that writes, which is
        // never the chat thread.
        val known = base ?: baseOf(player)
        val shape = mergedShape(player, counter)
        if (shape.samples <= 0) return
        val createdAt = known.traits?.createdAt ?: firstSeen[player] ?: at
        runCatching {
            store.upsertTraits(
                StoredTraits(
                    player = player.toString(),
                    samples = shape.samples,
                    avgLen = shape.avgLen,
                    publicPct = shape.publicPct,
                    emojiPct = shape.emojiPct,
                    peakHour = shape.peakHour,
                    // `soft_hits` is the discipline cat's column, and this is the **factual**
                    // writer. The store's upsert deliberately does not touch that column on an
                    // update, so the zero below only applies to a row that is being created — and
                    // a row that is being created cannot have accusations on it yet, because
                    // filing one creates the row itself. See `PlayerProfileStore.upsertTraits`.
                    softHits = 0,
                    traits = TraitMath.traitsOf(shape, counter),
                    updatedAt = at,
                    createdAt = createdAt,
                ),
            )
        }
        if (remember) lastWritten[player] = at
    }

    /** Whether a periodic flush is due. The caller owns the timer, on its own thread. */
    fun due(): Boolean = now() - lastFlushedAt.get() >= flushEveryMs
}
