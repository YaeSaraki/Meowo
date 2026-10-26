package com.meowo.chat.domain

/** One row of a reply tree: the record, and how far below the root it sits. */
data class ThreadEntry(val record: Record, val depth: Int)

/**
 * Turns a flat, time-ordered list of records into a **reply tree**.
 *
 * ## Why it exists
 *
 * `/meo replay #k3f9` reads completely differently as a tree than as a flat list. Flat, a reply looks
 * like an unrelated line that happens to be nearby; nested under `↳`, the shape of the conversation is
 * the first thing a reader sees — and "what did they reply to" stops being a question.
 *
 * ## How it copes with a partial history
 *
 * The list comes from a bounded ring and a 7-day store, so it is **routinely incomplete**: a reply can
 * arrive with its parent long gone, and a message that is itself an answer appears here as a root.
 * Rather than dropping those — which loses the very reply the reader came for — a record whose parent
 * is not in the list is promoted to a root of its own. Every line in the input appears exactly once.
 *
 * Cycles are possible: a player can edit a prefilled command into `#aaaa` answering `#bbbb` while
 * `#bbbb` answers `#aaaa`. The visited set cuts them instead of hanging.
 */
object ThreadTree {

    private const val MAX_DEPTH = 8

    /** Root first, then each reply indented below what it answers. */
    fun nest(records: List<Record>): List<ThreadEntry> {
        if (records.isEmpty()) return emptyList()
        val byCode = records.associateBy { it.code }
        val children = HashMap<String, MutableList<Record>>()
        val roots = ArrayList<Record>()

        for (record in records) {
            val parent = record.anchor?.takeIf { it != record.code && byCode.containsKey(it) }
            if (parent == null) roots += record else children.getOrPut(parent) { ArrayList() } += record
        }
        // Time order among siblings, so the tree reads the way the conversation happened.
        roots.sortBy { it.atEpochMillis }
        children.values.forEach { it.sortBy { record -> record.atEpochMillis } }

        val out = ArrayList<ThreadEntry>(records.size)
        val seen = HashSet<String>(records.size)
        for (root in roots) walk(root, 0, children, seen, out)
        // A cycle that swallowed itself leaves records unvisited; append them so none is ever lost.
        records.filter { it.code !in seen }.sortedBy { it.atEpochMillis }.forEach { out += ThreadEntry(it, 0) }
        return out
    }

    private fun walk(
        record: Record,
        depth: Int,
        children: Map<String, List<Record>>,
        seen: MutableSet<String>,
        out: MutableList<ThreadEntry>,
    ) {
        if (!seen.add(record.code)) return
        out += ThreadEntry(record, depth)
        if (depth >= MAX_DEPTH) return
        children[record.code].orEmpty().forEach { walk(it, depth + 1, children, seen, out) }
    }

    /** The label the renderer puts on a reply's quote: what was answered, trimmed to one line. */
    fun quoteOf(record: Record, limit: Int = 48): String {
        val flat = record.body.replace('\n', ' ').trim()
        return if (flat.length <= limit) flat else flat.take(limit - 1) + "…"
    }
}