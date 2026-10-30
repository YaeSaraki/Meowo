package com.meowo.agents

import com.meowo.agents.domain.Apology
import com.meowo.agents.domain.CallRefusal
import com.meowo.agents.domain.RedactionRefusal
import com.meowo.agents.domain.Refusal
import com.meowo.agents.domain.ToolRefusal
import com.meowo.agents.domain.TurnedAway
import com.meowo.platform.Messages

/**
 * The one place a refusal becomes a sentence.
 *
 * ## Why the wording is here and not in the pure layer
 *
 * The pure layer knows **what happened**: a reason and the values its sentence takes. The wording
 * is a property of the server, not of the cat — an operator rewrites it in the language pack and
 * nobody has to touch Kotlin. So the wording lives in the pack, the pack is rendered by the
 * message layer, and this class is the adapter between the two: a reason in, a sentence out.
 *
 * It sits in the context's root layer because that is the layer that has the [Messages]. The
 * platform module cannot hold it: `platform` depends on `api` and `shared` only, so it cannot see
 * a type that belongs to this context, and it has no business knowing what a cat refuses.
 *
 * ## Why one `when`
 *
 * The reasons are a sealed hierarchy, so this `when` is exhaustive and the compiler says so. A new
 * reason nobody wrote a sentence for is a **compile error**, not a Chinese line in an English
 * player's chat. That is the whole point of moving the reasons into the domain: the closure is
 * enforced by a type, not by a reviewer.
 *
 * ## Why the keys are literals here
 *
 * Every branch calls `messages.textFor` with a literal key, so the language gate — which scans
 * source for send-shaped calls — picks all of them up without being told about this file. A key
 * that does not exist in either bundle fails the build instead of reaching a player as a raw key.
 */
class AgentRefusals(private val messages: Messages) {

    /**
     * @param locale the client language to render in; the message layer applies the language mode.
     * @param reason what happened.
     * @param args the values the sentence's placeholders take, in order.
     */
    fun render(locale: String, reason: Refusal, args: List<Any>): String = when (reason) {
        RedactionRefusal.PRIVATE -> messages.textFor(locale, "agents-redaction-private")
        RedactionRefusal.OPTED_OUT -> messages.textFor(locale, "agents-redaction-opted-out")
        RedactionRefusal.NOT_ALLOWED -> messages.textFor(locale, "agents-redaction-not-allowed")

        ToolRefusal.PENDING -> messages.textFor(locale, "agents-tool-pending")
        ToolRefusal.FAILED -> messages.textFor(locale, "agents-tool-failed")
        ToolRefusal.BAD_TICKET -> messages.textFor(locale, "agents-tool-bad-ticket")
        ToolRefusal.DAILY_IS_YOURS -> messages.textFor(locale, "agents-tool-daily-is-yours")
        ToolRefusal.UNKNOWN_PLAYER -> messages.textFor(locale, "agents-tool-unknown-player")

        // Two of these carry the tool's name, so a player is told *which* capability failed rather
        // than that some capability did.
        CallRefusal.NO_CAPABILITY ->
            messages.textFor(locale, "agents-call-no-capability", *args.toTypedArray())
        CallRefusal.NOT_WIRED ->
            messages.textFor(locale, "agents-call-not-wired", *args.toTypedArray())
        CallRefusal.BAD_ARGUMENTS -> messages.textFor(locale, "agents-call-bad-arguments")
        CallRefusal.THREW ->
            messages.textFor(locale, "agents-call-threw", *args.toTypedArray())

        TurnedAway.NOT_A_CAT -> messages.textFor(locale, "agents-decline-not-a-cat")
        // The cooldown line carries the cat's own deflection — its persona, which stays in the
        // cat's own voice and language. A cat with no deflection gets the plain sentence instead,
        // because a sentence with an unfilled `{0}` in it would be worse than a plainer one.
        TurnedAway.COOLDOWN ->
            if (args.isEmpty()) messages.textFor(locale, "agents-decline-cooldown-plain")
            else messages.textFor(locale, "agents-decline-cooldown", *args.toTypedArray())
        TurnedAway.QUOTA -> messages.textFor(locale, "agents-decline-quota")
        TurnedAway.NOT_ALLOWED -> messages.textFor(locale, "agents-decline-not-allowed")
        TurnedAway.BUSY -> messages.textFor(locale, "agents-decline-busy")

        Apology.PROVIDER_FAILED -> messages.textFor(locale, "agents-apologise")
    }
}
