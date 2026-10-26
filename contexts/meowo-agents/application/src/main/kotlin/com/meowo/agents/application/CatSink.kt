package com.meowo.agents.application

import com.meowo.agents.domain.CatProfile
import com.meowo.agents.domain.CatRegistry
import com.meowo.agents.domain.CatTurn
import com.meowo.agents.domain.ConversationKey
import com.meowo.agents.domain.PresenceBoard
import com.meowo.agents.domain.Redaction
import com.meowo.agents.domain.TopicTagger
import com.meowo.api.chat.SystemSpeakerSink
import java.util.UUID

/** Why a direct message was turned away, for logs and for tests. */
enum class TurnedAway {
    /** Nobody is answering that speaker id. */
    NOT_A_CAT,

    /** The same player asked the same cat again too soon. */
    COOLDOWN,

    /** The daily allowance is spent. */
    QUOTA,

    /** Policy said no: the player is muted, opted out, or the asker may not have cats. */
    NOT_ALLOWED,

    /** The agent pool is saturated; dropped rather than queued forever. */
    BUSY,
}

/** What the sink decided about one direct message. */
sealed interface Admission {
    data class Accepted(val cat: CatProfile) : Admission

    data class Refused(val reason: TurnedAway) : Admission
}

/**
 * The agent's side of `/msg EconCat <text>`.
 *
 * ## The contract this exists to honour
 *
 * `ChatService.directMessage` calls [onDirectMessage] **synchronously on the caller's thread**, and
 * its boolean decides whether the message is journaled and whether the player is told anybody
 * answered. So this method must return without waiting for a model; everything expensive happens on
 * [executor] afterwards.
 *
 * `true` therefore means "somebody will answer", not "an answer exists". That is the honest reading
 * of the API, and the alternative — blocking the chat command on a network round trip — would stall
 * the caller for seconds.
 *
 * ## Why there is no `ChatModerator` here
 *
 * The agents deliberately do **not** register a `ChatModerator`. That interface runs on the hot path
 * for every message with a sub-millisecond budget and an explicit ban on blocking IO, which a model
 * call cannot honour; registering one would put an agent call on every single message. The
 * discipline cat works out of band through `setRisk` instead (docs/minimax/15-chat §8).
 *
 * ## Policy is injected, not assumed
 *
 * [policy] is the single place that decides whether this player may talk to this cat at all. It is
 * a constructor argument so the sink can be tested exhaustively without a server, and so the real
 * implementation can consult mute, opt-out and permission state in one place rather than in three.
 */
