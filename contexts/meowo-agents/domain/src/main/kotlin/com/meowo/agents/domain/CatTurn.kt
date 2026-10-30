package com.meowo.agents.domain

/**
 * What a cat decided to do with one question.
 *
 * Modelled as a closed set rather than free text so the runtime can enforce the rules — a model that
 * returns anything else gets a canned answer instead of being obeyed.
 */
sealed interface CatTurn {
    /** A direct answer. [text] is already the player's language. */
    data class Reply(val text: String) : CatTurn

    /**
     * "I can't answer that, ask them." [cat] must resolve in [CatRegistry], otherwise it degrades
     * to a refusal rather than being passed through.
     */
    data class Delegate(val cat: String, val reason: String) : CatTurn

    /**
     * The question is legitimate but not this cat's to answer, and not delegable either.
     *
     * A **reason**, not a sentence: the refusal reaches the player verbatim — the prompt tells the
     * model to repeat one as it is — so a Chinese sentence here is a Chinese sentence in an English
     * player's chat. The wording is rendered from the language pack; see [Refusal].
     */
    data class Refuse(val reason: Refusal, val args: List<Any> = emptyList()) : CatTurn
}