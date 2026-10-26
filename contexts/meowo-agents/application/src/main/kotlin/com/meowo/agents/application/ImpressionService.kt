package com.meowo.agents.application

import com.meowo.agents.domain.CatProfile
import com.meowo.agents.domain.ChatMessage
import com.meowo.agents.domain.Completion
import com.meowo.agents.domain.ConversationKey
import com.meowo.agents.domain.ImpressionPrompt
import com.meowo.agents.domain.LlmClient
import com.meowo.agents.infrastructure.PlayerProfileStore
import com.meowo.agents.infrastructure.StoredImpression
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Layer 2 of a cat's memory: what this one cat thinks of this one player.
 *
 * ## What was missing
 *
 * Everything here existed and nothing called it. `ImpressionPrompt` — 119 lines written against a
 * **measured** hallucination, where a cat produced "他半夜不睡，去过玩家的农场三次" from nothing but
 * chat statistics — had no caller. `PlayerProfileStore.cat_impression` had a writer nobody used and
 * a reader nobody used. And `CatSink` built every question without an `ownImpression`, so the
 * prompt's `[your impression]` block could only ever say *"(你还没怎么和和他说过话)"*. Three layers
 * of memory, two of them empty: the session window remembered the last twenty lines and nothing else.
 *
 * ## Why the write is out of band and quiet
 *
 * An impression is written **after** the reply was delivered, on the agent pool, and only when the
 * cat has actually met the player [ImpressionPrompt.MIN_MEETINGS] times. It costs one model call, so
 * it is paid for out of the [Lane.PROACTIVE] allowance — the out-of-band share — and never from the
 * questions'. A server with the feature switched on and no allowance left simply keeps the impressions
 * it already has, which is the right failure: a cat that has formed an opinion keeps it.
 *
 * ## Why there is no peer layer here
 *
 * An impression is written "写给自己看的". Dumping every other cat's private note into a prompt would
 * be sharing other people's opinions by default, which is the surveillance-shaped thing this project
 * keeps refusing; [PromptBuilder.Request.peerImpressions] stays empty until something actually asks a
 * cat for another's opinion and the player consented to it. Nothing does yet, and inventing the
 * consent is not this change's job.
 */
class ImpressionService(
    private val client: LlmClient,
    private val store: PlayerProfileStore,
    private val model: String,
    private val nameOf: (UUID) -> String,
    private val traitsOf: (UUID) -> String?,
    private val localeOf: (UUID) -> String,
    /**
     * Spends one out-of-band call against [player], or refuses.
     *
     * Takes the player because the budget is per player as well as per lane: without it one talker
     * could refill the shared out-of-band share by meeting every cat often.
     */
    private val spend: (UUID) -> Boolean,
    private val onFailure: (String, Throwable?) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    /** How stale an impression may get before a meeting is allowed to rewrite it. */
    private val refreshMillis: Long = 30 * 60_000L,
) {

    /** Meetings per (player, cat). In memory: it counts conversations, not anything worth storing. */
    private val meetings = ConcurrentHashMap<ConversationKey, Int>()

    /** When a meeting last *tried* to refresh, so a spammer cannot buy one write per message. */
    private val lastAttempt = ConcurrentHashMap<ConversationKey, Long>()

    /**
     * Impressions already seen this session.
     *
     * A read that hit SQLite on every question would put a database round trip on the answer path.
     * This holds what this run wrote and one read per (cat, player); everything else is fetched once
     * and kept, and [forget] drops it with the rest of the player's state.
     */
    private val cache = ConcurrentHashMap<ConversationKey, String>()

    /** How many (cat, player) impressions this run holds. For diagnostics and tests. */
    fun cached(): Int = cache.size

    /** Meetings recorded for one pair, for tests and for the diagnostics report. */
    fun meetingsOf(player: UUID, cat: String): Int = meetings[ConversationKey(player, cat)] ?: 0

    /**
     * What this cat thinks of this player, or null when it has never met them enough.
     *
     * Read on the agent pool, never on a tick: one indexed lookup on a miss, and nothing at all on a
     * hit.
     */
    fun impressionOf(player: UUID, cat: String): String? {
        val key = ConversationKey(player, cat)
        cache[key]?.let { return it }
        val row = runCatching { store.impressionOf(cat, player.toString()) }
            .onFailure { error -> onFailure("impression read failed for $cat/$player", error) }
            .getOrNull() ?: return null
        if (row.impression.isBlank()) return null
        cache[key] = row.impression
        return row.impression
    }

    /**
     * One meeting, counted; and if it is time, one impression written.
     *
     * Blocking, and only ever called from an agent-pool thread, after the player already has their
     * answer — see the class doc for why the write is not on the delivery path.
     */
    fun afterTurn(cat: CatProfile, player: UUID, topics: List<String> = emptyList()) {
        val key = ConversationKey(player, cat.id)
        val count = (meetings[key] ?: 0) + 1
        meetings[key] = count

        val traits = traitsOf(player) ?: return
        if (count < ImpressionPrompt.MIN_MEETINGS) return
        val at = now()
        // Absent means "never tried", which is eligible — not "tried at the epoch", which would make
        // the first attempt depend on where the clock happens to start.
        val previous = lastAttempt[key]
        if (previous != null && at - previous < refreshMillis) return
        lastAttempt[key] = at

        val request = ImpressionPrompt.Request(
            cat = cat,
            playerName = nameOf(player),
            meetings = count,
            traits = traits,
            // Labels only. Whatever produced them cannot return a player's own sentence — see
            // [TopicTagger], which is why this list is safe to hand to a prompt about a person.
            topics = topics,
            locale = localeOf(player),
        )
        // Null only when there are too few meetings, which is checked above; the check is repeated
        // because the constant belongs to the prompt and this is not the place that owns the rule.
        val system = ImpressionPrompt.systemPrompt(request) ?: return

        // Charged before the request, not after: a call that fails is money spent either way, and a
        // refund here would understate what the deployment actually pays.
        if (!spend(player)) return

        val messages = listOf(
            ChatMessage.System(content = system),
            ChatMessage.User(content = ImpressionPrompt.userBlock(request)),
        )
        when (val completion = client.complete(model, messages, emptyList())) {
            is Completion.Text -> {
                val text = ImpressionPrompt.acceptImpression(completion.content)
                if (text == null) return
                runCatching {
                    store.upsertImpression(
                        StoredImpression(
                            cat = cat.id,
                            player = player.toString(),
                            impression = text,
                            noteCount = 1,
                            updatedAt = at,
                        ),
                    )
                }.onFailure { error -> onFailure("impression write failed for ${cat.id}/$player", error) }
                cache[key] = text
            }
            is Completion.Unavailable ->
                onFailure("impression call unavailable for ${cat.id}/$player: ${completion.reason}", null)
            Completion.Unusable ->
                onFailure("impression call unusable for ${cat.id}/$player", null)
            else -> Unit
        }
    }

    /** Drops a player's meetings and cached impressions when they log out. */
    fun forget(player: UUID) {
        meetings.keys.removeIf { it.player == player }
        lastAttempt.keys.removeIf { it.player == player }
        cache.keys.removeIf { it.player == player }
    }

    fun clear() {
        meetings.clear()
        lastAttempt.clear()
        cache.clear()
    }
}