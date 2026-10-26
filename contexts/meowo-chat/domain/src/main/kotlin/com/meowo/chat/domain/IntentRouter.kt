package com.meowo.chat.domain

import java.util.Locale

/** What a private message to a system speaker is asking for. */
enum class Intent { GREETING, THANKS, HELP, BALANCE, CAPABILITIES, FALLBACK }

/**
 * Works out what a `/msg <cat> …` is asking, so the reply can be routed instead of every message
 * getting the same "I did not understand".
 *
 * Pure and bilingual on purpose: the tables are plain lowercase substrings, which covers Chinese
 * (no word boundaries) and English alike without a tokenizer, and the whole thing is unit tested
 * without a server.
 */
object IntentRouter {

    private val GREETING = listOf("你好", "您好", "在吗", "在不在", "hi", "hello", "hey", "morning", "晚上好", "早上好")
    private val THANKS = listOf("谢谢", "多谢", "感谢", "thx", "thanks", "thank you", "ta")
    private val HELP = listOf("帮助", "怎么用", "能做什么", "会什么", "help", "?", "？", "命令")
    private val BALANCE = listOf("余额", "多少钱", "还有多少", "balance", "money", "多少ฅ")
    private val CAPABILITIES = listOf("你是谁", "你叫什么", "你会什么", "who are you", "what can you do")

    fun detect(raw: String): Intent {
        val text = raw.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return Intent.FALLBACK
        // Order matters: "balance" is the most specific and the most likely to be asked.
        return when {
            matches(text, BALANCE) -> Intent.BALANCE
            matches(text, GREETING) -> Intent.GREETING
            matches(text, THANKS) -> Intent.THANKS
            matches(text, CAPABILITIES) -> Intent.CAPABILITIES
            matches(text, HELP) -> Intent.HELP
            else -> Intent.FALLBACK
        }
    }

    private fun matches(text: String, needles: List<String>) = needles.any { text.contains(it) }

    /**
     * Which cat should answer an intent. `asking` is the cat the player addressed; returning a
     * different one is how "wrong cat" is handled — asking `WarpCat` for a balance gets a pointer
     * to `EconCat` instead of a shrug.
     */
    fun route(intent: Intent, asking: String): String = when (intent) {
        Intent.BALANCE -> "economy"
        Intent.HELP, Intent.CAPABILITIES -> "system"
        else -> asking
    }
}
