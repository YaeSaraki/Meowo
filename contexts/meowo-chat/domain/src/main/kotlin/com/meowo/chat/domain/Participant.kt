package com.meowo.chat.domain

/**
 * Anything that can be addressed in chat.
 *
 * The point of modelling system speakers as participants is that the plugin's own messages stop
 * being decoration and become addressable: a player can `/msg EconCat …`, and whoever claimed the
 * `economy` speaker receives it.
 */
sealed interface Participant {
    val handle: String
    val kind: Kind

    enum class Kind { PLAYER, SYSTEM }

    /** A real player. [known] is false for a name that could not be resolved to a real account. */
    data class Player(
        override val handle: String,
        val uuid: java.util.UUID,
        val online: Boolean,
    ) : Participant {
        override val kind = Kind.PLAYER
    }

    /**
     * One of the plugin's message speakers — `EconCat`, `TeleCat`, … [id] matches the `speaker-<id>`
     * language key and the owning context's id, so the two can never drift apart silently.
     */
    data class System(
        val id: String,
        override val handle: String,
        /** Localised one-liner describing what this speaker is for. */
        val summary: String,
        /** Whether a sink currently holds this speaker, i.e. whether anybody answers. */
        val answerable: Boolean,
    ) : Participant {
        override val kind = Kind.SYSTEM
    }
}
