package com.meowo.agents.domain

/**
 * The one prompt a cat uses to decide what it thinks of a player.
 *
 * ## Why this is a separate prompt and not a mode of the chat one
 *
 * An impression is written **without a player present**. The model is not answering anyone; it is
 * forming a private opinion that will later be read back into other prompts. Two consequences shape
 * everything here:
 *
 * 1. There is no question to anchor on, so the model reaches for the persona and the sample — and a
 *    live run showed it will happily produce a confident sentence about a player it has never met.
 *    The bar is therefore "did something actually happen", not "does it sound right".
 * 2. It is the only place evaluative language is *allowed* to be produced, and it is still only
 *    allowed to be **subjective**. `soft_hits` never reaches here; the wording rule is what keeps
 *    "他很吵" (a judgement that would propagate as fact) out of a row other cats read.
 *
 * ## The failure this is written against
 *
 * Tested against a live model: asked to summarise a player from nothing but their chat traits, it
 * produced "他半夜不睡，去过玩家的农场三次" — an invented night, an invented visit, and an invented
 * count, delivered in a cat's voice. Every one of those sentences would then be read by other cats
 * as something that happened. Hence the hard rule below, and hence [acceptImpression] refusing the
 * output rather than trusting it.
 */
object ImpressionPrompt {

    /** Long enough to say something, short enough that a row stays a row (see the footprint test). */
    const val MAX_IMPRESSION_CHARS = 60

    /** Below this many meetings a cat has nothing to base an opinion on. */
    const val MIN_MEETINGS = 3

    data class Request(
        val cat: CatProfile,
        val playerName: String,
        /** How many times this cat and this player have actually talked. */
        val meetings: Int,
        /** The player's descriptive traits, exactly as `player_traits.traits` holds them. */
        val traits: String,
        /** What the two of them actually talked about, as short topic labels. Never their words. */
        val topics: List<String> = emptyList(),
        val locale: String = "zh_CN",
    ) {
        init {
            // The slot's contract is that it carries labels, and `TopicTagger` is what makes that
            // true by only ever returning its own vocabulary. Checked here as well because a label
            // list is the one place free text could re-enter this prompt, and the prompt below is
            // written on the assumption that it cannot.
            require(topics.all { it in TopicTagger.LABELS }) {
                "topics must be labels from TopicTagger.LABELS, never text from a conversation: $topics"
            }
        }
    }

    /**
     * @return the system prompt for writing an impression, or null when there is not enough
     *   shared history for an opinion to mean anything. Refusing here is cheaper and more honest
     *   than storing a first-impression-of-nothing.
     */
    fun systemPrompt(request: Request): String? {
        if (request.meetings < MIN_MEETINGS) return null
        val language = if (request.locale.substringBefore('_').lowercase() == "en") "用英文写。" else "用中文写。"
        return buildString {
            appendLine("你在给玩家 ${request.playerName} 写一条**私人印象**，写给自己看的，不是给对方看的。")
            appendLine()
            appendLine("## 你是谁")
            appendLine(request.cat.persona)
            appendLine(request.cat.tone)
            if (request.cat.sample.isNotBlank()) {
                appendLine("语气示范：「${request.cat.sample.trim('「', '」')}」")
                appendLine("（只是语气示范，里面没有任何事实，不要照抄它的内容。）")
            }
            appendLine()
            appendLine("## 你知道的")
            appendLine("你和 ${request.playerName} 聊过 ${request.meetings} 次。")
            appendLine("从聊天记录里统计出的说话方式：${request.traits}")
            if (request.topics.isNotEmpty()) {
                appendLine("你们聊过的话题：${request.topics.joinToString("、")}")
                appendLine("（这些是从你们**长期**聊天的记录里统计出的**大类标签**，不是他们说过的话，")
                appendLine("  也不是某一次具体的事。别把它们写成「他昨天挖了矿」这种具体事件。）")
            }
            appendLine()
            appendLine("## 硬性规则")
            appendLine("1. **只写你知道的事。** 上面没写的，一律不许出现。")
            appendLine("   不许编造发生过的事件、去过的地方、说过的话、具体的次数。")
            appendLine("   你没记录的事，就不写；宁可短，也不要好看。")
            appendLine("2. 你**看不到**任何原始聊天内容，你只有上面那几行统计。")
            appendLine("   所以你不可能知道具体发生过什么——那就别写具体发生过什么。")
            appendLine("3. 写**感觉**，不写**判决**。可以说「说话很急」「不太爱搭话」，")
            appendLine("   不要说「脾气差」「有问题」「这人不可信」。")
            appendLine("4. 不许提任何名字、数字、坐标。别的猫会读到这条印象。")
            appendLine("5. $language")
            appendLine("6. **$MAX_IMPRESSION_CHARS 字以内，一到两句。**")
            appendLine()
            appendLine("## 输出")
            appendLine("只输出那句话本身。不要解释，不要引号，不要标题。")
        }
    }

    /**
     * The same bound as replies: the number stated in the prompt and the number enforced here are
     * one constant, for the reason [PromptBuilder] keeps.
     */
    fun userBlock(request: Request): String = buildString {
        appendLine("玩家：${request.playerName}")
        appendLine("互动次数：${request.meetings}")
        appendLine("说话方式统计：${request.traits}")
        if (request.topics.isNotEmpty()) appendLine("聊过：${request.topics.joinToString("、")}")
        appendLine()
        appendLine("写下你对这位玩家的印象。")
    }

    /**
 * A count written the way this prompt invents them.
 *
 * `isDigit()` was the only check, and it does **not** catch either of the two examples this class's
 * own KDoc names — "去过玩家的农场三次" and "半夜两点" are Chinese characters, not digits, so both
 * passed the guard that was written specifically to stop them. A digit-only rule gave the appearance
 * of protection and none of the effect.
 *
 * Deliberately narrow: a numeral **followed by a unit**, which is where an invented count lives.
 * Matching bare numerals instead would refuse perfectly good impressions — "不一定", "一直",
 * "第一" all contain one — and a guard that refuses most of what it is meant to keep ends up
 * switched off.
 */
private val CHINESE_COUNT = Regex("[零一二三四五六七八九十百千万两几半]+\\s*[次回个天次遍趟件年]")

/**
     * Whether a drafted impression may be stored.
     *
     * Two checks the model cannot be relied on to apply itself. A count is refused outright, because
     * every invented count this prompt has produced ("去过三次", "半夜两点", "见过 4 个玩家") was one a
     * reader would take as a fact; and an instruction echo means the model answered the prompt rather
     * than the task, which is not something to store about a person.
     */
    fun acceptImpression(text: String?, maxLength: Int = MAX_IMPRESSION_CHARS): String? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > maxLength) return null
        if (trimmed.any { it.isDigit() }) return null
        if (CHINESE_COUNT.containsMatchIn(trimmed)) return null
        if (trimmed.startsWith("玩家：") || trimmed.contains("## ") || trimmed.contains("硬性规则")) return null
        return trimmed
    }
}
