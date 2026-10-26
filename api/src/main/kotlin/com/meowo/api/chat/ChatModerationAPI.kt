package com.meowo.api.chat

import java.util.UUID

/**
 * Read-only view of one message on its way through the chat pipeline.
 *
 * Only JDK types appear here: no Bukkit, no Adventure, no `Player` (红线 §4). The raw text is
 * **always the un-redacted original** — a moderator that only saw `***` would have no evidence to
 * judge on and no way to answer an appeal.
 */
data class ChatMessageView(
    val player: UUID,
    val channel: String,
    val rawText: String,
    val atEpochMillis: Long,
    val risk: Int,
)

/** What to do with a message. `REDACT` masks parts, `REWRITE` replaces the whole body. */
enum class ModerationAction { ALLOW, REDACT, BLOCK, REWRITE }

/**
 * A moderator's decision.
 *
 * [reason] is a free-form string rather than an enum on purpose: an external AI must be able to
 * introduce new reason codes without waiting for a plugin release, and the reason is what an
 * appeal is judged on.
 */
data class ModerationVerdict(
    val action: ModerationAction,
    val reason: String,
    val replacement: String? = null,
)

/**
 * A 风纪委员 AI implements this to come online.
 *
 * ### Latency contract
 *
 * [inspect] runs **on the chat path** and must be fast — target under 1ms. It must not block on
 * IO. An AI that needs a remote model should score out of band and push the result through
 * [ChatModerationAPI.setRisk], which the gate then reads in O(1).
 *
 * ### Failure contract
 *
 * An exception thrown from [inspect] is caught, logged, and treated according to the server's
 * `chat.moderation.fail-mode`; it never stops the other auditors and never crashes the server.
 * When the context is disabled, every callback stops being delivered (红线 §5).
 */
interface ChatModerator {
    fun inspect(message: ChatMessageView): ModerationVerdict

    /** Called after a message was published, with the *rendered* body. Advisory; must not block. */
    fun onPublished(message: ChatMessageView) {}

    /** A player left; drop any state held for them. */
    fun onQuit(player: UUID) {}
}

/**
 * Who receives direct messages addressed to a system speaker.
 *
 * This is how the plugin's own message speakers become addressable chat participants: a player runs
 * `/msg EconCat <text>` and whoever claimed the `economy` speaker receives it. An AI claiming a
 * speaker is thereby reachable by ordinary players, and its replies go back out through the
 * normal `Messages.say` path under the same name.
 */
interface SystemSpeakerSink {
    /**
     * @return true when the message was accepted; false to have it refused. A refusal is surfaced
     *   to the player as an explicit "nobody is answering" — never silently dropped.
     */
    fun onDirectMessage(sender: UUID, speakerId: String, text: String): Boolean

    /**
     * Delivers an answer in the same private-message shape the player's own line would have used.
     *
     * A player addressing a system speaker never sees their own message echoed back — the reply
     * *is* the receipt (see `ChatCommands`, "delivered quietly"). That only reads as a conversation
     * if the answer arrives as a `✉ from -> to ✔` line rather than as an ordinary chat line, so a
     * sink that answers asynchronously has to route through here.
     *
     * It is a method on this interface rather than a service an AI context looks up, because a sink
     * lives in a context that must not import the chat context (红线: no cross-context imports). The
     * default does nothing, which is right for a sink that answered synchronously and has nothing
     * to say later.
     *
     * @param senderName the player's name, so the line can be rendered from the chat side.
     */
    fun reply(sender: UUID, senderName: String, speakerId: String, text: String) {}
}

/**
 * A sink that answers **later** and therefore needs the chat context to render the answer.
 *
 * [SystemSpeakerSink.reply] alone is not enough on its own: the default does nothing, and the
 * implementation that knows how to draw a `✉ from -> to ✔` line lives in the chat context, which
 * this one must not import (红线: no cross-context imports). So the capability is *injected* at the
 * moment the speaker is claimed, over this seam, instead of the sink reaching for it.
 *
 * A sink that answers synchronously inside [SystemSpeakerSink.onDirectMessage] does not implement
 * this and does not need to.
 */
interface ReplyCapable {
    /** Called once, at claim time. Store it and invoke it when the answer is ready. */
    fun attachReply(send: (sender: UUID, senderName: String, speakerId: String, text: String) -> Unit)
}

/** Moderation state and the audit window. Obtained from Bukkit's ServicesManager. */
interface ChatModerationAPI {
    fun register(moderator: ChatModerator): AutoCloseable

    /** Claims a speaker id. Only one sink may hold a given id at a time. */
    fun claimSpeaker(speakerId: String, sink: SystemSpeakerSink): AutoCloseable

    /** Most recent messages first; a null player returns messages from everyone. */
    fun history(player: UUID?, limit: Int): List<ChatMessageView>

    fun risk(player: UUID): Int

    /** Fast path for an out-of-band AI: the gate reads this with one map lookup. */
    fun setRisk(player: UUID, risk: Int)

    /** Adds to the stored risk, decaying back down on its own. */
    fun bumpRisk(player: UUID, delta: Int)

    fun muteUntil(player: UUID, epochMillis: Long)
    fun isMuted(player: UUID): Boolean
}

/** Who may be addressed, and whether anybody is listening. */
data class ParticipantInfo(
    val handle: String,
    /** `PLAYER` or `SYSTEM`. */
    val kind: String,
    /** Set only for `SYSTEM`; matches the `speaker-<id>` language key. */
    val speakerId: String?,
    /** True when a [SystemSpeakerSink] currently holds this speaker. */
    val answerable: Boolean,
)

interface ChatDirectoryAPI {
    fun participants(prefix: String, limit: Int): List<ParticipantInfo>
    fun speakerIds(): Set<String>

    /**
     * Resolves a typed handle to a player's id, **including players who are offline**.
     *
     * [participants] answers "who can I talk to" and therefore only knows who is here now; the
     * tools a 风纪 cat runs need the opposite — "what has this player been doing", asked about
     * somebody who is not currently on the server. Without this a discipline tool could only ever
     * resolve a name to whoever happened to be online, and every lookup for an absent player came
     * back as "I don't know that person".
     *
     * Returns null rather than inventing an id: a tool asked about a name that belongs to nobody
     * must refuse, because the alternative is a score for the wrong player. Uses the same resolution
     * and the same system-speaker-wins precedence as `/msg`, so the two can never disagree about
     * who a name refers to.
     */
    fun uuidOf(handle: String): java.util.UUID?

    /**
     * When this player last logged in, or null when this server has not seen them join.
     *
     * Retention across contexts needs this and cannot compute it for itself: `chat/log.db` keeps a
     * last-login stamp per player, and **no other context can read that file** (红线: contexts never
     * share storage). Without this seam a second copy of "when did they last log in" would be kept
     * somewhere, and the two would eventually disagree — which is how a retention promise quietly
     * stops being one.
     *
     * Null means **unknown**, not absent. A caller must not delete on unknown: the player may simply
     * predate the log, and forgetting somebody because there was no evidence is the opposite of what
     * this method is for.
     */
    fun lastSeen(player: String): Long?

    /**
     * Deletes every recorded line belonging to [player], and reports how many went.
     *
     * The other half of a player's right to be forgotten, and it lives here because **the chat log is
     * the largest store of a player's own words on this server** — bigger than anything the agents
     * keep about them. A forget command that cleaned the profile and left the transcripts would be
     * the worst version of this feature: the visible part gone, the words still there.
     *
     * Returns the count so the caller can say what happened. A caller must not report success it did
     * not verify.
     */
    fun forgetPlayer(player: String): Int
}
