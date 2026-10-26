package com.meowo.chat.domain

/**
 * Player-facing markup, in the spirit of social's Formatter parser but deliberately tiny:
 * `**bold**`, `_italic_` and `<ins>underline</ins>`.
 *
 * Three rules make this safe to run on anything a player types:
 *
 * - An unclosed marker is **left alone**. Half-consuming `**hello` would silently eat the rest of
 *   the message, which reads as data loss.
 * - A backslash escapes the next character, so `\*\*not bold\*\*` renders literally.
 * - `_` only opens or closes emphasis at a **word boundary**. This is what stops `not_an_emoji`
 *   from becoming `not<i>an</i>emoji` and destroying an emoji name that happens to be unknown.
 *
 * ## The output is BBCode, and that sentence used to be here
 *
 * This KDoc previously claimed: *"Output markers use the same vocabulary as the input, so a result
 * can be re-parsed or handed straight to a legacy `§` serializer without a second translation
 * table."* **`[b]` is not `§l`.** The claim was false, it justified the BBCode choice, and for as
 * long as it stood `**粗体**` reached players as the literal characters `[b]粗体[/b]`.
 *
 * ## Why BBCode and not `§`, which really is the renderer's vocabulary
 *
 * Because `§` would make a player's outgoing string **indistinguishable from a serialisation
 * instruction**. `§` is U+00A7, not a control character, so it survives normalisation, and the
 * legacy reader would carry out `§c§lADMIN` as red bold text that reads as the server's own.
 * Format codes belong to whoever wrote the string; BBCode stays a **string**, which is also why
 * this class can live in a pure domain source root that is forbidden from importing Adventure.
 *
 * **The interpreter is [`ChatLine.body`]** — one function, three tags, and only balanced pairs.
 * Nothing else may read these markers: a second reader is a second opinion about who owns the
 * string, which is the exact disagreement that caused this. See 08 §44.
 */
object MarkupFormatter {

    private data class Pattern(val open: String, val close: String, val wrap: (String) -> String)

    private val PATTERNS = listOf(
        Pattern("**", "**") { "[b]$it[/b]" },
        Pattern("<ins>", "</ins>") { "[u]$it[/u]" },
        // Underscore last, and boundary-checked, so it cannot interfere with the others.
        Pattern("_", "_") { "[i]$it[/i]" },
    )

    fun apply(text: String): Rule.Result {
        var result = text
        val hits = mutableListOf<RuleHit>()
        for (pattern in PATTERNS) {
            val applied = applyOne(result, pattern) ?: continue
            result = applied.text
            hits += RuleHit("markup:${pattern.open}", pattern.open, RuleHit.Kind.MARKUP)
        }
        return Rule.Result(result, hits)
    }

    /**
     * One pass, left to right.
     *
     * Escapes have to be resolved *here* rather than in a separate pre-pass: stripping `\` from
     * `\*\*x\*\*` first would hand the next pass a perfectly good `**x**` to bold, which is the
     * opposite of what the player asked for.
     */
    private fun applyOne(text: String, pattern: Pattern): Rule.Result? {
        val out = StringBuilder(text.length)
        var i = 0
        var found = false
        var escaped = false
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                out.append(text[i + 1]); i += 2; escaped = true; continue
            }
            if (isBoundary(text, i, pattern)) {
                val closeAt = text.indexOf(pattern.close, i + pattern.open.length)
                val body = if (closeAt < 0) "" else text.substring(i + pattern.open.length, closeAt)
                if (closeAt >= 0 && body.isNotEmpty()) {
                    out.append(pattern.wrap(body))
                    found = true
                    i = closeAt + pattern.close.length
                    continue
                }
            }
            out.append(text[i]); i++
        }
        // Escapes count as a change: discarding the escaped output would put the backslashes back
        // and make `\*\*` render as bold anyway, one pass later.
        return when {
            found -> Rule.Result(out.toString(), listOf(RuleHit("markup:${pattern.open}", pattern.open, RuleHit.Kind.MARKUP)))
            escaped -> Rule.Result(out.toString(), listOf(RuleHit("markup:escape", "\\", RuleHit.Kind.MARKUP)))
            else -> null
        }
    }

    /** A single-character marker only counts when it is not glued to a word. */
    private fun isBoundary(text: String, i: Int, pattern: Pattern): Boolean {
        if (!text.startsWith(pattern.open, i)) return false
        if (pattern.open != "_") return true
        val before = i - 1
        val after = i + pattern.open.length
        return (before < 0 || !isWord(text[before])) && (after >= text.length || !isWord(text[after]))
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'
}
