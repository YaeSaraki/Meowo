package com.meowo.chat.domain

import com.meowo.api.chat.ChatMessageView
import com.meowo.api.chat.ChatModerator
import com.meowo.api.chat.ModerationAction
import com.meowo.api.chat.ModerationVerdict
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Knobs an operator can turn without touching code. */
data class GateSettings(
    val maxLength: Int = 256,
    val warnAt: Int = 40,
    val blockAt: Int = 80,
    /**
     * What happens to a message when an auditor throws.
     *
     * `CLOSED` drops that message; `OPEN` lets it through.
     *
     * **The default is CLOSED**, and it was OPEN until it was not. The reasoning that flipped it is
     * the one thing a moderation layer exists to guarantee: a 风纪 AI that is unreachable, mis-keyed
     * or throwing on every call is indistinguishable from a server with **no** moderation at all —
     * except that the operator's log says the feature is on. An allow-by-default gate makes that
     * failure silent, and a silent gate is the exact shape of "we thought this was being watched".
     *
     * The cost is real and worth stating plainly: a broken auditor now blocks the messages it
     * inspects rather than passing them. That is a *per-message* cost, not a server-wide one — the
     * other auditors still run, and every other channel is unaffected — and it is visible: the
     * message is dropped with a reason and the failure is reported. A server that would rather have
     * an open door than a closed one can still say so in the config; what it cannot do is get the
     * open door by accident.
     */
    val failMode: FailMode = FailMode.CLOSED,
) {
    enum class FailMode { OPEN, CLOSED }

    init {
        require(maxLength in 16..4096) { "chat.max-length must be 16..4096" }
        require(warnAt in 0..100) { "chat.warn-at must be 0..100" }
        require(blockAt in 1..100) { "chat.block-at must be 1..100" }
        require(warnAt < blockAt) { "chat.warn-at must be below chat.block-at" }
    }
}

/**
 * The one place every message passes through, whatever its route.
 *
 * ## Order matters and is part of the contract
 *
 * The gate is fed the **un-redacted original**. A 风纪 AI that only ever saw `***` would have no
 * evidence to judge on and nothing to show an appeal. Redaction happens *after* this returns.
 *
 * ## Failure handling
 *
 * An auditor that throws is caught, reported, and skipped. The other auditors still run. What
 * happens to the message then is [GateSettings.failMode]; the default is CLOSED, because a gate that
 * opens itself when the watcher breaks is not a gate.
 */
class ModerationGate(
    private val rules: RuleSet,
    private val risk: RiskRegistry,
    private val settings: GateSettings = GateSettings(),
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val auditors = CopyOnWriteArrayList<ChatModerator>()

    /**
     * The one length limit, so nothing else has to keep its own copy.
     *
     * The pipeline used to carry a second `maxLength` field and truncate with it *before* the gate
     * saw the text, which made the gate's `too-long` verdict unreachable in production. See
     * [ChatPipeline].
     */
    val maxLength: Int get() = settings.maxLength

    fun register(auditor: ChatModerator): AutoCloseable {
        auditors.add(auditor)
        return AutoCloseable { auditors.remove(auditor) }
    }

    fun decide(message: ChatMessage): ModerationDecision {
        if (message.rawText.length > maxLength) {
            return ModerationDecision.block("too-long")
        }
        if (isMuted(message.player)) return ModerationDecision.block("muted")

        val view = viewOf(message)

        // Short-circuit: the first auditor with an opinion wins, so a strict one always has the
        // final say over a lenient one.
        for (auditor in auditors) {
            val verdict = try {
                auditor.inspect(view)
            } catch (error: Throwable) {
                // **Throwable, not Exception.** A catastrophic regex does not throw a checked
                // exception — it exhausts the stack and throws `StackOverflowError`, which is an
                // `Error`. Catching `Exception` there let the error escape the chat thread and take
                // the whole context down with it, which is exactly what a bad filter must never do.
                // The failure is reported and the auditor is skipped either way.
                onFailure(auditor.javaClass.simpleName, error)
                if (settings.failMode == GateSettings.FailMode.CLOSED) {
                    return ModerationDecision.block("auditor-failed")
                }
                continue
            }
            val decision = verdict.toDecision() ?: continue
            if (decision.action != ModerationAction.ALLOW) return decision
        }

        val score = risk.risk(message.player)
        if (score >= settings.blockAt) return ModerationDecision.block("risk:$score")
        if (score >= settings.warnAt) {
            val redacted = rules.redact(message.rawText)
            return ModerationDecision(ModerationAction.REDACT, "risk:$score", hits = redacted.hits)
        }

        val redacted = rules.redact(message.rawText)
        return if (redacted.hits.isEmpty()) ModerationDecision.allow()
        else ModerationDecision(ModerationAction.REDACT, "filtered", hits = redacted.hits)
    }

    private var muteCheck: (UUID) -> Boolean = { false }

    fun withMuteCheck(check: (UUID) -> Boolean) = apply { muteCheck = check }

    fun isMuted(id: UUID): Boolean = muteCheck(id)

    fun viewOf(message: ChatMessage) = ChatMessageView(
        player = message.player,
        channel = message.channel,
        rawText = message.rawText,
        atEpochMillis = message.atEpochMillis,
        risk = risk.risk(message.player),
    )

    private fun ModerationVerdict.toDecision(): ModerationDecision? = when (action) {
        // A moderator that just says ALLOW with no findings still counts, so the rule pass below
        // stays authoritative for filters.
        ModerationAction.ALLOW -> if (reason == "ok") null else ModerationDecision.allow()
        ModerationAction.BLOCK -> ModerationDecision.block(reason)
        ModerationAction.REWRITE -> replacement
            ?.let { ModerationDecision(ModerationAction.REWRITE, reason, it) }
            ?: ModerationDecision.block(reason)
        ModerationAction.REDACT -> ModerationDecision(ModerationAction.REDACT, reason)
    }
}
