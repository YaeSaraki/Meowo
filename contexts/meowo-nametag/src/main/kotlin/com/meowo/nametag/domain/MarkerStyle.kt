package com.meowo.nametag.domain

/**
 * The small mark drawn after a player's name, and the colour it uses.
 *
 * Colours live here rather than in the language files on purpose: this is decoration, not copy, and
 * unlike a message it must be identical for every viewer or two people would see different nametags.
 * Text stays in the language bundle; only the mark is owned by the domain.
 *
 * Both colours are desaturated on purpose — a fully saturated amber reads as an alarm and a pure
 * green reads as a status LED, neither of which belongs floating over someone's head all day.
 *
 * ## The colour is a **number** here, not a `TextColor`
 *
 * Adventure decides who may produce a rendered component, and this domain does not render
 * anything: it describes a mark. So it hands out the 24-bit RGB value and the layer above turns it
 * into a `TextColor`. A domain that could build the colour itself could also build the text around
 * it, and the "keep markup a plain string" rule that the chat domain depends on would stop being a
 * rule.
 */
data class MarkerStyle(
    /** Hex RGB, without any prefix. */
    val rgb: String,
    /**
     * The glyph drawn before the colour. An apostrophe is a thin, unambiguous tick that still
     * reads at a distance, where a `•` blurs into the name behind it.
     */
    val glyph: String = "'",
    /** Leading space so the mark never touches the name. */
    val gap: String = " ",
) {
    /**
     * The colour as a 24-bit RGB value.
     *
     * It parses the digits itself and **fails loudly** on malformed input. That is not fussiness:
     * Adventure's own hex lexer only accepts a `#` prefix, so handed `"0xC9A063"` or a bare
     * `"C9A063"` it returns **null** — and a component built with a null colour renders in the
     * client's default colour, silently, with no error. That is exactly the "the dot never changes
     * colour" bug this accessor exists to prevent.
     */
    fun rgbValue(): Int = rgb.toIntOrNull(16)
        ?: throw IllegalArgumentException("Marker colour must be hex digits, got '$rgb'")

    companion object {
        /**
         * Away: a muted deep amber. Kept desaturated on purpose — a fully saturated yellow reads as
         * an alarm rather than a quiet status mark.
         */
        val AWAY = MarkerStyle(rgb = "C9A063")

        /** Present: a soft light green, well short of a status LED. */
        val PRESENT = MarkerStyle(rgb = "A3D9A0")

        /**
         * Studying: red, and deliberately the loudest of the three.
         *
         * A player mid-pomodoro is doing something others should notice and should not interrupt, so
         * this is the one mark that earns a saturated colour. Amber already means "away" and green
         * already means "here"; red is the only reading left that says *busy, focused* rather than
         * *idle*. It is a deep red rather than a pure `#FF0000` so it still reads as a mark at
         * distance rather than a glare.
         */
        val STUDY = MarkerStyle(rgb = "D64545")

        fun of(state: NametagState): MarkerStyle = when (state) {
            NametagState.PRESENT -> PRESENT
            NametagState.AWAY -> AWAY
            NametagState.STUDY -> STUDY
        }
    }
}

/**
 * The three states a player can be advertised in, in the order they are decided.
 *
 * Modelled as an enum rather than the old `afk: Boolean` because a player who is mid-pomodoro is
 * *both* away by idle rules and studying — the old flag had no way to say that, and folding it into
 * "away" would show an amber dot for someone who is in fact concentrating.
 */
enum class NametagState {
    PRESENT,
    AWAY,
    STUDY;

    companion object {
        /**
         * Resolves the three inputs into one state.
         *
         * [studying] wins over idle on purpose. The idle rules exist to notice somebody who has
         * stepped away from the keyboard; a player running a focus session has declared they are
         * here on purpose, so a red study mark is more truthful than an amber away one — and it is
         * the more useful signal, since "do not disturb" is what a bystander needs to know.
         */
        fun resolve(afk: Boolean, studying: Boolean): NametagState = when {
            studying -> STUDY
            afk -> AWAY
            else -> PRESENT
        }
    }
}
