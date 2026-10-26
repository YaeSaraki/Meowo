package com.meowo.chat.domain

import com.meowo.api.chat.ModerationAction

/** What the pipeline decided to do with one message. */
sealed interface Outcome {
    /** Send [text] to the viewers. */
    data class Send(val text: String, val redacted: Boolean) : Outcome

    /** Drop it. [reason] explains why, for the audit trail and the player's own feedback. */
    data class Drop(val reason: String) : Outcome
}

/**
 * The parser pipeline, borrowed from social's parser model.
 *
 * ## The order is the contract
 *
 * ```
 * normalise → MODERATE → redact → markup → emoji
 * ```
 *
 * **The gate runs before redaction, and that is not an optimisation choice.** A 风纪 AI that only
 * ever saw `***` would have no evidence to judge on and nothing to show in an appeal.
 * `PipelineTest` pins the order by proving the moderator is handed the original.
 *
 * Every stage is a pure function over strings, so the whole thing is safe to run on the async chat
 * thread with no locking (红线 §3).
 */
class ChatPipeline(
    private val gate: ModerationGate,
    private val rules: RuleSet,
    private val emojiTable: Map<String, String> = EmojiParser.DEFAULTS,
) {

    /**
     * The one length limit, read from the gate.
     *
     * It used to be a second field on this class, and the two were set from the same config value —
     * but they were set **separately**, and the pipeline applied its own copy *before* the gate got to
     * see the text. A message over the limit was therefore silently truncated to exactly the limit,
     * and the gate's `too-long` verdict could never fire: by the time it asked, the text was already
     * legal. A rule that cannot fire is not a rule.
     *
     * Now the gate owns the limit and the pipeline asks for it, and **nothing truncates before the
     * verdict**. A message that is too long is dropped with a reason a player can be told about,
     * rather than cut off mid-sentence and sent as if that were what they wrote.
     */
    fun process(message: ChatMessage): Outcome {
        // Cleaning only — **no length cap here**. The cap is the gate's verdict, and it has to be
        // taken on the text as it was typed.
        val normalised = normalise(message.rawText)

        // ① Moderation sees the *un-redacted* text, exactly as the player typed it.
        val decision = gate.decide(message.copy(rawText = normalised))
        when (decision.action) {
            ModerationAction.BLOCK -> return Outcome.Drop(decision.reason)
            ModerationAction.REWRITE -> {
                // A rewrite still goes through the filters. The replacement is a **new** body written
                // by a moderator, not a passage of the player's text, so it can contain anything —
                // and a filter list that a rewrite walks straight past is a filter list with a hole
                // in it exactly where the interesting text is.
                val rewritten = rules.redact(decision.replacement.orEmpty())
                return Outcome.Send(
                    decorate(rewritten.text),
                    redacted = true,
                )
            }
            else -> Unit
        }

        // ② Redaction happens only after the gate has had its look.
        val redacted = rules.redact(normalised)

        return Outcome.Send(decorate(redacted.text), redacted = redacted.hits.isNotEmpty())
    }

    /**
     * Emoji first, then markup.
     *
     * The order is not cosmetic: `` `not_an_emoji `` contains an underscore, so a markup pass that ran
     * first would italicise half the name and leave `:not[i]an[/i]emoji:` behind, unrecognisable.
     * Replacements also cannot introduce stray underscores, so markup afterwards is safe.
     */
    private fun decorate(text: String): String {
        val emoji = EmojiParser.apply(text, emojiTable).text
        return MarkupFormatter.apply(emoji).text
    }

    /**
     * Collapses runs of spaces and drops control and zero-width characters.
     *
     * Applied before anything else so moderators and filters all see the same canonical text.
     *
     * **It does not cap the length any more**, and that is the point: the cap used to be applied
     * here, *before* the gate saw the text, which made the gate's `too-long` verdict unreachable —
     * every message had already been trimmed to exactly the limit by the time it was asked about.
     * Truncating a message and sending it as if it were what the player wrote is also worse than
     * dropping it: the player sees their sentence cut off mid-word and has no idea why. A dropped
     * message has a reason; a truncated one looks like a bug.
     *
     * The length rule now lives in exactly one place — [ModerationGate.maxLength] — and this function
     * only decides what the text *is*.
     */
    fun normalise(raw: String): String {
        val out = StringBuilder(raw.length)
        var pendingSpace = false
        for (c in raw) {
            when {
                c == ' ' -> pendingSpace = out.isNotEmpty()
                c.isISOControl() || ZERO_WIDTH.contains(c) -> Unit
                else -> {
                    if (pendingSpace) { out.append(' '); pendingSpace = false }
                    out.append(c)
                }
            }
        }
        return out.toString().trim()
    }

    private companion object {
        const val ZERO_WIDTH = "‌‍⁠﻿"
    }
}
