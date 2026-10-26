package com.meowo.chat

/**
 * Tunables for the chat pipeline. Read once in `start()`, so a reload restarts the context and the
 * rules are recompiled for everyone at the same moment.
 */
data class ChatSettings(
    val maxLength: Int = 256,
    val warnAt: Int = 40,
    val blockAt: Int = 80,
    /**
     * What happens to a message when an auditor throws: `true` lets it through, `false` drops it.
     *
     * **The default is `false` (fail-closed), and it was `true` until it was not.** A 风纪 AI that is
     * unreachable or throwing on every call must not be indistinguishable from a server with no
     * moderation at all: with fail-open, the operator's log says the feature is on while every
     * message walks straight through it. The cost of failing closed is that a broken auditor drops
     * the messages it inspects — a per-message cost, visible, and reversible in the config.
     */
    val failOpen: Boolean = false,
    val auditCapacity: Int = 512,
    val literalFilters: List<String> = emptyList(),
    val regexFilters: List<String> = emptyList(),
    val playerFirst: Boolean = false,
    /** Built-in responder so `/msg` answers before any AI is connected. */
    val helpdesk: Boolean = true,
    /** Private messages kept per player; the oldest are evicted beyond this. */
    val mailCapacity: Int = 32,
    /** Lines held in memory for `/meo replay` without touching SQLite. */
    val logCapacity: Int = 4096,
    /**
     * Days a player's chat stays on disk **after they stop logging in**.
     *
     * Measured from the last join, not from the age of a row: a regular who says nothing all week
     * keeps everything they said in March, and someone who vanished a month ago keeps nothing.
     * Retention is a promise, not a suggestion — see [ChatLogStore].
     */
    val logDays: Int = 15,
    /**
     * Operator-added emoji, merged **over** [EmojiParser.DEFAULTS].
     *
     * The built-in table is a plugin concern and stays in code; adding a server-specific face is an
     * operator's business, and making them edit a Kotlin file to say so is a support question waiting
     * to happen. Empty by default, so a server that adds nothing behaves exactly as before.
     */
    val extraEmoji: Map<String, String> = emptyMap(),
) {
    init {
        require(maxLength in 16..4096) { "chat.max-length must be 16..4096" }
        require(warnAt in 0..100) { "chat.warn-at must be 0..100" }
        require(blockAt in 1..100) { "chat.block-at must be 1..100" }
        require(warnAt < blockAt) { "chat.warn-at must be below chat.block-at" }
        require(auditCapacity in 16..65_536) { "chat.audit-capacity must be 16..65536" }
        require(mailCapacity in 1..1024) { "chat.mail-capacity must be 1..1024" }
        require(logCapacity in 64..1_048_576) { "chat.log-capacity must be 64..1048576" }
        require(logDays in 1..3650) { "chat.log-days must be 1..3650" }
    }
}
