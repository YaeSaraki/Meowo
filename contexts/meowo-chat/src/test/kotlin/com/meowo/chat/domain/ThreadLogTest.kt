package com.meowo.chat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The in-memory log behind `/meo replay`.
 *
 * The properties that matter are the awkward ones: a bounded log has to stay **correct** while it
 * wraps, and a thread walk must survive a reply whose parent has already been evicted.
 */
class ThreadLogTest {

    private fun record(code: String, anchor: String? = null, at: Long = 0L, body: String = code) =
        Record(
            code = code, channel = Channel.PRIVATE, author = "Alice", body = body,
            recipient = "Bob", anchor = anchor, atEpochMillis = at,
        )

    @Test fun `a thread is the root plus everything answering it`() {
        val log = ThreadLog(128)
        log.record(record("aaaa"))
        log.record(record("bbbb", anchor = "aaaa", at = 10))
        log.record(record("cccc", anchor = "aaaa", at = 20))
        val thread = log.thread("aaaa")
        assertEquals(listOf("aaaa", "bbbb", "cccc"), thread.map { it.code })
    }

    @Test fun `a thread is chronological regardless of arrival order`() {
        val log = ThreadLog(128)
        log.record(record("aaaa", at = 10))
        log.record(record("bbbb", anchor = "aaaa", at = 5))
        log.record(record("cccc", anchor = "aaaa", at = 20))
        assertEquals(listOf("bbbb", "aaaa", "cccc"), log.thread("aaaa").map { it.code })
    }

    /** A player can edit a prefilled command into a loop; the walk must cut it, not hang. */
    @Test fun `a cycle between two records does not hang the walk`() {
        val log = ThreadLog(128)
        log.record(record("aaaa", anchor = "bbbb"))
        log.record(record("bbbb", anchor = "aaaa"))
        assertEquals(2, log.thread("aaaa").size)
    }

    @Test fun `replies to replies join the same thread`() {
        val log = ThreadLog(128)
        log.record(record("aaaa"))
        log.record(record("bbbb", anchor = "aaaa"))
        log.record(record("cccc", anchor = "bbbb"))
        assertEquals(listOf("aaaa", "bbbb", "cccc"), log.thread("aaaa").map { it.code })
    }

    /**
     * Clicking a **reply's** code has to show the exchange that produced it. Starting at the clicked
     * record answers the wrong question, and the parent is exactly the part the reader cannot see.
     */
    @Test fun `a conversation climbs from a reply to its root`() {
        val log = ThreadLog(128)
        log.record(record("aaaa", at = 1))
        log.record(record("bbbb", anchor = "aaaa", at = 2))
        log.record(record("cccc", anchor = "bbbb", at = 3))
        assertEquals(listOf("aaaa", "bbbb", "cccc"), log.conversation("cccc").map { it.code })
        assertEquals(listOf("aaaa", "bbbb", "cccc"), log.conversation("bbbb").map { it.code })
    }

    @Test fun `a climb through a cycle ends somewhere instead of hanging`() {
        val log = ThreadLog(128)
        log.record(record("aaaa", anchor = "bbbb"))
        log.record(record("bbbb", anchor = "aaaa"))
        assertTrue(log.conversation("aaaa").isNotEmpty())
    }

    /**
     * The chain is what a reply shows when hovered, so a reply to a reply has to reveal what it is
     * *actually* answering — otherwise the reader clicks once and finds another question mark.
     */
    @Test fun `a chain walks from the root down to the record`() {
        val log = ThreadLog(128)
        log.record(record("k3f9", at = 1))
        log.record(record("8i5f", anchor = "k3f9", at = 2))
        log.record(record("bbbb", anchor = "8i5f", at = 3))
        assertEquals(listOf("k3f9", "8i5f", "bbbb"), log.chain("bbbb").map { it.code })
        assertEquals(listOf("k3f9", "8i5f"), log.chain("8i5f").map { it.code })
    }

    @Test fun `a chain through a cycle stops instead of hanging`() {
        val log = ThreadLog(128)
        log.record(record("aaaa", anchor = "bbbb"))
        log.record(record("bbbb", anchor = "aaaa"))
        assertTrue(log.chain("aaaa").isNotEmpty())
        assertEquals(2, log.chain("aaaa").size)
    }

    @Test fun `a chain whose parents are gone is its own`() {
        val log = ThreadLog(128)
        log.record(record("bbbb", anchor = "aaaa"))
        assertEquals(listOf("bbbb"), log.chain("bbbb").map { it.code })
    }

