package com.meowo.agents.domain

import java.util.ArrayDeque

/**
 * A bounded board of **what the public channel is talking about**, shared by every cat.
 *
 * ## Why this exists, and what it is not
 *
 * A cat that has only ever heard one player asking it things has no sense of a server. The cheapest
 * thing that fixes that is knowing that *something is being discussed right now* — and it has to be
 * the cheapest, because awareness that costs a query is awareness that never gets used.
 *
 * So the board holds **events, not words**: who was in public chat, and how many messages they have
 * sent recently. A cat can say "你们刚才也在聊传送吧" if the *topic* layer above it says so; what it
 * can never do is read what anyone actually wrote to anyone else.
 *
 * ## Private messages are not here, and cannot get here
 *
 * There is no code path that puts a `dm:` message on this board, and the writer takes only public
 * traffic by construction ([note]). That is the whole point: sharing *that something happened* is
 * presence, sharing *what was said* is surveillance, and only the first one is worth the tokens.
 *
 * ## Bounded
 *
 * A ring, like everything else here. When it wraps, the board forgets — which is also what makes it
 * safe to render into a prompt without a second thought about volume.
 */
class PresenceBoard(
    private val capacity: Int = 64,
    private val windowMillis: Long = 15 * 60_000,
) {
    init {
        require(capacity in 8..1024) { "presence capacity must be 8..1024" }
        require(windowMillis in 60_000..86_400_000) { "presence window must be 1 minute .. 1 day" }
    }

    /** One player seen on the public channel, with a summary of how much they have been saying. */
    data class Presence(val player: String, val mentions: Int, val lastAt: Long)

    private val entries = ArrayDeque<Presence>(capacity)

    /**
     * Records a public message. Anything else is ignored.
     *
     * [isPublic] is passed rather than inferred from a channel string so that a caller cannot widen
     * the board by accident: the decision is made once, where the channel is known, and this method
     * simply refuses what it is not told is public.
     */
    @Synchronized
    fun note(player: String, isPublic: Boolean, now: Long = System.currentTimeMillis()) {
        if (!isPublic) return
        val existing = entries.firstOrNull { it.player == player }
        if (existing != null) {
            // Re-insert rather than mutate: the ring is ordered by recency and mutating in place
            // would leave a stale position.
            entries.remove(existing)
            entries.addLast(existing.copy(mentions = existing.mentions + 1, lastAt = now))
        } else {
            entries.addLast(Presence(player, 1, now))
        }
        while (entries.size > capacity) entries.removeFirst()
    }

    /** Who has been around lately, most recent first. Entries older than the window are dropped. */
    @Synchronized
    fun recent(now: Long = System.currentTimeMillis(), limit: Int = 6): List<Presence> {
        while (entries.isNotEmpty() && now - entries.first().lastAt > windowMillis) entries.removeFirst()
        // `ArrayDeque` has no asReversed(); a bounded window makes a copy cheaper than the alternative.
        return entries.toList().asReversed().take(limit.coerceAtLeast(1))
    }

    /**
     * The one sentence a prompt gets.
     *
     * Names only, and only how many. A prompt that could quote would eventually quote, and a prompt
     * that could speculate would eventually invent — see [ImpressionPrompt], which is the only place
     * in this codebase allowed to produce a claim about a person.
     */
    @Synchronized
    fun summary(now: Long = System.currentTimeMillis(), limit: Int = 6): String {
        val around = recent(now, limit)
        if (around.isEmpty()) return ""
        return around.joinToString("；") { "${it.player}（说了 ${it.mentions} 句）" }
    }

    @Synchronized fun size(): Int = entries.size

    @Synchronized fun clear() = entries.clear()

    @Synchronized fun forget(player: String) {
        entries.removeIf { it.player == player }
    }
}