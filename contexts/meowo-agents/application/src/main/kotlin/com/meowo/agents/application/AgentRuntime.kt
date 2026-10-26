package com.meowo.agents.application

import com.meowo.agents.domain.CatProfile
import com.meowo.agents.domain.CatTurn
import com.meowo.agents.domain.ChatMessage
import com.meowo.agents.domain.Completion
import com.meowo.agents.domain.ConversationKey
import com.meowo.agents.domain.ConversationMemory
import com.meowo.agents.domain.SessionJournal
import com.meowo.agents.domain.Guard
import com.meowo.agents.domain.LlmClient
import com.meowo.agents.domain.PromptBuilder
import com.meowo.agents.domain.PresenceBoard
import com.meowo.agents.domain.Redaction
import com.meowo.agents.domain.Speaker
import com.meowo.agents.domain.ToolContext
import com.meowo.agents.domain.Turn
import java.util.UUID

/** One question, fully described, with no Bukkit and no clock in sight. */
data class Question(
    val asker: UUID,
    val cat: CatProfile,
    val text: String,
    val askerIsAdmin: Boolean = false,
    /**
     * The asker's client locale, resolved by the message layer. Without it the model answers an
     * English question in Chinese, which is the one thing [CatTurn.Reply] says it will not do.
     */
    val locale: String = "zh_CN",
    /** Resolves the disclosure verdict for another player; supplied by the caller's policy. */
    val disclose: (subject: UUID) -> Redaction.Verdict = { Redaction.Verdict.Allowed(Redaction.Scope.SELF) },
    /** Layer 1: the shared factual base, already rendered to prompt-safe words. */
    val facts: String? = null,
    /** Layer 2: this cat's own impression. */
    val ownImpression: String? = null,
    /** Layer 3: only what this cat explicitly asked other cats for. */
    val peerImpressions: List<PromptBuilder.PeerImpression> = emptyList(),
    /** Layer 0: who else has been on the public channel. Names and counts; see [PresenceBoard]. */
    val around: String = "",
    /**
     * May this question make **another** model call, and pay for it?
     *
     * The first call was already paid when the question was admitted, which is why this is asked
     * from round two onwards. Returning false stops the loop and the cat says whatever it already
     * learned — a caller that is out of allowance gets a partial answer and a logged count, not a
     * free extra round trip.
     *
     * On the question rather than on [AgentRuntime] because the answer depends on **who is asking**:
     * the budget is per player.
     */
    val mayCallAgain: () -> Boolean = { true },
)

/**
 * The one place a cat is allowed to think.
 *
 * ## Threads
 *
 * Everything here runs on the caller's thread, and the caller's thread is the agent pool's. No
 * Bukkit scheduler is touched, no database is opened, and no tick thread is ever blocked: a
 * conversation is a blocking job — a network round trip, possibly several — and putting one on a
 * tick thread would stall the whole server for as long as the provider takes. [submit] is the only
 * supported entry point, and it hands the work to [executor].
 *
 * ## The tool loop
 *
 * A model may call tools several times before answering. The loop is bounded by [maxToolRounds]: a
 * model that keeps asking is cut off and gets whatever it last learned, because an unbounded loop
 * against a paid endpoint is a way to spend money by accident.
 *
 * The loop is bounded by **money** as well as by rounds: [Question.mayCallAgain] is consulted
 * before every request after the first, so the budget counts requests rather than conversations.
 * It used to count conversations, which quietly let one question cost four times what the cap said.
 */
