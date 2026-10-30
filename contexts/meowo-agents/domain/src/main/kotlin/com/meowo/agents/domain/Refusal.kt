package com.meowo.agents.domain

/**
 * Why a cat would not answer.
 *
 * ## What this is for
 *
 * A cat that refuses used to carry the refusal **as a sentence**, in Chinese, in the middle of the
 * pure layer. Three different things could refuse — the data, the call, the cat itself — and each
 * one had its own hard-coded line. An English player was told, in Chinese, that a tool was
 * unavailable, and the prompt made it worse: the model is told to repeat a refusal *as it is*
 * ("照原话说"), so it repeated the Chinese verbatim.
 *
 * So the refusal is now a **reason**, and the sentence is somebody else's job.
 *
 * ## Why the reasons live here
 *
 * The domain is the layer that owns the vocabulary of what can happen. The reasons are what can
 * happen; the wording is what the server says about it. [Redaction] already worked this way — its
 * verdict is a decision about scope, not a phrase — and this file extends the same shape to the
 * rest of the context.
 *
 * The five sets are kept apart on purpose. They are different axes: "the data is not yours", "the
 * tool cannot answer", "the call was not usable", "the cat will not answer right now" and "the
 * provider failed" are five different facts, and a caller that had to ask which family a reason
 * belonged to before it could act would be worse off than one that did not.
 *
 * What they share is [Refusal], so that the layer that turns a reason into a sentence can cover
 * all of them in one exhaustive `when` — and the compiler, not a reviewer, is what notices a new
 * reason nobody wrote a sentence for.
 */
sealed interface Refusal

/**
 * The data will not be handed over.
 *
 * @see Redaction for the rule that decides these.
 */
enum class RedactionRefusal : Refusal {
    /** The subject's record is not the asker's to see. */
    PRIVATE,

    /** The subject closed their profile; their choice outranks an admin's curiosity. */
    OPTED_OUT,

    /** The asker may not look at anybody else's record. */
    NOT_ALLOWED,
}

/**
 * The tool could not produce an answer.
 *
 * These are the tool's own outcomes, not the caller's: a lookup that timed out, a lookup that
 * failed, an argument that could not be read.
 */
enum class ToolRefusal : Refusal {
    /** The lookup is still running, and the cat will not wait for it twice. */
    PENDING,

    /** The lookup failed. Never rendered as a number — a failed balance is not zero. */
    FAILED,

    /** The ticket id the model sent could not be read. */
    BAD_TICKET,

    /** Claiming the daily subsidy is the player's own command, not the cat's. */
    DAILY_IS_YOURS,

    /** The name the model sent belongs to nobody here. */
    UNKNOWN_PLAYER,
}

/**
 * The call itself was not usable.
 *
 * These say something about the **call**, not about the data: the model asked for something the
 * cat cannot do, the tool is not wired, the arguments were unreadable, or the tool threw.
 */
enum class CallRefusal : Refusal {
    /** The cat does not have that ability. */
    NO_CAPABILITY,

    /** The tool is wired for nobody. A startup bug, not a player's mistake. */
    NOT_WIRED,

    /** The arguments the model sent could not be read. */
    BAD_ARGUMENTS,

    /** The tool threw. */
    THREW,
}

/**
 * The cat will not answer this question right now.
 *
 * @see com.meowo.agents.domain.Redaction for a different kind of "no": that one is about the data,
 *   this one is about the moment.
 */
enum class TurnedAway : Refusal {
    /** Nobody is answering that speaker id. */
    NOT_A_CAT,

    /** The same player asked the same cat again too soon. */
    COOLDOWN,

    /** The daily allowance is spent. */
    QUOTA,

    /** Policy said no: the player is muted, opted out, or the asker may not have cats. */
    NOT_ALLOWED,

    /** The agent pool is saturated; dropped rather than queued forever. */
    BUSY,
}

/** The provider failed and the cat has nothing to say. */
enum class Apology : Refusal {
    /** The provider failed, and there is no answer to salvage. */
    PROVIDER_FAILED,
}

/**
 * Turns a reason into a sentence in the player's language.
 *
 * The seam: the pure layer knows **what happened**, and this knows **how to say it**. The wording
 * lives in the language pack, so the sentence is rendered by the message layer rather than
 * assembled here — and a refusal reaches the player in the language the player reads.
 *
 * @param locale the client language to render in, as the message layer resolves it.
 * @param reason what happened.
 * @param args the values the sentence's placeholders take, in order.
 */
typealias RefusalRenderer = (locale: String, reason: Refusal, args: List<Any>) -> String
