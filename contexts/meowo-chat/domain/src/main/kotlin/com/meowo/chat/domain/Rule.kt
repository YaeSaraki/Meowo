package com.meowo.chat.domain

import java.util.Locale

/**
 * A text-rewriting rule. Every rule is a **pure function**: same input, same output, no state, no
 * side effects. That is what lets the rule set be shared across the chat thread and the main
 * thread without locking, and what makes the whole pipeline cacheable.
 */
sealed interface Rule {
    val id: String

    /** Returns the rewritten text plus what matched, or null when the rule does not apply. */
    fun apply(text: String): Result?

    data class Result(val text: String, val hits: List<RuleHit>)
}

/**
 * Blocks a literal phrase regardless of case, replacing it with a fixed mask.
 *
 * The needle is lower-cased once at construction so matching stays allocation-cheap; the mask keeps
 * the original length so a censored word cannot be recovered from the length of the result.
 */
data class LiteralRule(
    override val id: String,
    private val needle: String,
    private val mask: String = "***",
    private val ignoreCase: Boolean = true,
) : Rule {
    private val lowered = if (ignoreCase) needle.lowercase(Locale.ROOT) else needle

    init {
        require(needle.isNotEmpty()) { "literal rule '$id' needs a non-empty needle" }
    }

    override fun apply(text: String): Rule.Result? {
        val haystack = if (ignoreCase) text.lowercase(Locale.ROOT) else text
        if (!haystack.contains(lowered)) return null
        val hits = mutableListOf<RuleHit>()
        val out = StringBuilder(text.length)
        var index = 0
        while (true) {
            val at = haystack.indexOf(lowered, index)
            if (at < 0) break
            out.append(text, index, at).append(mask)
            hits += RuleHit(id, text.substring(at, at + needle.length), RuleHit.Kind.FILTER)
            index = at + needle.length
        }
        out.append(text, index, text.length)
        return Rule.Result(out.toString(), hits)
    }
}

/**
 * Blocks whatever the pattern matches.
 *
 * The pattern is compiled once. Two guards matter: [maxInput] keeps a long message from becoming
 * a denial-of-service through backtracking, and [DANGEROUS_PATTERNS] rejects administrator
 * mistakes that would make the chat thread itself the victim (see docs/minimax/15).
 *
 * ## A bad pattern fails **this rule**, not the server
 *
 * The pattern is operator-supplied text in `config.yml`, and a typo in it — an unbalanced
 * parenthesis, a stray `*` — is a **compile error in Java's regex engine**. It used to escape as
 * an exception out of the constructor, through `RuleSet.fromConfig`, through the chat service's
 * constructor, and out of `ChatContext.start()`: one mistyped character in a config file and the
 * whole chat context refused to start, so the server came up with no chat at all and a stack trace
 * in the log as the only explanation.
 *
 * So the pattern is validated here, and a pattern that cannot be compiled raises
 * [IllegalArgumentException] **at construction of this one rule** — which the caller collects and
 * reports, rather than letting it escape into a context's startup.
 */
class RegexRule(
    override val id: String,
    pattern: String,
    private val mask: String = "***",
    private val maxInput: Int = 256,
    /**
     * Whether the pattern matches regardless of case.
     *
     * **On by default, like [LiteralRule]**, because the two are the same kind of thing: a filter
     * an operator writes to catch a word. A pattern that catches `fuck` but not `FUCK` is not a
     * stricter filter, it is a filter that a capital letter defeats — and the operator has no way to
     * know that, because the pattern reads exactly like the literal rules next to it in the config.
     *
     * It used to be off for regular expressions only, which made the two rule kinds behave
     * differently for no stated reason. An operator who wants case sensitivity can say so.
     */
    private val ignoreCase: Boolean = true,
) : Rule {
    private val regex: Regex

    init {
        // The dangerous-quantifier check runs **first**, and on the raw pattern string: it is a
        // substring test, so it must not depend on the regex having compiled first. Checking it
        // afterwards meant a pattern that was both catastrophic *and* valid reached the compiler
        // before anybody asked whether it was safe.
        require(DANGEROUS.none { pattern.contains(it) }) {
            "regex rule '$id' contains a nested quantifier ($pattern), which can backtrack catastrophically"
        }
        this.regex = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (error: IllegalArgumentException) {
            // Re-thrown as an IllegalArgumentException with the rule's id in the message, so the
            // operator is told *which* pattern in their config is wrong rather than being handed a
            // bare "Unclosed group near index 12".
            throw IllegalArgumentException(
                "regex rule '$id' is not a valid regular expression ($pattern): ${error.message}",
                error,
            )
        }
    }

    override fun apply(text: String): Rule.Result? {
        val input = if (text.length > maxInput) text.take(maxInput) else text
        val matches = regex.findAll(input).toList()
        if (matches.isEmpty()) return null
        val hits = matches.map { RuleHit(id, it.value, RuleHit.Kind.FILTER) }
        var out = input
        // Longest first, so a short rule cannot cut a longer match in half and leave a tail.
        matches.sortedByDescending { it.value.length }.forEach { out = out.replace(it.value, mask) }
        return Rule.Result(out, hits)
    }

    private companion object {
        /** Quantifier immediately followed by a group that can itself repeat. */
        val DANGEROUS = listOf("+)+", "*)*", "+)*", ")*+", "+)?", "*)?", "){2,}")
    }
}