class AgentRuntime(
    private val client: LlmClient,
    private val tools: ToolRegistry,
    private val model: String,
    private val nameOf: (UUID) -> String,
    private val memory: SessionJournal = ConversationMemory(),
    private val maxToolRounds: Int = 4,
    /**
     * Turns a typed name into a player id, for the tools that take one.
     *
     * Defaults to "nobody goes by that name", which is the safe failure: a tool asked about a
     * player it cannot resolve refuses rather than reporting a score for whoever happens to match.
     * The wiring layer supplies the server's directory, which also resolves players who are offline
     * but still known — the people a 风纪 cat most often needs to look up.
     */
    private val lookupPlayer: (String) -> UUID? = { null },
) {
    init {
        require(maxToolRounds > 0) { "maxToolRounds must be positive" }
    }

    /**
     * Asks a cat something and returns what it decided to do.
     *
     * Blocking, and only ever called from an agent-pool thread.
     */
    fun ask(question: Question): CatTurn {
        val cat = question.cat
        val key = ConversationKey(question.asker, cat.id)
        val askerName = nameOf(question.asker)

        val prompt = PromptBuilder.build(
            PromptBuilder.Request(
                cat = cat,
                askerName = askerName,
                question = question.text,
                // Ten exchanges of context, not one and a half: the whole reason the window was lengthened.
                recentTurns = memory.recent(key, limit = 10),
                playerFacts = question.facts?.let { PromptBuilder.FactBlock.Granted(Redaction.Scope.SELF, it) },
                ownImpression = question.ownImpression
                    ?.let { PromptBuilder.FactBlock.Granted(Redaction.Scope.SELF, it) },
                peerImpressions = question.peerImpressions,
                around = question.around,
                locale = question.locale,
                askerIsAdmin = question.askerIsAdmin,
            ),
        )

        val messages = PromptBuilder.opening(prompt).toMutableList()
        val ctx = ToolContext(
            asker = question.asker,
            cat = cat,
            askerIsAdmin = question.askerIsAdmin,
            disclose = question.disclose,
            lookupPlayer = lookupPlayer,
        )
        val specs = tools.specsFor(cat)

        var lastSpoken: String? = null
        var lastRefusal: CatTurn.Refuse? = null
        var firstCall = true

        repeat(maxToolRounds) {
            // The caller paid for this question when it admitted it; every round after the first is
            // a request nobody has committed to yet, so it is paid for here.
            if (!firstCall && !question.mayCallAgain()) return settle(lastSpoken, lastRefusal)
            firstCall = false
            when (val completion = client.complete(model, messages, specs)) {
                is Completion.Text -> {
                    val reply = PromptBuilder.acceptReply(completion.content)
                        ?: return lastRefusal ?: CatTurn.Refuse(APOLOGISE)
                    memory.append(key, Turn(Speaker.PLAYER, question.text))
                    memory.append(key, Turn(Speaker.CAT, reply))
                    return CatTurn.Reply(reply)
                }
                is Completion.ToolUse -> {
                    messages += ChatMessage.Assistant(content = null, toolCalls = completion.calls)
                    for (call in completion.calls) {
                        val turn = tools.invoke(cat, call, ctx)
                        messages += ChatMessage.ToolResult(toolCallId = call.id, content = textOf(turn))
                        when (turn) {
                            is CatTurn.Reply -> lastSpoken = turn.text
                            is CatTurn.Refuse -> lastRefusal = turn
                            is CatTurn.Delegate -> Unit
                        }
                    }
                }
                // The provider failed: nothing to retry with, and no partial answer worth showing.
                Completion.Unusable -> return lastRefusal ?: CatTurn.Refuse(APOLOGISE)
                // The provider failed in a way that may pass. The next round tries again — and the
                // round above it has already asked `mayCallAgain`, so the retry is **paid for**
                // like any other request. The cap counts requests, not attempts-that-look-free.
                is Completion.Unavailable -> Unit
            }
        }

        // Out of rounds, or out of allowance. Say whatever the cat last learned rather than
        // looping for free.
        return settle(lastSpoken, lastRefusal)
    }

    /**
     * Ends a conversation that stopped early.
     *
     * A tool that answered is worth more than an apology — it is the answer to the question, already
     * paid for — but only if it survives [PromptBuilder.acceptReply]. Shared by both early exits so
     * "out of rounds" and "out of budget" cannot drift into behaving differently.
     */
    private fun settle(lastSpoken: String?, lastRefusal: CatTurn.Refuse?): CatTurn {
        val spoken = lastSpoken?.let { PromptBuilder.acceptReply(it) }
        return when {
            spoken != null -> CatTurn.Reply(spoken)
            else -> lastRefusal ?: CatTurn.Refuse(APOLOGISE)
        }
    }

    /**
     * Hands a blocking question to the agent pool, and reports whether it was admitted.
     *
     * Never call [ask] from a tick thread. The returned [Admitted] is what the caller acts on: a
     * rejection means the player must be told the agent is busy rather than left waiting for an
     * answer that is not coming.
     */
    fun submit(question: Question, scheduler: AgentScheduler, lane: Lane = Lane.QUESTION): Admitted =
        scheduler.submit(lane) { ask(question) }

    private fun textOf(turn: CatTurn): String = when (turn) {
        is CatTurn.Reply -> turn.text
        is CatTurn.Refuse -> turn.text
        is CatTurn.Delegate -> "${turn.cat}：${turn.reason}"
    }

    /**
     * Frees the **cache** on logout. Durable rows age out on their own retention — see
     * [SessionJournal.forgetPlayer], because deleting on logout is what made every session start over.
     */
    fun forget(player: UUID) = memory.forgetPlayer(player)

    fun clear() = memory.clear()

    /** What the prompt will see of this conversation, for diagnostics and for a cap assertion. */
    fun windowOf(player: UUID, cat: String, limit: Int = 3): List<Turn> =
        memory.recent(ConversationKey(player, cat), limit)

    companion object {
        const val APOLOGISE = "喵……刚才走神了，再问一次？"

        /** Delegation depth, mirroring the domain cap. */
        const val MAX_DELEGATIONS = Guard.MAX_DELEGATIONS
    }
}
