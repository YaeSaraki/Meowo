package com.meowo.chat.domain

import java.util.ArrayDeque

/**
 * The recent chat log: every line, addressable by its `#k3f9` code, with replies grouped by anchor.
 *
 * ## Bounded, loudly
 *
 * A cap is not a memory optimisation here, it is the promise. This holds **every** line the plugin
 * sends, so an unbounded log would grow forever and an operator reading it would have no idea how far
 * back they could go. When the ring wraps the oldest entries go, and any thread that pointed at one
 * simply becomes a thread that starts further along. A truncated answer beats an exception, and both
 * beat pretending the server remembers everything.
 *
 * ## Threads are read-time
 *
 * Nothing here maintains a reply tree. A thread is "the record with this code, plus everything that
 * answers it", walked breadth-first and capped. A reply whose parent has already been evicted still
 * shows up — it just heads its own thread, which is the honest result.
 */
class ThreadLog(private val capacity: Int = 4096) {

    private val entries = ArrayDeque<Record>()

    /** Code to record. Sized by the number of lines currently held, which is exactly the cap. */
    private val byCode = HashMap<String, Record>()

    /** Anchor to the codes that answer it, so a walk never scans the whole ring. */
    private val children = HashMap<String, MutableList<String>>()

    /** How deep a thread walk may nest before it stops following replies to replies. */
    private val maxDepth = 8

    init {
        require(capacity in 64..1_048_576) { "log capacity must be 64..1048576" }
    }

    /** Stores [record] and returns it. */
    @Synchronized
    fun record(record: Record): Record {
        entries.addLast(record)
        byCode[record.code] = record
        record.anchor?.let { children.getOrPut(it) { ArrayList(2) }.add(record.code) }
        while (entries.size > capacity) evictOldest()
        return record
    }

    private fun evictOldest() {
        val gone = entries.removeFirst() ?: return
        byCode.remove(gone.code)
        children.remove(gone.code)
        // Only drop the child list when nothing points at it any more; two records can share an anchor.
        children[gone.anchor]?.let { kids ->
            kids.remove(gone.code)
            if (kids.isEmpty()) children.remove(gone.anchor)
        }
    }

    @Synchronized fun byCode(code: String): Record? = byCode[code]

    /**
     * The chain that leads to [code], oldest first, **including the record itself**.
     *
     * This is what a reply shows when hovered. A reply to a reply to a reply would otherwise show
     * only its immediate parent, and the reader would have to click to find out what *that* was
     * answering — one hop at a time, through a context that is already gone by the time they get there.
     * The whole chain in one tooltip is the nesting, made visible without a click.
     *
     * Bounded by the visited set, so a cycle a player built by editing a prefilled command ends at some
     * root instead of hanging. A record whose parents have aged out is its own first entry.
     */
    @Synchronized
    fun chain(code: String): List<Record> {
        val start = byCode[code] ?: return emptyList()
        val out = ArrayList<Record>(4)
        val walked = HashSet<String>()
        var cursor: Record = start
        while (walked.add(cursor.code)) {
            out += cursor
            cursor = cursor.anchor?.let(byCode::get) ?: break
        }
        return out.reversed()
    }

    /**
     * The **whole conversation** around [code]: climb to the root of the chain, then take that root's
     * thread.
     *
     * Starting at [code] would answer the wrong question. A reader who clicks the code on a *reply*
     * wants the exchange that produced it, not just what came after — and the parent is exactly the
     * part they cannot see on screen. Climbing is bounded by the visited set, so a cycle a player
     * built by editing a prefilled command ends at some root instead of hanging.
     */
    @Synchronized
    fun conversation(code: String, limit: Int = 24): List<Record> {
        val walked = HashSet<String>()
        var root = byCode[code] ?: return emptyList()
        while (true) {
            val parent = root.anchor?.takeIf { walked.add(root.code) }?.let(byCode::get) ?: break
            root = parent
        }
        return thread(root.code, limit)
    }

    /** How many lines answer [code] directly. Zero for a leaf, which is the common case. */
    @Synchronized fun childCount(code: String): Int = children[code]?.size ?: 0

    /** Codes currently held, for collision avoidance when minting a new one. */
    @Synchronized fun liveCodes(): Set<String> = byCode.keys.toSet()

    /**
     * The record with [code] and everything answering it, oldest first.
     *
     * Breadth-first so the nearest replies come before replies-to-replies, bounded by [limit] and by
     * [maxDepth] nesting. A cycle (A answers B, B answers A — reachable by a player editing a prefilled
     * command) is cut by the visited set rather than looping.
     */
    @Synchronized
    fun thread(code: String, limit: Int = 24): List<Record> {
        val root = byCode[code] ?: return emptyList()
        val wanted = limit.coerceAtLeast(1)
        val seen = HashSet<String>()
        val out = ArrayList<Record>(wanted)
        var frontier = listOf(root)
        var depth = 0
        while (frontier.isNotEmpty() && out.size < wanted && depth < maxDepth) {
            val next = ArrayList<String>()
            for (record in frontier) {
                if (!seen.add(record.code)) continue
                out += record
                if (out.size >= wanted) break
                children[record.code]?.let(next::addAll)
            }
            frontier = next.mapNotNull(byCode::get)
            depth++
        }
        return out.sortedBy { it.atEpochMillis }
    }

    /**
     * The most recent records by one author, newest first, capped at [limit].
     *
     * This is the **moderation** view: a player reports "that person said something", staff need the
     * lines, and every line it prints carries a `#code` so the next question — "what else was said
     * about this?" — is one `/meo replay` away. Scanning backwards from the newest stops at the first
     * [limit] hits rather than copying the ring first.
     *
     * [author] matches case-insensitively and **either side**: staff think in names, the ring stores
     * `Alice → Bob`, and a report never says which side it meant.
     */
    @Synchronized
    fun byAuthor(author: String, limit: Int = 20): List<Record> {
        val wanted = author.lowercase()
        val out = ArrayList<Record>(limit.coerceAtLeast(1))
        val backwards = entries.descendingIterator()
        while (backwards.hasNext()) {
            val record = backwards.next()
            if (record.author.lowercase() == wanted ||
                record.recipient?.lowercase() == wanted
            ) {
                out += record
                if (out.size >= limit) break
            }
        }
        return out
    }

    @Synchronized fun size(): Int = entries.size

    @Synchronized fun all(): List<Record> = entries.toList()

    @Synchronized fun clear() {
        entries.clear(); byCode.clear(); children.clear()
    }
}