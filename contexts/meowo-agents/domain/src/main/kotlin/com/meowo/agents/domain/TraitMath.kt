package com.meowo.agents.domain

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The in-memory counters behind one player, and the pure mapping from them to something a cat may
 * read out loud.
 *
 * Incremented on every published message in O(1); never queried against the database.
 */
class BehaviorCounters {
    var samples: Int = 0; private set
    var charTotal: Long = 0L; private set
    var publicCount: Int = 0; private set
    var privateCount: Int = 0; private set
    var emojiUses: Int = 0; private set

    /** Messages per hour-of-day; index 0..23. */
    val activeHours: IntArray = IntArray(24)

    // ── style counters ───────────────────────────────────────────────────────────────────────
    // Raw counts, never ratios: a ratio needs a denominator, and a denominator that can be
    // computed per message is a denominator that can be wrong per message.
    var questionMarks: Int = 0; private set
    var exclamations: Int = 0; private set
    var trailingDots: Int = 0; private set
    var tildes: Int = 0; private set
    var shouted: Int = 0; private set
    var shortReplies: Int = 0; private set
    var longMessages: Int = 0; private set
    var strongLanguage: Int = 0; private set
    var silent: Int = 0; private set

    @Synchronized
    fun onMessage(text: String, isPublic: Boolean, hasEmoji: Boolean, hourOfDay: Int) {
        samples++
        charTotal += text.length
        if (isPublic) publicCount++ else privateCount++
        if (hasEmoji) emojiUses++
        activeHours[hourOfDay.coerceIn(0, 23)]++
        if (text.any { it == '?' || it == '\uFF1F' }) questionMarks++
        if (text.any { it == '!' || it == '\uFF01' }) exclamations++
        if (text.contains("\u2026") || text.contains("...")) trailingDots++
        if (text.contains('~') || text.contains("\uFF5E")) tildes++
        if (SHOUTED.containsMatchIn(text)) shouted++
        if (text.trim().length in 1..2) shortReplies++
        if (text.length > LONG_MESSAGE_CHARS) longMessages++
        if (TraitMath.strongLanguageIn(text)) strongLanguage++
        if (text.isBlank()) silent++
    }

    fun snapshot(): ChatShape = ChatShape(
        samples = samples,
        avgLen = if (samples == 0) 0.0 else charTotal.toDouble() / samples,
        publicPct = if (samples == 0) 0.0 else publicCount.toDouble() * 100 / samples,
        emojiPct = if (samples == 0) 0.0 else emojiUses.toDouble() * 100 / samples,
        // A peak only exists when one hour genuinely stands out; see TraitMath.peakHourOf.
        peakHour = TraitMath.peakHourOf(activeHours),
    )

    /** The 24-slot histogram, which is also what a co-presence score is computed from. */
    fun hourly(): IntArray = activeHours.copyOf()

    private companion object {
        const val LONG_MESSAGE_CHARS = 60

        /**
         * Enough capital letters to be shouting, in a language that has no other use for them.
         *
         * A run of capitals was tried first and is wrong: English and German separate words with
         * spaces, so "STOP DOING THAT" — the clearest shouting there is — has no run of six.
         */
        val SHOUTED = Regex("[A-Z]{3,}(\\s+[A-Z]{3,})+|[A-Z]{8,}")
    }

    /**
     * Re-seeds the totals from a stored row.
     *
     * The histogram is not persisted — only the summary it produced — so a restored player has no
     * peak until they say enough again. That is the honest direction to be wrong in: a missing
     * habit costs a sentence, whereas a fabricated one gets read as fact by every cat.
     */
    @Synchronized
    fun restore(samples: Int, charTotal: Long, publicCount: Int, privateCount: Int, emojiUses: Int) {
        this.samples = samples
        this.charTotal = charTotal
        this.publicCount = publicCount
        this.privateCount = privateCount
        this.emojiUses = emojiUses
    }
}

/** An immutable, prompt-safe snapshot. Carries no evaluative data — see [NEVER_IN_PROMPT]. */
data class ChatShape(
    val samples: Int,
    val avgLen: Double,
    val publicPct: Double,
    val emojiPct: Double,
    val peakHour: Int?,
)

/**
 * Turns counters into words.
 *
 * Every threshold is deliberately coarse and the wording is deliberately flat: this describes how
 * someone talks, never what that means. Anything evaluative lives in `soft_hits`, which never
 * reaches this function.
 */
object TraitMath {

    /** Below this many messages there is nothing honest to say. */
    const val MIN_SAMPLES = 5

    /**
     * Above this many messages a trait is worth stating without hedging.
     *
     * Five messages and five thousand produced the identical sentence before this existed, which
     * means a player seen once was characterised as firmly as a regular. The wording does not need
     * to be different — it needs to be *marked* as provisional.
     */
    const val SOLID_SAMPLES = 40

    /**
     * How far the busiest hour must stand above the average before it counts as a habit.
     *
     * A player who is online all evening is not a night owl, and with `maxByOrNull` a flat
     * distribution resolved to index 0 — so a measurement showed someone active 50 messages in
     * every hour of the day described as "常在 凌晨 活跃". That sentence is then stored and read by
     * every cat as fact. A distribution this flat has **no** peak, and saying so is the honest
     * answer.
     */
    const val PEAK_DOMINANCE = 1.8

    /** Capped, because this string goes into the prompt of every question every cat answers. */
    const val MAX_TRAIT_PARTS = 8

