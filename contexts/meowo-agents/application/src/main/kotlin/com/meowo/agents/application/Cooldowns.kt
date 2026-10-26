package com.meowo.agents.application

import com.meowo.agents.domain.ConversationKey
import com.meowo.agents.domain.Guard
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One cooldown clock: when each conversation was last used.
 *
 * ## Why it is a class and not two maps
 *
 * The sink keeps two of these — one for questions and one for greetings — and the rule that
 * matters is the same for both: **a stamp may only be given back by the call that took it.**
 *
 * That rule used to be invisible, because the code said `remove(key)` and meant "remove whatever
 * is there". When two questions for the same cat overlapped, the one that was refused by a busy
 * queue took the *other* one's stamp with it, and the player who had just asked a question was
 * let straight back in. The refusal was supposed to cost the player nothing, and it cost them the
 * rate limit.
 *
 * [release] therefore takes the stamp that was written and gives it back only if it is still
 * there — `remove(key, value)`, which is the one form of removal that cannot take somebody else's
 * property.
 */
class Cooldowns {

    private val stamps = ConcurrentHashMap<ConversationKey, Long>()

    /** Records that [key] was used at [at]. */
    fun stamp(key: ConversationKey, at: Long) {
        stamps[key] = at
    }

    /** Whether [key] is still inside its cooldown. */
    fun cooling(key: ConversationKey, now: Long, cooldownMillis: Long): Boolean =
        Guard.cooldownLeft(stamps[key] ?: 0L, now, cooldownMillis) > 0L

    /**
     * Gives a stamp back — but only if it is still the one that was taken.
     *
     * @return whether the stamp was actually there to give back.
     */
    fun release(key: ConversationKey, at: Long): Boolean = stamps.remove(key, at)

    /** Drops every stamp belonging to [player]. */
    fun releasePlayer(player: UUID) {
        stamps.keys.removeIf { it.player == player }
    }

    fun clear() {
        stamps.clear()
    }

    /** Whether [key] has a stamp at all. */
    fun contains(key: ConversationKey): Boolean = stamps.containsKey(key)
}
