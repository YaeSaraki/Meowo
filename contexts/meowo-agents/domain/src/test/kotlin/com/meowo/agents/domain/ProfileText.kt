package com.meowo.agents.domain

/**
 * Checks on the **static** text a cat carries as its own identity — its sample, its scope.
 *
 * The model reads these on every single turn, and it is good at turning a phrase that looks like
 * data into data. A sample saying "你账上是 1,240" produces a player who was never queried being
 * told they have 1,240; a scope saying "深度 12 以下的矿" produces a depth nobody looked up.
 *
 * The rule is therefore: static text may talk **about** numbers, names and places, but may never
 * **contain** one that could be handed over as if it had been looked up.
 *
 * Kept in one place because it is one rule. Two copies of it in two test classes are two answers
 * waiting for someone to tighten one of them — the failure mode [Guard] warns about, applied to
 * the test suite.
 */
object ProfileText {

    private val COORDINATE = Regex("[xy]=?-?\\d")

    /**
     * A Chinese numeral **followed by a unit**, which is where an invented count lives.
     *
     * `isDigit` only ever sees ASCII, so "等一天再看" and "往下翻两页" sailed straight through — and
     * a model repeats "今天是第二天" as readily as it repeats "1,240". The rule says the static
     * text may not contain a number that could be handed over as if it had been looked up, and a
     * Chinese count is exactly that.
     *
     * Narrow on purpose: a bare numeral would refuse perfectly good text — "不一定", "一直",
     * "第一" all contain one — and a guard that refuses most of what it is meant to keep ends up
     * switched off.
     */
    private val CHINESE_COUNT = Regex("[零一二三四五六七八九十百千万两几半]+\\s*[次回个天次遍趟件年页段步层秒]")

    /**
     * "哪一页" and "这一件" are demonstratives: the 一 belongs to the word, not to a count, and
     * refusing them would refuse ordinary Chinese.
     */
    private val DEMONSTRATIVE = Regex("[哪这那每]一")

    /**
     * @return what is wrong with this text, or null when it is safe to put in a prompt.
     */
    fun violation(text: String): String? {
        if (text.any { it.isDigit() }) return "a number"
        if (CHINESE_COUNT.containsMatchIn(DEMONSTRATIVE.replace(text, ""))) return "a Chinese count"
        if (COORDINATE.containsMatchIn(text)) return "a coordinate"
        if (text.contains("UUID")) return "a UUID"
        if (text.contains("块")) return "a currency unit"
        return null
    }
}
