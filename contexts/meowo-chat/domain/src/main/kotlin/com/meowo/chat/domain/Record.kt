package com.meowo.chat.domain

import java.util.UUID

/** Where a line came from. Decides how the thread around it reads. */
enum class Channel { GLOBAL, PRIVATE, SYSTEM }

/**
 * One recorded line: the `#k3f9` anchor, who said it, when, and what it answers.
 *
 * Stored **flat**, one row per rendered line. A thread is not a structure that has to be maintained
 * on write — it is whatever rows point at the same [anchor], discovered on read. That keeps the write
 * path to one insert, and means a reply whose parent has already been evicted degrades to a shorter
 * thread instead of vanishing.
 *
 * [author] is always the **display** name a player would recognise — `Alice`, `EconCat` — never the
 * internal id. [speakerId] keeps the id alongside it so a later feature can still address the cat.
 */
data class Record(
    /** The `#k3f9` anchor, stored **without** the `#`. */
    val code: String,
    val channel: Channel,
    /** Display name: a player's name, or a cat's `XxxCat`. */
    val author: String,
    /** The cat's internal id (`economy`); null for anything a player said. */
    val speakerId: String? = null,
    val authorId: UUID? = null,
    /** Who the line was addressed to; null for global chat and for a cat's answer. */
    val recipient: String? = null,
    val recipientId: UUID? = null,
    val body: String,
    /** The code this line answers, without `#`; null when it is not a reply. */
    val anchor: String? = null,
    val atEpochMillis: Long,
) {
    /**
     * **There is no `displayTime()` here on purpose.**
     *
     * Formatting a date is the platform's business: this domain does not know how a time is written
     * for a player, and the moment it decides that itself there are two answers to "what time was
     * that" — one here and one wherever else a timestamp is shown. The caller formats
     * `atEpochMillis`; this record only carries the moment.
     */

    /** `Alice → Bob`, or just `Alice` when the line went to everybody. */
    fun participants(): String = if (recipient == null) author else "$author → $recipient"
}