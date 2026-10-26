package com.meowo.chat.domain

import com.meowo.api.chat.ModerationAction
import java.util.UUID

/**
 * The only input the pipeline accepts. Deliberately carries no `Component` and no `Player`: the
 * domain has to stay free of the Bukkit ecosystem so it can be tested in milliseconds and evolve
 * independently (红线 §1).
 */
data class ChatMessage(
    val player: UUID,
    val channel: String,
    /** The player's raw text, before any redaction. Moderators need the original. */
    val rawText: String,
    val atEpochMillis: Long,
)

/** What one rule matched, so a decision can be explained rather than just enforced. */
data class RuleHit(val ruleId: String, val matched: String, val kind: Kind) {
    enum class Kind { FILTER, MARKUP, EMOJI }
}

/**
 * The outcome of the moderation gate.
 *
 * [reason] is a free string on purpose — an external 风纪 AI has to be able to introduce new
 * reason codes without waiting for a plugin release, and the reason is what an appeal is judged on.
 */
data class ModerationDecision(
    val action: ModerationAction,
    val reason: String,
    val replacement: String? = null,
    val hits: List<RuleHit> = emptyList(),
) {
    companion object {
        fun allow() = ModerationDecision(ModerationAction.ALLOW, "ok")
        fun block(reason: String) = ModerationDecision(ModerationAction.BLOCK, reason)
    }
}
