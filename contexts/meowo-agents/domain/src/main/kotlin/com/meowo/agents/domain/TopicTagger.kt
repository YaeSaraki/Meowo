package com.meowo.agents.domain

/**
 * Turns a conversation window into short topic labels, for [ImpressionPrompt.Request.topics].
 *
 * ## Why this is a keyword table and not another model call
 *
 * The slot exists and has always said *"短话题标签，绝不是他们的话"*. Two things follow from that
 * sentence, and both of them rule out asking a model:
 *
 * 1. **It costs nothing.** `ImpressionService.afterTurn` already spends a call on the impression. A
 *    classifier call would nearly double it, and it is paid out of `Lane.PROACTIVE` — the budget
 *    that exists for a cat *choosing* to speak. Buying a second round trip to sort words into
 *    buckets is not that.
 * 2. **It cannot hallucinate.** The one measured failure in this whole subsystem is a model turning
 *    statistics into a confident fiction — "他半夜不睡，去过玩家的农场三次". A summariser asked for
 *    topics is the same shape of request with the same shape of risk, and its output would be pasted
 *    into the prompt *below the line that says "只写你知道的事"*. The guard rail would be fed by the
 *    thing it is meant to stop.
 *
 * So this is a lookup, and the output can only ever be a label that is written in the source below.
 * That is the whole safety argument: a player's sentence cannot reach the prompt through here, not
 * because it was filtered, but because the function is not capable of returning it.
 *
 * ## Precision over recall, deliberately
 *
 * A label is a **fact about what was discussed**, fed into a prompt whose entire design is "write
 * nothing that did not happen". A missed topic costs the impression a little colour; a wrong topic
 * teaches a cat something false about a person, and that gets written down and read back by other
 * cats. Every entry below is therefore chosen to be hard to hit by accident — no bare "金" (金币 is
 * money, not mining), no English "how" (it is in "how are you"), no bare "家" (it is in 大家).
 *
 * Labels are Chinese because the prompt they feed is Chinese; only the **output** is localised, via
 * the instruction line. Chinese trigger words are added alongside the English ones so a server with
 * two languages gets the same coverage from the same pass.
 */
object TopicTagger {

    /** How many labels reach the prompt. A window is twenty lines; five is the useful part of it. */
    const val MAX_TOPICS = 5

    /** How much of the window to look at. The impression is about the whole relationship. */
    const val WINDOW = 20

    /**
     * Label → the words that mean it.
     *
     * Every trigger is at least two characters, or an English word unlikely to appear by accident.
     * Read the list as "what would make this label a lie if it fired" — if a trigger could plausibly
     * be about something else, it does not belong here.
     */
    private val TOPICS: Map<String, List<String>> = linkedMapOf(
        "挖矿" to listOf("矿", "钻石", "镐", "挖", "煤矿", "矿物", "mine", "mining", "diamond", "ore", "pickaxe", "coal", "iron"),
        "建造" to listOf("建筑", "建造", "房子", "城堡", "盖房", "红石中继", "build", "building", "house", "castle", "roof"),
        "农场" to listOf("农场", "种地", "种植", "耕地", "收成", "繁殖", "村民", "farm", "farming", "crop", "wheat", "villager"),
        "战斗" to listOf("战斗", "打架", "刷怪", "骷髅", "苦力怕", "凋灵", "血量", "武器", "攻击", "怪物", "fight", "combat", "skeleton", "creeper", "wither", "mob", "boss", "kill"),
        "交易" to listOf("交易", "买卖", "收购", "商店", "价格", "多少钱", "金币", "trade", "buy", "sell", "price", "shop", "market"),
        "传送" to listOf("传送", "坐标", "回家", "基地", "出生点", "tpa", "teleport", "spawn", "waypoint"),
        "探索" to listOf("末地", "下界", "地狱", "堡垒", "遗迹", "探索", "nether", "end", "stronghold", "ruins", "explore", "adventure"),
        "科技" to listOf("红石", "科技", "机器", "自动化", "活塞", "漏斗", "redstone", "piston", "hopper", "automation"),
        "食物" to listOf("食物", "面包", "牛排", "熟肉", "饿死", "food", "hungry", "bread", "steak", "eat"),
        "钓鱼" to listOf("钓鱼", "鱼竿", "钓到", "fishing", "angler"),
        "求助" to listOf("求助", "救命", "怎么办", "帮帮我", "help me", "please help", "stuck"),
        "音乐" to listOf("音乐", "唱片", "乐器", "旋律", "music", "song", "jukebox", "disc"),
    )

    /** Every label this tagger can ever produce. The output is a subset of this set, by construction. */
    val LABELS: Set<String> = TOPICS.keys

    /**
     * The labels a window is about, most-mentioned first.
     *
     * Returns an empty list for an empty window — the prompt's `topics.isNotEmpty()` guard then
     * simply omits the line, which is the right thing to say when nothing was recognised.
     */
    fun labels(window: List<Turn>, limit: Int = MAX_TOPICS): List<String> {
        if (window.isEmpty() || limit <= 0) return emptyList()
        // Both speakers: the prompt says "你们聊过的话题", and a cat's own answer is half of what a
        // conversation was about. Nothing leaks either way — the output is drawn from the table.
        val text = window.joinToString(" ") { it.text }.lowercase()
        if (text.isBlank()) return emptyList()

        val scored = TOPICS.map { (label, words) -> label to words.sumOf { countOf(text, it) } }
            .filter { it.second > 0 }
        if (scored.isEmpty()) return emptyList()
        // Ties break on the label, not on map order, so the same window always yields the same
        // prompt — an impression that changes for no reason reads as the model being unstable.
        return scored.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.first }
    }

    /** Overlapping occurrences counted, so "矿" in 煤矿 counts once and "挖" in 挖矿 counts once. */
    private fun countOf(text: String, word: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return count
            count++
            from = at + word.length
        }
    }
}
