package com.meowo.chat

import com.meowo.chat.domain.Channel
import com.meowo.chat.domain.CodeBook
import com.meowo.chat.domain.Record
import com.meowo.chat.domain.ThreadLog
import com.meowo.chat.domain.ThreadTree
import com.meowo.chat.infrastructure.ChatLogStore
import com.meowo.platform.ChatLine
import com.meowo.platform.LineOrigin
import com.meowo.platform.LineStamper
import com.meowo.platform.PlatformHost
import com.meowo.platform.Timestamps
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `#k3f9` on every line, and the records behind it.
 *
 * ## Who allocates a code
 *
 * **The sender, once.** A private line is delivered to both parties, so a code minted at delivery
 * time would hand each side a different one for the same sentence — and clicking the receiver's code
 * would open a thread that does not contain what they were just reading. Every render site that
 * produces *one message* therefore mints *one code* and passes it down.
 *
 * ## What is kept
 *
 * Every line, in memory (bounded ring) and on disk (a player keeps theirs until they stop logging
 * in — `chat.log-days` is days of *absence*, not days of age). **The cat's own notices are
 * included**: "what did the server tell me at 21:24?" is a real question, and `/balance` output is
 * exactly what it will be asked about. A cat line anchors to nothing and gathers no replies, so it
 * costs one row and never appears in anybody's thread.
 *
 * ## Failure is invisible
 *
 * [com.meowo.platform.Messages] wraps the stamper, so a broken archive cannot stop a chat line. A
 * failed persistence write is logged and dropped: the in-memory log still has the record for the rest
 * of the session.
 */
