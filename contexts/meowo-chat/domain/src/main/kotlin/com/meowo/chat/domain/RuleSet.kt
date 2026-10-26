package com.meowo.chat.domain

/**
 * The compiled, immutable rule set. Built once when the context starts and swapped wholesale on
 * reload, exactly the way `Messages.bundles` is — so a reload can never be observed half-applied.
 */
class RuleSet private constructor(
    val filters: List<Rule>,
    /**
     * The rules that could not be compiled, and why.
     *
     * Kept rather than thrown: an operator's `config.yml` is hand-written text, and one bad regular
     * expression in it must cost **that one filter**, not the whole chat context. The rejected ones
     * are reported at start-up so the operator learns their pattern is wrong instead of discovering
     * it by noticing that a word they banned is no longer banned.
     */
    val rejected: List<RejectedRule> = emptyList(),
) {

    /** Every match across every filter, plus the text with all of them masked. */
    fun redact(text: String): Rule.Result {
        val hits = mutableListOf<RuleHit>()
        var current = text
        for (rule in filters) {
            val applied = rule.apply(current) ?: continue
            current = applied.text
            hits += applied.hits
        }
        return Rule.Result(current, hits)
    }

    /** One rule that did not compile, with the reason. */
    data class RejectedRule(val id: String, val reason: String)

    companion object {
        fun of(filters: List<Rule> = emptyList()): RuleSet = RuleSet(filters.toList())

        /**
         * Parses the literal/regex lists an operator wrote in config.
         *
         * A pattern that does not compile is **skipped and reported**, not thrown. The alternative —
         * letting the exception out — took the entire chat context down at start-up, because the
         * rule set is built inside the service's constructor and the service's constructor runs
         * inside `ChatContext.start()`. One unbalanced parenthesis in `config.yml` meant a server
         * with no chat at all, and the operator's only clue was a stack trace about a regex.
         *
         * The literal list cannot fail (an empty needle is filtered out below), but it goes through
         * the same path so both kinds of filter are treated alike.
         */
        fun fromConfig(literals: List<String>, patterns: List<String>): RuleSet {
            val filters = mutableListOf<Rule>()
            val rejected = mutableListOf<RejectedRule>()

            literals.filter { it.isNotBlank() }.forEach { literal ->
                val id = "literal:${literal.lowercase()}"
                try {
                    filters += LiteralRule(id, literal.trim())
                } catch (error: IllegalArgumentException) {
                    rejected += RejectedRule(id, error.message ?: "invalid literal filter")
                }
            }
            patterns.filter { it.isNotBlank() }.forEach { pattern ->
                val id = "regex:$pattern"
                try {
                    filters += RegexRule(id, pattern.trim())
                } catch (error: IllegalArgumentException) {
                    rejected += RejectedRule(id, error.message ?: "invalid regex filter")
                }
            }
            return RuleSet(filters, rejected)
        }
    }
}
