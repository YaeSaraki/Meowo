package com.meowo.chat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reply tree `/meo replay` prints.
 *
 * Two things make this hard enough to pin. The history is **routinely incomplete** — a reply whose
 * parent aged out of the ring must still be shown, not dropped. And a player editing a prefilled
 * command can build a cycle, which must not hang the renderer.
 */
class ThreadTreeTest {

    private fun record(code: String, anchor: String? = null, at: Long = 0L, body: String = "m$code") =
        Record(
            code = code, channel = Channel.PRIVATE, author = "Alice", recipient = "Bob",
            body = body, anchor = anchor, atEpochMillis = at,
        )

    @Test fun `replies nest under what they answer`() {
        val nested = ThreadTree.nest(listOf(record("aaaa"), record("bbbb", anchor = "aaaa"), record("cccc", anchor = "bbbb")))
        assertEquals(listOf(0, 1, 2), nested.map { it.depth })
        assertEquals(listOf("aaaa", "bbbb", "cccc"), nested.map { it.record.code })
    }

    @Test fun `siblings share a level`() {
        val nested = ThreadTree.nest(listOf(
            record("aaaa"),
            record("bbbb", anchor = "aaaa", at = 1),
            record("cccc", anchor = "aaaa", at = 2),
        ))
        assertEquals(listOf(0, 1, 1), nested.map { it.depth })
    }

    /**
     * The case that matters in production: the ring and the 7-day store are both partial, so a reply
     * routinely arrives without its parent. Dropping it would lose the exact line the reader came for.
     */
    @Test fun `a reply whose parent is gone becomes its own root`() {
        val nested = ThreadTree.nest(listOf(record("bbbb", anchor = "aaaa")))
        assertEquals(1, nested.size)
        assertEquals(0, nested.single().depth)
        assertEquals("bbbb", nested.single().record.code)
    }

    @Test fun `a self-answering message does not nest under itself`() {
        val nested = ThreadTree.nest(listOf(record("aaaa", anchor = "aaaa")))
        assertEquals(1, nested.size)
        assertEquals(0, nested.single().depth)
    }

    @Test fun `a cycle does not hang and loses nothing`() {
        val nested = ThreadTree.nest(listOf(record("aaaa", anchor = "bbbb"), record("bbbb", anchor = "aaaa")))
        assertEquals(setOf("aaaa", "bbbb"), nested.map { it.record.code }.toSet())
    }

    @Test fun `every record appears exactly once`() {
        val records = listOf(
            record("aaaa"), record("bbbb", anchor = "aaaa"), record("cccc", anchor = "aaaa"),
            record("dddd"), record("eeee", anchor = "zzzz"), record("ffff", anchor = "dddd", at = 9),
        )
        val codes = ThreadTree.nest(records).map { it.record.code }
        assertEquals(records.size, codes.size)
        assertEquals(records.size, codes.toSet().size, "a record was printed twice: $codes")
    }

    @Test fun `the root comes before its replies even when the reply arrived first`() {
        val nested = ThreadTree.nest(listOf(record("bbbb", anchor = "aaaa", at = 0), record("aaaa", at = 50)))
        assertEquals(listOf("aaaa", "bbbb"), nested.map { it.record.code })
    }

    @Test fun `an empty history nests to nothing`() {
        assertEquals(emptyList<ThreadEntry>(), ThreadTree.nest(emptyList()))
    }

    @Test fun `nesting stops before it runs away`() {
        var deepest = "n0"
        val records = buildList {
            add(record(deepest))
            for (level in 1..40) {
                val next = "n$level"
                add(record(next, anchor = deepest))
                deepest = next
            }
        }
        assertTrue(ThreadTree.nest(records).maxOf { it.depth } <= 8, "the walk must be bounded")
    }

    @Test fun `a quote is one line and trimmed`() {
        assertEquals("你好", ThreadTree.quoteOf(record("aaaa", body = "  你好  ")))
        assertEquals("a b", ThreadTree.quoteOf(record("aaaa", body = "a\nb")))
        val long = ThreadTree.quoteOf(record("aaaa", body = "x".repeat(200)), limit = 10)
        assertEquals(10, long.length)
        assertTrue(long.endsWith("…"), "a trimmed quote says so: $long")
    }
}