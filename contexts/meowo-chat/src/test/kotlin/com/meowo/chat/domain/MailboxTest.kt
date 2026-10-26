package com.meowo.chat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The mailbox: what it keeps, how it is marked, and why it is bounded.
 *
 * Only **offline** mail is stored — an online recipient gets the line pushed into chat, so storing
 * it too would mean reading the same message twice.
 */
class MailboxTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private fun mail(to: UUID) = Mail(UUID.randomUUID(), alice, "Alice", "hi", 1_000)

    @Test fun `an offline sender is filed and the recipient has one unread`() {
        val box = Mailbox()
        val stored = box.store(alice, "Alice", bob, "hi", 1_000)
        assertTrue(stored != null)
        assertEquals(1, box.unreadCount(bob))
        assertEquals(0, box.unreadCount(alice), "the sender gets nothing of their own")
    }

    /**
     * The cooldown belongs to a **pair**, not to the box.
     *
     * It used to be one stamp per recipient, so a player could write to a dozen different offline
     * people in a row — one message each, every one of them past the grace period — and the spam
     * limit stopped being a limit. The document always said "the same pair"; now the code says it
     * too.
     */
    @Test fun `the cooldown is per pair, not per recipient`() {
        val carol = UUID.randomUUID()
        val box = Mailbox(cooldownMillis = 3_000)
        assertTrue(box.store(alice, "Alice", bob, "hi", 1_000) != null)
        assertNull(box.store(alice, "Alice", bob, "again", 2_000), "the same pair is cooling down")
        assertTrue(box.store(alice, "Alice", carol, "hi", 2_000) != null, "another pair is not")
    }

    /** The same pair may write again once the grace period is over. */
    @Test fun `the pair may write again after the grace period`() {
        val box = Mailbox(cooldownMillis = 3_000)
        assertTrue(box.store(alice, "Alice", bob, "first", 1_000) != null)
        assertNull(box.store(alice, "Alice", bob, "second", 2_000))
        assertTrue(box.store(alice, "Alice", bob, "third", 5_000) != null)
    }

    @Test fun `the newest mail is listed first`() {
        val box = Mailbox(cooldownMillis = 0)
        box.store(alice, "Alice", bob, "first", 1_000)
        box.store(alice, "Alice", bob, "second", 2_000)
        assertEquals(listOf("second", "first"), box.all(bob).map { it.text })
    }

    @Test fun `opening the mailbox marks what was shown as read`() {
        val box = Mailbox(cooldownMillis = 0)
        box.store(alice, "Alice", bob, "hi", 1_000)
        val shown = box.all(bob)
        assertEquals(1, box.unreadCount(bob))

        val changed = box.markRead(bob, shown.mapTo(mutableSetOf(), Mail::id))

        assertEquals(1, changed.size)
        assertEquals(0, box.unreadCount(bob), "reading must clear the unread badge")
        assertTrue(box.all(bob).single().read, "history is kept, only the mark changes")
    }

    @Test fun `marking read twice reports nothing the second time`() {
        val box = Mailbox(cooldownMillis = 0)
        box.store(alice, "Alice", bob, "hi", 1_000)
        val id = box.all(bob).single().id
        assertEquals(1, box.markRead(bob, setOf(id)).size)
        assertEquals(0, box.markRead(bob, setOf(id)).size, "already read is not a change")
    }

    @Test fun `answering a sender clears the unanswered mark on everything they left`() {
        val carol = UUID.randomUUID()
        val box = Mailbox(cooldownMillis = 0)
        box.store(alice, "Alice", bob, "one", 1_000)
        box.store(alice, "Alice", bob, "two", 2_000)
        box.store(carol, "Carol", bob, "unrelated", 3_000)

        val changed = box.markReplied(bob, alice)

        assertEquals(2, changed.size, "both of Alice's mails are answered by one reply")
        assertTrue(box.all(bob).filter { it.from == alice }.all { it.replied })
        assertFalse(box.all(bob).first { it.from == carol }.replied, "Carol's mail is untouched")
    }

    @Test fun `an oversized box evicts its own oldest entries instead of growing`() {
        val box = Mailbox(capacity = 4, cooldownMillis = 0)
        repeat(50) { box.store(alice, "Alice", bob, "m$it", it.toLong()) }
        assertEquals(4, box.all(bob).size, "an unbounded mailbox is a memory leak with extra steps")
        assertEquals(listOf("m49", "m48", "m47", "m46"), box.all(bob).map { it.text },
            "newest first, oldest evicted")
    }

    @Test fun `you cannot mail yourself, and a pair cannot spam`() {
        val box = Mailbox(cooldownMillis = 3_000)
        assertNull(box.store(alice, "Alice", alice, "me", 1_000))
        assertTrue(box.store(alice, "Alice", bob, "one", 1_000) != null, "the first mail always goes")
        assertNull(box.store(alice, "Alice", bob, "two", 1_500), "and the second is held back")
        assertTrue(box.store(alice, "Alice", bob, "three", 5_000) != null)
    }

    @Test fun `a relayed mail says so in its body`() {
        val box = Mailbox(cooldownMillis = 0)
        val stored = box.store(UUID.randomUUID(), "EconCat", bob, "带上话", 1_000, relayed = true)
        assertTrue(stored!!.text.contains("转告"), "the reader must know it is second-hand: ${stored.text}")
    }

    @Test fun `a box rehydrated from storage keeps its marks and honours the cap`() {
        val box = Mailbox(capacity = 2)
        val kept = listOf(
            Mail(UUID.randomUUID(), alice, "Alice", "oldest", 1_000, read = true, replied = true),
            Mail(UUID.randomUUID(), alice, "Alice", "middle", 2_000, read = false),
            Mail(UUID.randomUUID(), alice, "Alice", "newest", 3_000),
        )
        box.replaceAll(bob, kept)
        assertEquals(listOf("newest", "middle"), box.all(bob).map { it.text }, "oldest evicted by the cap")
        assertEquals(2, box.unreadCount(bob), "both kept mails are unread")
        assertTrue(box.all(bob).none { it.read && it.replied }, "the evicted reply mark went with it")
    }

    @Test fun `a nonsensical capacity is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { Mailbox(capacity = 0) }
    }

    @Test fun `forgetting a player drops their box so a reconnect starts clean`() {
        val box = Mailbox()
        box.store(alice, "Alice", bob, "hi", 1_000)
        box.forget(bob)
        assertEquals(0, box.unreadCount(bob))
        assertTrue(box.all(bob).isEmpty())
    }
}