class ChatJournal(
    private val host: PlatformHost,
    private val settings: ChatSettings,
    private val onLog: (String, Throwable) -> Unit,
) {

    private val log = ThreadLog(settings.logCapacity)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var store: ChatLogStore? = null

    /**
     * Last login per player, in memory.
     *
     * The table behind it is authoritative; this is a **cache** of it, loaded once at open and
     * refreshed by [noteLogin]. It exists so [lastSeen] can be synchronous: a retention decision is
     * made on a timer thread, and waiting for a JDBC round trip there would be a tick stall. The
     * cache is never *ahead* of the table by more than one flush, and being ahead of it is the safe
     * direction to be wrong in — it retains a moment longer rather than forgetting early.
     */
    private val seenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Which codes are taken.
     *
     * The whole code space, not just the lines still in memory: a code that collides with an older
     * row sends a reader to somebody else's message. See `CodeBook`.
     */
    private val codeBook = CodeBook()

    /** How many lines are held in memory for `/meo replay` without touching SQLite. */
    fun size(): Int = log.size()

    /** The most recent lines one player said or received, newest first. See [ThreadLog.byAuthor]. */
    fun recentBy(author: String, limit: Int = 20): List<Record> = log.byAuthor(author, limit)

    /** Codes currently in memory, so `/meo replay` can TAB-complete what it can actually find. */
    fun liveCodes(): Set<String> = log.liveCodes()

    /** Opens the durable half. Separate from construction so no context creates a file eagerly. */
    fun start(plugin: JavaPlugin) {
        if (store != null || closed.get()) return
        store = runCatching { ChatLogStore(plugin.dataFolder.toPath().resolve("chat/log.db"), settings.logDays) }
            .onFailure { error -> onLog("chat log unavailable; codes stay in memory only", error) }
            .getOrNull()
        // Read on the calling thread, once, at open: this is a start-up cost and every later read of
        // [lastSeen] comes from memory.
        runCatching { store?.allLastSeen() ?: emptyMap() }
            .onSuccess { seenAt.putAll(it) }
            .onFailure { error -> onLog("chat login history unavailable", error) }
        // The codes the log already holds, so a fresh code cannot collide with a row that is
        // already written. Read once, on the calling thread, like the login history above: it is a
        // start-up cost and nothing asks for it again.
        runCatching { store?.codes() ?: emptyList() }
            .onSuccess { codes -> codes.forEach { codeBook.reserve(it) } }
            .onFailure { error -> onLog("chat code history unavailable", error) }
    }

    /**
     * A player is here, which is what retention measures against.
     *
     * The clock on somebody's chat runs from their last login rather than from the age of the row —
     * see [ChatLogStore]. It is a login and not a message because a player who logs in every day and
     * says nothing is still somebody whose words we are keeping.
     *
     * Costs one map write and a queued task, so it is safe to call from a join handler on the main
     * thread. No store yet (persistence failed to open) simply means nothing to keep.
     */
    fun noteLogin(player: UUID) {
        val at = System.currentTimeMillis()
        // Memory first, for the same reason the store does: a saturated queue must not lose the
        // fact that a live player is live, because that fact decides what we keep about them.
        seenAt.merge(player.toString(), at) { _, existing -> maxOf(existing, at) }
        store?.noteSeen(player, at)
    }

    /**
     * When [player] last logged in, or null when this log has never seen them.
     *
     * Null is **unknown**, not absent, and callers must not delete on it: the player may predate the
     * log entirely, and forgetting somebody because there was no evidence is the opposite of what
     * this is for.
     */
    fun lastSeen(player: UUID): Long? = seenAt[player.toString()]

    /**
     * Deletes everything recorded for [player] on request, and reports how many rows went.
     *
     * The write is asynchronous, so the number is the one the store returns **after** it has run —
     * a command that says "deleted 12" while the queue dropped the work would be worse than saying
     * nothing. Waits on the agent's behalf, never on a tick: this is called from a command the
     * player is standing in front of, and a bounded JDBC round trip there is acceptable where a
     * tick-thread stall is not.
     */
    fun forgetPlayer(player: UUID): Int {
        val store = store ?: return 0
        return runCatching { store.forgetPlayer(player).get(5, java.util.concurrent.TimeUnit.SECONDS) }
            .onFailure { error -> onLog("chat forget failed for $player", error) }
            .getOrDefault(0)
    }

    /**
     * Publishes the stamper to [com.meowo.platform.Messages], so every line the plugin sends opens
     * with a code.
     *
     * Cleared on close: a disabled chat context must leave the rest of the plugin rendering exactly
     * as it did before this existed, which is the only safe failure mode for a change this wide.
     */
    fun install(): AutoCloseable {
        val stamper = LineStamper { viewer, origin, text -> stamp(viewer, origin, text, null, null, null) }
        host.messages.stamper = stamper
        return AutoCloseable { close() }
    }

    /**
     * Records one line and returns its `#k3f9` component.
     *
     * The hover says **who and when**, because that is what a reader hovering a code wants; the click
     * runs `/meo replay`, the same command a player can type by hand.
     */
    private fun stamp(
        viewer: CommandSender?,
        origin: LineOrigin,
        text: String,
        anchor: String?,
        authorId: UUID?,
        recipientId: UUID?,
    ): Component {
        val made = record(origin, text, anchor, authorId, recipientId)
        return ChatLine.code(made.code, hoverText(viewer, made))
    }

    /**
     * A fresh code plus the record behind it, ready to be rendered into a line.
     *
     * [anchor] is the code this message answers. Passing it is what makes `/meo replay` grow a thread
     * rather than echo a single line.
     *
     * [persist] decides whether the line goes into the **durable** log as well as the in-memory one.
     * It is false for lines nobody said: opening the mailbox re-stamps every message in it, and
     * that is a *reading*, not something that happened. Writing those rows turned "open your
     * mailbox" into "add a copy of every message to the chat archive" — so the archive filled with
     * duplicates of mail that was delivered once, and the retention budget was spent on copies.
     */
    fun record(
        origin: LineOrigin,
        body: String,
        anchor: String? = null,
        authorId: UUID? = null,
        recipientId: UUID? = null,
        atEpochMillis: Long = System.currentTimeMillis(),
        persist: Boolean = true,
    ): Record {
        val record = Record(
            code = codeBook.mint(),
            channel = when (origin.kind) {
                LineOrigin.Kind.PLAYER -> if (origin.target == null) Channel.GLOBAL else Channel.PRIVATE
                LineOrigin.Kind.CAT, LineOrigin.Kind.SYSTEM -> Channel.SYSTEM
            },
            // A cat is recorded under the name a player reads, not the internal id.
            author = if (origin.kind == LineOrigin.Kind.CAT) host.messages.speaker(null, origin.speaker) else origin.speaker,
            speakerId = origin.speaker.takeIf { origin.kind == LineOrigin.Kind.CAT },
            authorId = authorId,
            recipient = origin.target,
            recipientId = recipientId,
            body = body,
            anchor = anchor,
            atEpochMillis = atEpochMillis,
        )
        log.record(record)
        if (persist) {
            store?.insert(record)?.exceptionally { error ->
                onLog("chat log write failed for #${record.code}", error)
                null
            }
        }
        return record
    }

    /**
     * The code of whatever this message answers, shown in front of its words — empty when it is not a
     * reply.
     *
     * Rendered by [com.meowo.platform.ChatLine.private], which owns the spacing: whether there is a
     * reference is decided by whether the **string** is blank, never by asking a component whether it
     * happens to have children. A leaf component has none, and that check silently ate the separating
     * space on every reply.
     */
    fun refCode(record: Record): String = record.anchor.orEmpty()

    /**
     * What hovering that reference shows: **the whole chain**, oldest first, one line per hop.
     *
     * That is where a reply to a reply shows what it is actually answering. Without it a reader has to
     * click, find the parent, and wonder what *that* was answering — one hop at a time, through a
     * context that is already gone by the time they get there. The chain in one tooltip is the nesting,
     * made visible. Clicking the reference runs `/meo replay`, which climbs the same chain.
     */
    fun refHover(viewer: CommandSender?, record: Record): String {
        val anchor = record.anchor ?: return ""
        val chain = log.chain(anchor)
        if (chain.isEmpty()) return ""
        return chain.joinToString("\n") { "#${it.code}  ${it.participants()}  ${ThreadTree.quoteOf(it, 60)}" }
    }

    /**
     * What hovering this line's `#k3f9` says: who, exactly when, and **how much answers it**.
     *
     * The reply count is the reason to look: without it a code gives a reader nothing to click *towards*,
     * which is the same "it exists but nobody knows" trap as the reply button hidden on a name.
     */
    fun hoverText(viewer: CommandSender?, record: Record): String {
        val replies = log.childCount(record.code)
        val when_ = Timestamps.full(record.atEpochMillis)
        return if (replies == 0) host.messages.text(viewer, "code-hover", record.participants(), when_)
        else host.messages.text(
            viewer, "code-hover-replies", record.participants(), when_,
            host.messages.text(viewer, "code-replies", replies),
        )
    }

    fun thread(code: String, limit: Int = 32): CompletableFuture<List<Record>> {
        // `conversation`, not `thread`: clicking a reply's code must show the exchange that produced
        // it, and the parent is precisely the part the reader cannot see where they clicked.
        val inMemory = log.conversation(code, limit)
        if (inMemory.isNotEmpty()) return CompletableFuture.completedFuture(inMemory)
        val store = store ?: return CompletableFuture.completedFuture(emptyList())
        return store.thread(code, limit)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        host.messages.stamper = null
        runCatching { store?.close() }
        store = null
        seenAt.clear()
        log.clear()
    }
}