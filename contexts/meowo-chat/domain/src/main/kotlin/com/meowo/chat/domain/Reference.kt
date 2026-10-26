package com.meowo.chat.domain

/**
 * What a player typed before the message itself: an optional `#k3f9` to answer, then the body.
 *
 * ## There is no quote marker any more
 *
 * `❝` used to be parsed here, and it cost more than it bought. A prefilled reply carried
 * `#k3f9 ❝在吗 `, and because the marker runs to the end of the line, the **quoted text and the message
 * were the same string** — the renderer had to choose which one to print, and either choice was wrong:
 * print both and the sentence appears twice, print one and the player's actual reply disappears.
 *
 * The code fixes it. `#k3f9` is unambiguous, and the renderer looks the original up in the log and
 * prints it behind `❝` as **decoration**, next to a body that is always the player's own words.
 *
 * ## Why the code is visible at all
 *
 * It travels as ordinary text the player can edit or delete, not as hidden state. Nothing is lost if
 * they retype it, and a reader can see which message a reply points at without asking.
 */
data class Reference(
    /** The bare code being answered, without `#`; null when the player is not replying. */
    val anchor: String?,
    val body: String,
) {
    /** True when this message points at another one. */
    val isReply: Boolean get() = anchor != null

    companion object {
        val EMPTY = Reference(null, "")

        fun parse(input: String): Reference {
            var rest = input.trimStart()
            val anchor = MessageCode.leading(rest)
            if (anchor != null) rest = rest.substring(MessageCode.LENGTH + 1).trimStart()
            return Reference(anchor, rest.trim())
        }
    }
}