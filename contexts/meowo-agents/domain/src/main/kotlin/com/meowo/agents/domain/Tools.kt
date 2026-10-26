package com.meowo.agents.domain

import java.util.UUID

/**
 * What a tool produced.
 *
 * [Refused] is kept distinct all the way to the player, because a refusal shown as an answer reads
 * as data: the model would confidently repeat "这属于对方的资料" as though it were a finding.
 */
sealed interface ToolAnswer {
    data class Data(val text: String) : ToolAnswer

    /** [message] is a [Redaction] constant or a [CatTurn.Refuse] line, never data. */
    data class Refused(val message: String) : ToolAnswer
}

/** Everything a tool needs to decide what it may return. */
data class ToolContext(
    val asker: UUID,
    val cat: CatProfile,
    val askerIsAdmin: Boolean,
    /**
     * Resolves the disclosure verdict for someone else's data. Supplied by the caller so this class
     * holds no configuration and no reference to the store.
     */
    val disclose: (subject: UUID) -> Redaction.Verdict,
    /**
     * Resolves a **name a player typed** into the player it means, or null when it is ambiguous or
     * unknown.
     *
     * Needed because a model reasons in names while everything here is a UUID. It refuses rather
     * than picking a candidate: answering a question about the wrong player is the one error a
     * discipline tool must not make, and a model holding the wrong name cannot be talked out of
     * it. A name that is already a UUID passes through, so a caller may use either.
     */
    val lookupPlayer: (name: String) -> UUID? = { null },
)

/** A tool that simply has the data, with no policy decision to make. */
fun plain(text: String): ToolAnswer = ToolAnswer.Data(text)
