package com.meowo.api.event

import java.util.UUID

/** Immutable integration contracts; no Player objects may escape the platform boundary. */
data class PlayerJoined(val player: UUID)
data class BalanceChanged(val transaction: UUID, val player: UUID, val before: java.math.BigDecimal, val after: java.math.BigDecimal, val operation: String)

/**
 * A message was refused before it reached anyone.
 *
 * The chat context does **not** punish anybody — it has no business knowing how. A future `punish`
 * context subscribes to this and applies the sanction, which is the only way two contexts can
 * cooperate without one importing the other (红线 §2).
 */
data class ChatMessageBlocked(
    val player: UUID,
    val channel: String,
    val reason: String,
    val atEpochMillis: Long,
)

/**
 * A message reached the channel.
 *
 * Exists so a context can **learn from** chat without joining the moderation path. A
 * `ChatModerator` is inspected on the hot path for every message under a sub-millisecond budget, so
 * a listener that only wants to count messages has no business registering as one — that is why
 * this event exists and why the profile tracker is built on it rather than on `onPublished`.
 *
 * @param text the body as it was published. Handled as data at the far end, never as instructions.
 */
data class ChatMessagePublished(
    val player: UUID,
    val channel: String,
    val text: String,
    val isPublic: Boolean,
    val hasEmoji: Boolean,
    val atEpochMillis: Long,
)

/** A player addressed one of the plugin's system speakers directly. Kept for auditing. */
data class DirectMessageToSystem(
    val player: UUID,
    val speakerId: String,
    val atEpochMillis: Long,
)

/**
 * A player's own avatar changed — the insomnia phantoms were switched on or off, or the body size moved.
 *
 * The `avatar` context owns those two switches; the main menu draws them. Neither may import the other
 * (红线 §2), so the change travels as a **notification** instead: the avatar context publishes it, and
 * whoever draws the player (today: the menu) redraws.
 *
 * It is a notification and not a command: a subscriber that misses it has lost a repaint, not a fact.
 * Nobody may read the new state out of the event either — the event says *that* something changed, and
 * the state is read from [com.meowo.api.AvatarAPI], so there is only one truth about it.
 */
data class AvatarChanged(val player: UUID)
