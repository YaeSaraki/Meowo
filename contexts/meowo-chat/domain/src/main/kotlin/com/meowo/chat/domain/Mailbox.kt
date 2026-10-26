package com.meowo.chat.domain

import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One private message addressed to an offline player. */
data class Mail(
    val id: UUID,
    val from: UUID,
    val fromName: String,
    val text: String,
    val atEpochMillis: Long,
    /** Set when the recipient opened their mailbox. */
    val read: Boolean = false,
    /** Set when the recipient answered this sender. Keeps answered mail from looking urgent. */
    val replied: Boolean = false,
) {
    /** Unread is what the mailbox lists first. */
    val pending: Boolean get() = !read
    val handled: Boolean get() = read && replied
}

/** What happened to a message, so the sender can be told the truth rather than a generic "sent". */
sealed interface Delivery {
    /** Somebody is online and saw it in chat. */
    data class Pushed(val to: String) : Delivery

    /** Nobody was online; it is waiting in the mailbox. */
    data class Stored(val to: String, val unread: Int) : Delivery

    data class Refused(val reason: Reason) : Delivery

    enum class Reason {
        SELF, BLOCKED, UNKNOWN, RATE_LIMIT,

        /**
         * The message was only a `#k3f9` reference with no words.
         *
         * Clicking a message prefills the code so the reader can answer it; sending that unchanged is
         * a reference, not a message, and a delivered line with no words is worse than being told.
         */
        EMPTY,

        /**
         * The sender is muted.
         *
         * A mute that only one code path honours is worse than no mute at all: it looks enforced,
         * and the first player to notice the gap stops believing the rest of the moderation.
         */
        MUTED,
    }
}

/**
 * Player-to-player private messages for players who are **not** online.
 *
 * ## Why only offline mail
 *
 * An online recipient gets the line pushed straight into chat. Storing it as well would mean
 * reading the same message twice, so the mailbox holds only what would otherwise be lost.
 *
 * ## Why a private message is filtered
 *
 * `deliver` refuses a message the moderation gate already blocked. The gate runs first — a
 * mailbox is storage, not an exemption. See `ChatService.postToPlayer`.
 *
 * ## Bounded on purpose
 *
 * Each box is capped and evicts its own oldest entries. An unbounded mailbox is a memory leak with
 * extra steps, and a full disk is worse still.
 */
class Mailbox(
    private val capacity: Int = 32,
    /** Grace period before the same pair may send again, to stop mutual spam. */
    private val cooldownMillis: Long = 3_000L,
) {
    private data class Box(
        val queue: ArrayDeque<Mail> = ArrayDeque(),
        /**
         * When each **sender** last wrote to this box.
         *
         * Keyed by sender because the cooldown is about a *pair*: the doc has always said "the same
         * pair may not send again", and the code kept one stamp per box instead. With one stamp per
         * box a player could write to a dozen different offline people in a row — one message each,
         * every one of them past the grace period — and the spam limit stopped being a limit.
         */
        val lastSent: MutableMap<UUID, Long> = HashMap(),
    )

    private val boxes = ConcurrentHashMap<UUID, Box>()

    init {
        require(capacity in 1..1024) { "chat.mail-capacity must be 1..1024" }
    }

    /**
     * Files one message.
     *
     * @return null when the sender is the recipient or is cooling down; the caller refuses visibly.
     */
    fun store(from: UUID, fromName: String, to: UUID, text: String, now: Long, relayed: Boolean = false): Mail? {
        if (from == to) return null
        val box = boxes.computeIfAbsent(to) { Box() }
        val mail = synchronized(box) {
            // `lastSent > 0` matters: a fresh box must always accept its first message, whatever
            // the clock reads. Testing `now - lastSent` alone would refuse it on any small `now`.
            val last = box.lastSent[from] ?: 0L
            if (last > 0L && now - last < cooldownMillis) return null
            box.lastSent[from] = now
            if (box.queue.size >= capacity) box.queue.removeFirst()
            val body = if (relayed) "（转告）$text" else text
            Mail(UUID.randomUUID(), from, fromName, body, now).also { box.queue.addLast(it) }
        }
        return mail
    }

    /** Every message, newest first — the mailbox listing, read and replied alike. */
    fun all(player: UUID): List<Mail> {
        val box = boxes[player] ?: return emptyList()
        return synchronized(box) { box.queue.toList().reversed() }
    }

    fun unreadCount(player: UUID): Int =
        boxes[player]?.let { synchronized(it) { it.queue.count(Mail::pending) } } ?: 0

    /** Opening the mailbox marks what the player was shown as read. Returns only the changes. */
    fun markRead(player: UUID, ids: Set<UUID>): List<Mail> {
        val box = boxes[player] ?: return emptyList()
        return synchronized(box) {
            val changed = box.queue.filter { it.id in ids && !it.read }.map { it.copy(read = true) }
            if (changed.isNotEmpty()) {
                val marked = box.queue.map { if (it.id in ids) it.copy(read = true) else it }
                box.queue.clear(); box.queue.addAll(marked)
            }
            changed
        }
    }

    /** Answering a sender clears the unanswered mark on everything they left. Returns the changes. */
    fun markReplied(player: UUID, from: UUID): List<Mail> {
        val box = boxes[player] ?: return emptyList()
        return synchronized(box) {
            val changed = box.queue.filter { it.from == from && !it.replied }.map { it.copy(replied = true) }
            if (changed.isNotEmpty()) {
                val marked = box.queue.map { if (it.from == from) it.copy(replied = true) else it }
                box.queue.clear(); box.queue.addAll(marked)
            }
            changed
        }
    }

    /** Drops the box itself — the player's own copies. */
    fun forget(player: UUID) { boxes.remove(player) }

    /**
     * Drops every box that holds a message **from** [player].
     *
     * The other half of a forget: the copies they sent, in other people's mailboxes. The chat log
     * deletes both directions for the same reason — a person's words are their own wherever they
     * landed, and a player who asked to be forgotten must not find their sentences still sitting in
     * somebody else's inbox.
     */
    fun forgetSentBy(player: UUID) {
        boxes.forEach { (owner, box) ->
            synchronized(box) {
                val kept = box.queue.filter { it.from != player }
                if (kept.size == box.queue.size) return@synchronized
                box.queue.clear()
                box.queue.addAll(kept)
            }
            if (synchronized(box) { box.queue.isEmpty() }) boxes.remove(owner, box)
        }
    }

    fun clear() = boxes.clear()

    /** Hydrates a box from storage at start, oldest first, still honouring the cap. */
    fun replaceAll(player: UUID, mails: List<Mail>) {
        val kept = mails.sortedBy { it.atEpochMillis }.takeLast(capacity)
        boxes[player] = Box(ArrayDeque(kept))
    }
}