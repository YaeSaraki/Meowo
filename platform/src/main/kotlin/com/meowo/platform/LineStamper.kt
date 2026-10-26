package com.meowo.platform

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

/**
 * Who produced a line, for the record behind its `#k3f9` code.
 *
 * Deliberately a **display** identity and not a UUID pair: the code is there to answer "who said
 * this and when", and a name plus a kind is what that needs. UUIDs would make every cat line carry a
 * `null` and every private line carry two lookups for a question nobody asks that way.
 */
data class LineOrigin(
    val kind: Kind,
    /** A player's name, or a cat's id (`speaker-<id>`). */
    val speaker: String,
    /** Who it was addressed to; null for global chat and for a cat answering. */
    val target: String? = null,
) {
    enum class Kind { CAT, PLAYER, SYSTEM }
}

/**
 * Mints and records the `#k3f9` anchor for a line, and returns the component to open it with.
 *
 * `platform` cannot import the chat context, and it **should not**: the records, the store and the
 * retention policy belong to the context that owns chat. So the capability arrives as a callback, the
 * same way `Messages.speakerCard` does, and it is cleared on close so a disabled chat context leaves
 * no listener behind and every line simply renders without a code.
 */
fun interface LineStamper {
    /**
     * Records one line and returns the `#k3f9` component to prefix it with.
     *
     * [text] is the rendered line with its `§` codes, which is what a later `/meo replay` prints back.
     * Implementations must not block: this runs while a message is being delivered.
     */
    fun stamp(viewer: CommandSender?, origin: LineOrigin, text: String): Component
}

/** Renders a line origin the way `/meo replay` prints it. */
fun LineOrigin.label(): String = when (kind) {
    LineOrigin.Kind.CAT -> speaker
    LineOrigin.Kind.PLAYER, LineOrigin.Kind.SYSTEM ->
        if (target == null) speaker else "$speaker → $target"
}