    fun traitsOf(shape: ChatShape): String? = traitsOf(shape, BehaviorCounters())

    /**
     * The same description, with the style habits folded in.
     *
     * [ChatShape] carries only what survives a round trip through the database, so a caller holding
     * live counters passes them here to get the full picture. A caller that only has the stored
     * summary still gets the four coarse traits, never a wrong one.
     */
    fun traitsOf(shape: ChatShape, live: BehaviorCounters): String? {
        if (shape.samples < MIN_SAMPLES) return null
        val parts = mutableListOf<String>()
        parts += when {
            shape.avgLen < 8 -> "句子很短"
            shape.avgLen < 25 -> "句子不长"
            shape.avgLen > 90 -> "喜欢长篇大论"
            else -> "表达中等长度"
        }
        parts += when {
            shape.publicPct >= 90 -> "几乎都在公频说话"
            shape.publicPct <= 20 -> "更爱私聊"
            else -> "公频私聊都聊"
        }
        if (shape.emojiPct >= 30) parts += "常用 emoji"
        shape.peakHour?.let { parts += "常在 ${hourLabel(it)} 活跃" }
        // Only the strongest habits, and only a few of them: this text is re-sent on every question,
        // so an unbounded list is a per-call cost, not a one-off write.
        for (signal in styleSignals(live).sortedByDescending { it.share }.take(MAX_TRAIT_PARTS)) {
            parts += signal.text
        }

        // Thin evidence is stated as thin. A cat that cannot tell "I know this person" from "I have
        // seen them type five times" will say the second one with the first one's confidence.
        val hedge = if (shape.samples < SOLID_SAMPLES) "才聊过几次，大概是：" else ""
        return hedge + parts.joinToString("，") + "。"
    }

    /**
     * The hour somebody is actually usually around, or null when the distribution has no peak.
     *
     * Null is a real answer and not a missing one: it is what stops a player who is online at all
     * hours from being labelled a night owl.
     */
    fun peakHourOf(hourly: IntArray): Int? {
        if (hourly.isEmpty()) return null
        val total = hourly.sum()
        if (total <= 0) return null
        val busiest = hourly.max()
        val mean = total.toDouble() / hourly.size
        if (mean <= 0.0) return null
        return if (busiest / mean >= PEAK_DOMINANCE) hourly.indexOf(busiest) else null
    }

    // ── style signals ────────────────────────────────────────────────────────────────────────

    /**
     * Whether a message contains ordinary swearing.
     *
     * A short, explicit list, matched as substrings. It is here to describe **how somebody talks**,
     * in the same way "uses emoji a lot" does, and it is deliberately not a moral judgement: the
     * rendered wording says they curse, not that they are rude. A wider or cleverer matcher would
     * start catching words the writer never meant as profanity, and a profile that mislabels
     * someone is worse than one that says less.
     */
    private val STRONG_LANGUAGE = listOf(
        "卧槽", "我操", "草泥马", "尼玛", "妈的", "tmr", "Damn", "damn", "shit", "fuck", " hell ",
    )

    fun strongLanguageIn(text: String): Boolean = STRONG_LANGUAGE.any { text.contains(it) }

    /**
     * The habits worth saying out loud, each with the sample floor it needs.
     *
     * A floor per signal rather than one global one: "asks a lot of questions" is readable from
     * twenty messages, while "swears constantly" needs far more before it is worth saying about
     * somebody. [MIN_SAMPLES] alone would let a habit be claimed off six lines of chat.
     */
    data class Signal(val text: String, val share: Double, val minSamples: Int)

    fun styleSignals(c: BehaviorCounters): List<Signal> {
        val n = c.samples
        if (n <= 0) return emptyList()
        fun share(count: Int) = count.toDouble() / n
        val out = mutableListOf<Signal>()
        fun add(text: String, count: Int, at: Double, floor: Int) {
            val ratio = share(count)
            if (n >= floor && ratio >= at) out += Signal(text, ratio, floor)
        }
        add("爱问问题", c.questionMarks, 0.30, 20)
        add("语气很冲", c.exclamations, 0.40, 20)
        add("爱用省略号", c.trailingDots, 0.25, 20)
        add("爱用波浪号", c.tildes, 0.25, 20)
        add("会打全大写", c.shouted, 0.20, 20)
        add("爱爆粗口", c.strongLanguage, 0.20, 30)
        add("回得很短", c.shortReplies, 0.50, 30)
        add("爱写长段", c.longMessages, 0.30, 20)
        return out
    }

    fun hourLabel(hour: Int): String = when (hour) {
        in 0..4 -> "凌晨"
        in 5..10 -> "上午"
        in 11..13 -> "中午"
        in 14..18 -> "下午"
        else -> "晚上"
    }

    /**
     * Co-presence score: how many hours both players spoke in.
     *
     * The basis for "have you actually played together" — an Xbox Live lesson: a complaint from
     * someone who never met you carries almost no weight. Taking `min` per hour means both had to
     * be engaged, which is closer to "played together" than "were online at the same moment",
     * and it needs no O(players²) sampling.
     */
    fun coScore(a: IntArray, b: IntArray): Int {
        val n = minOf(a.size, b.size)
        var sum = 0
        for (h in 0 until n) sum += minOf(a[h], b[h])
        return sum
    }

    /** The hour bucket [now] falls into, in the server's zone. */
    fun hourOf(now: ZonedDateTime): Int = now.withZoneSameInstant(now.zone).hour

}