class CatSink(
    private val runtime: AgentRuntime,
    private val scheduler: AgentScheduler,
    private val budget: CallBudget = CallBudget(),
    private val policy: (UUID, CatProfile, String) -> Admission = { _, cat, _ -> Admission.Accepted(cat) },
    private val cooldownMillis: Long = 10_000L,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * What this player is known to be like, or null when they have not said enough.
     *
     * Supplied by the context because the tracker lives there; without it every question would go
     * out with an empty `[about …]` block and the cats would be as ignorant as they were before
     * anything was recorded.
     */
    private val traitsOf: (UUID) -> String? = { null },
    /**
     * Who else has been on the public channel lately, already reduced to one prompt-safe sentence.
     *
     * A [com.meowo.agents.domain.PresenceBoard], passed as its rendered summary rather than the board
     * itself: the sink has no business deciding who was in public chat, and a summary is the only
     * shape a prompt should ever see.
     */
    private val presence: PresenceBoard = PresenceBoard(),
    private val localeOf: (UUID) -> String = { "zh_CN" },
    private val nameOf: (UUID) -> String = { it.toString().take(8) },
    /**
     * Whether this player may be told more than everybody else.
     *
     * Left at "nobody" by default rather than reading a permission node here: the sink has no server.
     * It used to be wired as a constant `false` from the context, which meant the prompt's rule 6
     * told **every** admin they were not an admin, and the discipline tools refused them as an
     * ordinary player would be refused. One line of wiring in the context fixes both.
     */
    private val adminOf: (UUID) -> Boolean = { false },
    /**
     * May this cat tell [asker] about [subject]?
     *
     * Default: yourself yes, anyone else no. That is the honest answer for a sink that carries no
     * policy state, and it is the opposite of what was hard-coded before — every disclosure was
     * refused, including the ones about **the asker**, which silently disabled `economy.treasury`
     * for everybody on a server that had configured it.
     */
    private val disclose: (UUID, UUID) -> Redaction.Verdict = { asker, subject ->
        if (asker == subject) Redaction.Verdict.Allowed(Redaction.Scope.SELF)
        else Redaction.Verdict.Refused(Redaction.NOT_ALLOWED_REFUSAL)
    },
    /**
     * Layer 2 of the memory: what this cat thinks of this player, if it has met them enough.
     *
     * Null by default, which makes the prompt say "you have not talked much" — the honest state for
     * a sink with no memory. It used to be **always** null even in production, because nothing was
     * ever written: the prompt carried a permanent blank and `ImpressionPrompt` had no caller.
     */
    private val impressions: ImpressionService? = null,
    /** Injected at claim time by the chat context; see [com.meowo.api.chat.ReplyCapable]. */
    private var reply: ((UUID, String, String, String) -> Unit)? = null,
    /**
     * Reports a failure that happened on a worker thread.
     *
     * The work runs on the agent pool, so an exception thrown there has nowhere to go: the worker
     * survives it, the player gets nothing, and the log says nothing at all. That is the worst
     * shape a failure can have — a cat that "did not answer" and an operator with no evidence. So
     * the failure is reported rather than swallowed.
     *
     * Injected rather than logged here so this class stays free of Bukkit and of any logger.
     */
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) : SystemSpeakerSink, com.meowo.api.chat.ReplyCapable {

    override fun attachReply(send: (UUID, String, String, String) -> Unit) {
        reply = send
    }

    /** When each player last *asked* a cat something. */
    private val lastAsked = Cooldowns()

    /**
     * When a cat last spoke up **unprompted**.
     *
     * Separate from [lastAsked] on purpose. Sharing one stamp would mean a greeting the player never
     * asked for silently starts the question cooldown, so the cat could answer "在吗" and then refuse
     * the question that greeting invited. Two clocks, one cooldown length.
     */
    private val lastProactive = Cooldowns()

    /** How a finished turn is delivered. Injected so this class stays free of Bukkit and Messages. */
    var onReply: (CatTurn, ConversationKey, CatProfile) -> Unit = { _, _, _ -> }

    /** Why the last direct message was refused, or null when it was accepted. */
    @Volatile
    var lastRefusal: TurnedAway? = null
        private set

    /**
     * Returns immediately.
     *
     * The order of the checks is the cheap ones first: an unknown speaker and a rate-limited player
     * are answered without touching the quota, so a spammer cannot spend anybody else's budget.
     */
    override fun onDirectMessage(sender: UUID, speakerId: String, text: String): Boolean {
        lastRefusal = null
        val cat = CatRegistry.byId(speakerId)
        if (cat == null) return refuse(TurnedAway.NOT_A_CAT, null, sender)

        val key = ConversationKey(sender, cat.id)
        if (lastAsked.cooling(key, now(), cooldownMillis)) {
            return refuse(TurnedAway.COOLDOWN, cat, sender)
        }

        when (val admission = policy(sender, cat, text)) {
            is Admission.Refused -> return refuse(admission.reason, cat, sender)
            is Admission.Accepted -> Unit
        }

        // Budget is spent only if the work is genuinely going to run. A submission that the
        // scheduler drops must give the call back, or a burst of chatter on a busy server would
        // permanently consume allowance nobody used.
        if (!budget.acquire(Lane.QUESTION, sender)) return refuse(TurnedAway.QUOTA, cat, sender)

        // Stamped here, synchronously, and not in the worker: between this method returning and the
        // worker starting there is a window, and two questions landing in it would both pass the
        // cooldown check. That turns the rate limit into decoration.
        val stamp = now()
        lastAsked.stamp(key, stamp)

        val admitted = scheduler.submit(Lane.QUESTION) {
            // Reported, not swallowed: an exception on the worker thread would otherwise leave the
            // player with nothing and the operator with nothing either.
            try {
                answer(key, cat, text)
            } catch (error: Exception) {
                // The player asked something and is waiting for a line. Saying nothing would leave
                // them staring at a chat that never answers, so the cat apologises — and the
                // failure is reported, because an exception nobody hears about is an exception
                // nobody can fix.
                onFailure("agent question failed for $key", error)
                onReply(CatTurn.Refuse(AgentRuntime.APOLOGISE), key, cat)
            }
        }
        if (admitted is Admitted.Rejected) {
            budget.refund(Lane.QUESTION, sender)
            // **Only the stamp this call wrote.** A plain `remove(key)` would also erase a *later*
            // question's stamp if the player got another one in between — which is exactly what
            // happens when the queue is busy: the second question is stamped, the first one's
            // submission is refused, and the second question's cooldown disappears with it.
            lastAsked.release(key, stamp)
            return refuse(TurnedAway.BUSY, cat, sender)
        }
        return true
    }

    /**
     * The agent's own opening line. Unlike a question, it is never queued: when the server is busy
     * a greeting is the first thing that should go, and it says so out loud rather than pretending.
     *
     * ## Why the cooldown lives here rather than in the caller
     *
     * This is the only door to the proactive lane, so this is where "not too soon" belongs. Left to
     * the caller it becomes the same shape as the bug this feature was designed around: every new
     * trigger remembers the gate, until one does not. The stamp is taken **synchronously and before**
     * the submit, for the same reason [onDirectMessage] does it — the window between returning and
     * the worker starting is real, and a greeting a player did not ask for is exactly the kind of
     * thing that must not be sent twice.
     *
     * The budget check stays first: an empty allowance is a cheaper refusal than a cooldown, and
     * neither spends anything.
     */
    fun offerProactive(player: UUID, cat: CatProfile, text: String): Admitted {
        if (!budget.hasRoom(Lane.PROACTIVE)) return Admitted.Rejected(Busy.BUDGET)
        val key = ConversationKey(player, cat.id)
        if (lastProactive.cooling(key, now(), cooldownMillis)) {
            return Admitted.Rejected(Busy.SATURATED)
        }
        if (!budget.acquire(Lane.PROACTIVE, player)) return Admitted.Rejected(Busy.BUDGET)
        val stamp = now()
        lastProactive.stamp(key, stamp)
        val admitted = scheduler.submit(Lane.PROACTIVE) {
            try {
                val turn = runtime.ask(
                    Question(
                        asker = player, cat = cat, text = text,
                        around = presence.summary(),
                        askerIsAdmin = adminOf(player),
                        disclose = { subject -> disclose(player, subject) },
                        // Same rule as a question: this lane's budget is spent per request too, and
                        // never from the questions' share.
                        mayCallAgain = { budget.acquire(Lane.PROACTIVE, player) },
                    ),
                )
                onReply(turn, key, cat)
            } catch (error: Exception) {
                onFailure("agent greeting failed for $key", error)
                onReply(CatTurn.Refuse(AgentRuntime.APOLOGISE), key, cat)
            }
        }
        if (admitted is Admitted.Rejected) {
            budget.refund(Lane.PROACTIVE, player)
            // The stamp this call wrote, not whatever is there now — see the question path.
            lastProactive.release(key, stamp)
        }
        return admitted
    }

    private fun answer(key: ConversationKey, cat: CatProfile, text: String) {
        val turn = runtime.ask(
            Question(
                asker = key.player,
                cat = cat,
                text = text,
                facts = traitsOf(key.player),
                ownImpression = impressions?.impressionOf(key.player, cat.id),
                locale = localeOf(key.player),
                around = presence.summary(),
                askerIsAdmin = adminOf(key.player),
                // The sink holds no policy state of its own; this is the server's policy, asked
                // about whoever the question turns out to be about.
                disclose = { subject -> disclose(key.player, subject) },
                // Every round after the first is a request nobody has committed to yet, so it is
                // paid for where it is made. Without this a question could cost four times what
                // the daily cap says a question costs.
                mayCallAgain = { budget.acquire(Lane.QUESTION, key.player) },
            ),
        )
        onReply(turn, key, cat)
        // After the player already has their answer: the impression is the cat's own housekeeping
        // and may cost a second round trip, which must not sit between a question and its reply.
        // Still on this pool thread — a question path that waited for it would be a tick stall.
        //
        // The topics come out of the window the runtime already holds, read here rather than inside
        // the service: [TopicTagger] is a pure function over a list, and wiring the journal into
        // the impression layer to call it would make one about memory into a second reader of it.
        impressions?.afterTurn(
            cat, key.player,
            TopicTagger.labels(runtime.windowOf(key.player, cat.id, TopicTagger.WINDOW)),
        )
    }

    /**
     * Starts a new budget day, zeroing the counters if it is genuinely a new one.
     *
     * @return whether the counters were actually reset, so a caller can log the rollover rather
     *   than repeating the same message on every tick of the timer that drives this.
     */
    fun advanceBudgetDay(day: Long): Boolean = budget.advanceTo(day)

    /**
     * Turns a refusal into something the player can read, in the cat's own voice.
     *
     * The alternative was returning `false`, which the chat context reports as
     * `msg-refused-blocked` — "该内容被审核拦截". Nothing was moderated: the cat was cooling down,
     * out of budget, or simply unable to answer, and telling a player they had been censored for
     * asking a question is both false and the fastest way to lose their trust. So the sink
     * **explains** and returns `true`, which also gets the exchange into the journal and onto the
     * same `✉` line the answer would have used.
     */
    private fun refuse(reason: TurnedAway, cat: CatProfile?, player: UUID): Boolean {
        lastRefusal = reason
        // Nobody at all is answering this speaker — the chat context's "现在没人应答" is the
        // truthful line, and returning false is what selects it.
        if (reason == TurnedAway.NOT_A_CAT || cat == null) return false
        val line = declineLine(reason, cat)
        // Best effort: if the chat context has gone, the player simply gets nothing, which is still
        // better than being told they were censored.
        runCatching { reply?.invoke(player, nameOf(player), cat?.speakerId ?: "system", line) }
        return true
    }

    /** Hands a finished line to the chat context's private-message rendering. */
    fun deliver(player: UUID, playerName: String, speakerId: String, text: String) {
        reply?.invoke(player, playerName, speakerId, text)
    }

    private fun declineLine(reason: TurnedAway, cat: CatProfile?): String {
        val deflect = cat?.deflect?.takeIf { it.isNotBlank() }
        return when (reason) {
            TurnedAway.NOT_A_CAT -> "没人应答，等一下再试喵。"
            TurnedAway.COOLDOWN -> deflect?.let { "我歇会儿。$it" } ?: "我歇会儿，过会儿再问。"
            TurnedAway.QUOTA -> "今天问得有点多，我这边先停一停。"
            TurnedAway.NOT_ALLOWED -> "我现在不方便说话。"
            TurnedAway.BUSY -> "我这边排着队，过会儿再说。"
        }
    }

    /**
     * Drops everything remembered about a player.
     *
     * ## Both clocks, and not the budget
     *
     * The proactive clock used to survive this call, so a player who logged out and back in was
     * still inside the greeting cooldown — the cat refused to greet them because of a stamp from
     * before the logout. Both clocks are about *this session*, so both go.
     *
     * The **budget** is deliberately not touched: the per-player allowance belongs to the day, not
     * to the session, and handing it back on logout would make the cap reset on every reconnect.
     * See `CallBudget`.
     */
    fun forget(player: UUID) {
        lastAsked.releasePlayer(player)
        lastProactive.releasePlayer(player)
        // Meetings and cached impressions are about *this* player, and nothing else should keep
        // them once they are gone.
        impressions?.forget(player)
    }

    fun clear() {
        lastAsked.clear()
        lastProactive.clear()
        impressions?.clear()
    }

    /** True when this cat has answered [player] at least once since the last clear. */
    fun hasAnswered(player: UUID, cat: String): Boolean = lastAsked.contains(ConversationKey(player, cat))
}
