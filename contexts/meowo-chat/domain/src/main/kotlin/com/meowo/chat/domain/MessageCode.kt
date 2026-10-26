package com.meowo.chat.domain


/**
 * The `#k3f9` anchor on every message.
 *
 * ## Why random, and why four characters
 *
 * The code has to be **unpredictable** and **unique**. Sequential (`#233`, `#234`) hands every player a
 * free count of how busy the server is today, and random two- or three-digit codes collide almost
 * immediately — 900 values is nothing once two dozen people are talking. Four base-36 characters give
 * 1 679 616 combinations, so collisions are a curiosity rather than a design problem, and the code
 * stays short enough to read at a glance.
 *
 * ## Why not a UUID
 *
 * Because this is a thing a **human** reads and types back. `#k3f9` fits in a command; a UUID does not.
 *
 * ## Uniqueness is checked, not hoped for
 *
 * The codes themselves are minted by [CodeBook], which knows **every** code that has ever been used
 * — the ones on disk as well as the ones from this session. A collision is not fatal in itself, but
 * it sends a reader to somebody else's message and it destroys the older row, so it is worth
 * preventing exactly rather than retrying against whatever happens to be in memory.
 */
object MessageCode {

    const val PREFIX = "#"

    /** Four base-36 characters: 36⁴ = 1 679 616. */
    const val LENGTH = 4

    /** How many codes exist at all: 36⁴. */
    const val SPACE = 1_679_616

    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    /**
     * The numeric value of a code, or -1 when it is not one.
     *
     * The code space is small enough to be held in a bitmap — 1 679 616 bits is 210 kB — so
     * "which codes are taken" can be answered exactly and cheaply instead of by keeping a set of
     * strings. See `CodeBook`.
     */
    fun indexOf(code: String): Int {
        if (code.length != LENGTH) return -1
        var index = 0
        for (character in code) {
            val digit = ALPHABET.indexOf(character)
            if (digit < 0) return -1
            index = index * ALPHABET.length + digit
        }
        return index
    }

    /** The code for a numeric value. */
    fun codeOf(index: Int): String {
        require(index in 0 until SPACE) { "code index must be 0..${SPACE - 1}" }
        val chars = CharArray(LENGTH)
        var rest = index
        for (position in LENGTH - 1 downTo 0) {
            chars[position] = ALPHABET[rest % ALPHABET.length]
            rest /= ALPHABET.length
        }
        return String(chars)
    }

    /**
     * A code, and only at the **start** of a line.
     *
     * The negative lookahead is what keeps `#ff0000` intact: a four-character run followed by more
     * word characters is a colour, not a code. Without it the plugin would eat the first four digits
     * of every hex colour a player writes.
     */
    private val PATTERN = Regex("^#[0-9a-z]{$LENGTH}(?![0-9a-z])")

    // Minting lives in `CodeBook`, and **only** there: a second implementation of "give me a free
    // code" is a second copy of the uniqueness rule, and the two would drift — which is exactly how
    // a code ends up colliding with a row that is already on disk.

    /** The bare code at the start of [text], without the `#`, or null when there is none. */
    fun leading(text: String): String? {
        val trimmed = text.trimStart()
        if (!PATTERN.containsMatchIn(trimmed)) return null
        return trimmed.substring(1, LENGTH + 1)
    }

    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }
}