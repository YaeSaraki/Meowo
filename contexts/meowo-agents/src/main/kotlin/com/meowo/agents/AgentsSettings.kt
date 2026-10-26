package com.meowo.agents

/**
 * Tunables for the cats, read once in `start()` so a reload restarts the context and re-reads
 * them.
 *
 * @param baseUrl OpenAI-compatible endpoint. **Empty by default**: with no provider configured
 *   every cat replies with a short in-character apology, which is a usable server. The alternative
 *   — a context that starts and then hangs on every question — is not.
 * @param preflight adds one line to each system prompt. Off by default because it was measured to
 *   change nothing (see `PromptBuilder.Request.preflight`); a deployment on a model this project
 *   has not measured may want it.
 */
data class AgentsSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "MiniMax-M3",
    /**
     * Completion budget. Must be large enough for a reasoning model to finish thinking and still
     * emit its tool call — measured: 80 truncated *every* MiniMax-M3 response, and a truncated
     * response looks exactly like a model that declined to call a tool.
     *
     * Thinking and answer share this one allowance, so it also has to cover the answer: a reply
     * allowed to be 320 characters is several hundred tokens by itself.
     */
    val maxTokens: Int = 1024,
    val preflight: Boolean = false,
    val workers: Int = 2,
    /** How many player questions may wait for a busy worker. */
    val backlog: Int = 32,
    /** Whole-server call ceiling for a day, before the proactive share is carved off. */
    val dailyCap: Int = 500,
    /** Per-player call ceiling, so one talker cannot empty the server's allowance. */
    val perPlayerCap: Int = 20,
    /** Minimum gap between one player's questions to one cat. */
    val cooldownSeconds: Long = 10,
    /**
     * Whether cats may start a conversation. Off by default: a server that did not ask for
     * unsolicited chatter should not get any, and the whole feature is a `off`-by-default line in
     * the plan for the same reason.
     */
    val proactive: Boolean = false,
    /** Share of the daily cap reserved for real questions. Chatter can never eat it. */
    val proactiveShare: Double = 0.20,
    /**
     * How often the out-of-band discipline pass runs: prune, then recompute risk, then advance the
     * ladder. One timer for all three, so they cannot disagree about which evidence still exists.
     */
    val disciplineIntervalSeconds: Long = 300,
    /** Where the profile database lives, relative to the plugin data folder. */
    val storePath: String = "agents/profile.db",
    /**
     * How long a cat remembers what one player said to it.
     *
     * Same window as the chat journal on purpose: a cat's memory is a different slice of words the
     * chat log already keeps, not a second, longer archive of them.
     */
    val memoryDays: Int = 7,
    /**
     * Days without a login before a cat forgets what it thought of a player.
     *
     * Deliberately the same window the chat log keeps words for: an impression is a **compression**
     * of those words, and a compressed copy that outlives its source is how "15 days of absence and
     * we delete what you said" quietly stops being true. The clock is the player's last **login**,
     * not the impression's age — a player who logs in daily keeps what the cats thought of them.
     */
    val impressionDays: Int = 15,
)