    @Test fun `an unknown code has no chain`() {
        assertEquals(emptyList<Record>(), ThreadLog(128).chain("zzzz"))
    }

    @Test fun `an unknown code has no conversation`() {
        assertEquals(emptyList<Record>(), ThreadLog(128).conversation("zzzz"))
    }

    @Test fun `an unknown code has no thread`() {
        assertEquals(emptyList<Record>(), ThreadLog(128).thread("zzzz"))
    }

    @Test fun `the thread is capped`() {
        val log = ThreadLog(256)
        log.record(record("aaaa"))
        repeat(50) { log.record(record("r%03d".format(it), anchor = "aaaa")) }
        assertEquals(11, log.thread("aaaa", limit = 11).size, "the limit is a promise, not a hint")
    }

    /**
     * The one that matters in production: the ring wraps constantly, and a thread whose parent has
     * been evicted must still resolve to what is left rather than to nothing.
     */
    @Test fun `a reply survives its root being evicted`() {
        val log = ThreadLog(64)
        log.record(record("aaaa"))
        val survivor = log.record(record("bbbb", anchor = "aaaa"))
        repeat(200) { log.record(record("f%03d".format(it))) }
        // The reply was long ago pushed out by the flood, so re-record it against the same anchor to
        // model what the index has to cope with once only one end of a thread survives.
        log.record(survivor)
        assertEquals(listOf("bbbb"), log.thread("bbbb").map { it.code })
        assertEquals(emptyList<Record>(), log.thread("aaaa"), "the evicted root is gone, and says so")
    }

    @Test fun `evicting a root stops counting it as a parent`() {
        val log = ThreadLog(64)
        log.record(record("aaaa"))
        log.record(record("bbbb", anchor = "aaaa"))
        repeat(200) { log.record(record("f%03d".format(it))) }
        // `cccc` anchored to the evicted `aaaa` must not resurrect the index entry for it.
        assertTrue(log.byCode("aaaa") == null)
        assertTrue(log.liveCodes().none { it == "aaaa" })
    }

    @Test fun `the ring never grows past its capacity`() {
        val log = ThreadLog(64)
        repeat(1000) { log.record(record("c%04d".format(it))) }
        assertEquals(64, log.size())
    }

    /**
     * The moderation view: "that person said something". It has to match **either side** of the line —
     * staff think in names, the ring stores `Alice → Bob`, and a report never says which it meant.
     */
    @Test fun `byAuthor matches either side of the line`() {
        val log = ThreadLog(128)
        log.record(record("aaaa", at = 1))                                   // Alice -> Bob
        log.record(Record("bbbb", Channel.PRIVATE, "Carol", body = "hi", recipient = "Alice", atEpochMillis = 2))
        log.record(record("cccc", at = 3))                                   // Alice -> Bob
        assertEquals(listOf("cccc", "bbbb", "aaaa"), log.byAuthor("Alice").map { it.code })
        // `bbbb` is Carol → Alice, so Bob is not in it at all.
        assertEquals(listOf("cccc", "aaaa"), log.byAuthor("bob").map { it.code }, "case-insensitive, matched as recipient")
        assertEquals(listOf("bbbb"), log.byAuthor("Carol").map { it.code })
        assertEquals(emptyList<Record>(), log.byAuthor("Nobody"))
    }

    @Test fun `byAuthor is newest first and capped`() {
        val log = ThreadLog(256)
        repeat(20) { log.record(record("c%03d".format(it), at = it.toLong())) }
        assertEquals(listOf("c019", "c018"), log.byAuthor("Alice", limit = 2).map { it.code })
    }

    @Test fun `clear forgets everything`() {
        val log = ThreadLog(64)
        log.record(record("aaaa"))
        log.clear()
        assertEquals(0, log.size())
        assertTrue(log.liveCodes().isEmpty())
        assertEquals(emptyList<Record>(), log.thread("aaaa"))
    }

    @Test fun `records report who and when without the caller formatting anything`() {
        val record = record("aaaa", at = 0L)
        assertEquals("Alice → Bob", record.participants())
        // The record carries the **moment**, not a formatted string: a date is the platform's
        // business, and a domain that formatted it here would be a second answer to "what time was
        // that" — one here and one wherever else a timestamp is shown.
        assertEquals(0L, record.atEpochMillis)
    }
}