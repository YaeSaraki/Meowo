package com.meowo.chat.infrastructure

import com.meowo.chat.domain.Channel
import com.meowo.chat.domain.Record
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Retention, which is the only part of this class with a policy in it.
 *
 * There was no test here at all before, which is the wrong way round: the store holds every line a
 * player has ever written, and the question "when does this go away" deserves more than a KDoc.
 *
 * Retention runs on open and every 256 writes, so the tests drive it the way production does — by
 * reopening the file — rather than by reaching into a private method.
 */
class ChatLogStoreTest {

    @TempDir lateinit var dir: Path

    private val db: Path get() = dir.resolve("chat/log.db")
    private var retention = 15
    private lateinit var store: ChatLogStore

    private val alice = UUID.fromString("00000000-0000-4000-8000-00000000aaaa")
    private val bob = UUID.fromString("00000000-0000-4000-8000-00000000bbbb")
    private val ghost = UUID.fromString("00000000-0000-4000-8000-00000000cccc")

    @BeforeEach fun open() { store = ChatLogStore(db, retentionDays = retention) }

    @AfterEach fun close() { store.close() }

    /** The real trigger: retention runs when the file is opened. */
    private fun pruneNow() {
        store.close()
        store = ChatLogStore(db, retentionDays = retention)
    }

    private fun daysAgo(days: Long): Long = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)

    private fun record(
        code: String,
        authorId: UUID? = null,
        recipientId: UUID? = null,
        anchor: String? = null,
        at: Long = System.currentTimeMillis(),
        body: String = "说了点什么",
    ) = Record(
        code = code,
        channel = if (recipientId == null) Channel.GLOBAL else Channel.PRIVATE,
        author = "Alice",
        authorId = authorId,
        recipient = recipientId?.let { "Bob" },
        recipientId = recipientId,
        body = body,
        anchor = anchor,
        atEpochMillis = at,
    )

    private fun query(sql: String, vararg args: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
            c.prepareStatement(sql).use { p ->
                args.forEachIndexed { i, a -> p.setString(i + 1, a) }
                p.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    private fun codes(): Set<String> = query("SELECT code FROM chat_log").toSet()

    private fun seenAt(player: UUID): Long? =
        query("SELECT seen_at FROM chat_active WHERE uuid = ?", player.toString())
            .firstOrNull()?.toLong()

    // ── the clock runs from the login, not from the row ───────────────────────────────────────

    /**
     * The whole point of the change, and the test that fails if it is quietly reverted.
     *
     * Under the old rule this row died on the spot — it is **300 days old**. It stays because the
     * player logged in yesterday, which is the promise the retention rule now makes.
     */
    @Test fun `a row survives as long as its player keeps logging in`() {
        store.insert(record("old1", authorId = alice, at = daysAgo(300))).join()
        store.noteSeen(alice, at = daysAgo(1)).join()
        pruneNow()
        assertTrue("old1" in codes(), "a regular's March messages must outlive their date: ${codes()}")
    }

    @Test fun `a player who stops logging in loses their chat`() {
        store.insert(record("g001", authorId = ghost, at = daysAgo(1))).join()
        store.noteSeen(ghost, at = daysAgo(20)).join()
        pruneNow()
        assertFalse("g001" in codes(), "a month of absence is the deletion trigger: ${codes()}")
    }

    /** Whoever sent it, Bob's copy of Alice's words is still Bob's data on disk. */
    @Test fun `a line goes when the recipient has gone, even though the sender is still here`() {
        store.insert(record("p001", authorId = alice, recipientId = bob, at = daysAgo(1))).join()
        store.noteSeen(alice, at = daysAgo(1)).join()
        store.noteSeen(bob, at = daysAgo(20)).join()
        pruneNow()
        assertFalse("p001" in codes(), "${codes()}")
    }

    /**
     * The mailbox re-stamps a stored message under the reader's own id.
     *
     * Nothing on that row points back at the sender, so a rule that only looked at `author_id`
     * would keep the reader's mailbox forever — and it is precisely the copy he never knew was
     * stored.
     */
    @Test fun `a line with no author still belongs to the player it was shown to`() {
        store.insert(record("m001", authorId = null, recipientId = bob, at = daysAgo(1))).join()
        store.noteSeen(bob, at = daysAgo(20)).join()
        pruneNow()
        assertFalse("m001" in codes(), "${codes()}")
    }

    /**
     * Without this carve-out the ownerless rows would be the only thing on disk that never goes.
     *
     * A `/balance` echo and a server notice belong to no player, so nobody's login keeps them —
     * they fall back to the plain age rule.
     */
    @Test fun `a line belonging to nobody still ages out`() {
        store.insert(record("sys1", authorId = null, recipientId = null, at = daysAgo(30))).join()
        pruneNow()
        assertFalse("sys1" in codes(), "${codes()}")
    }

    @Test fun `an ownerless line that is recent stays`() {
        store.insert(record("sys2", authorId = null, recipientId = null, at = daysAgo(1))).join()
        pruneNow()
        assertTrue("sys2" in codes(), "${codes()}")
    }

    /** The window is the operator's, not a constant buried in the delete. */
    @Test fun `the window is whatever the operator set`() {
        val threeDays = UUID.fromString("00000000-0000-4000-8000-00000000dddd")
        store.insert(record("t001", authorId = threeDays, at = daysAgo(1))).join()
        store.noteSeen(threeDays, at = daysAgo(2)).join()
        retention = 1
        pruneNow()
        assertFalse("t001" in codes(), "retention=1 must forget someone gone for two days: ${codes()}")
    }

    // ── upgrading a database that has no login history ────────────────────────────────────────

    /**
     * A server upgrading from the age rule already has years of rows and no login table.
     *
     * Treating "no login record" as "never logged in" would wipe the whole archive on the first
     * prune, so every known player is dated from their own last message instead. Two days old
     * survives a fifteen-day window; forty days does not.
     */
    @Test fun `rows that predate the login table are dated by their own last message`() {
        store.insert(record("recent", authorId = alice, at = daysAgo(2))).join()
        store.insert(record("ancient", authorId = bob, at = daysAgo(40))).join()
        pruneNow()
        val left = codes()
        assertTrue("recent" in left, "a two-day-old row must survive an upgrade: $left")
        assertFalse("ancient" in left, "a forty-day-old row must not: $left")
    }

    /** The backfill fills gaps and never pulls a clock backwards. */
    @Test fun `backfill never moves a real login into the past`() {
        store.noteSeen(alice, at = daysAgo(1)).join()
        store.insert(record("aaa1", authorId = alice, at = daysAgo(400))).join()
        pruneNow()
        assertTrue("aaa1" in codes(), "an older row must not overwrite yesterday's login: ${codes()}")
    }

    /**
     * A clock that jumped, or a task that ran out of order, must not make somebody look absent.
     *
     * The stamp that goes in is the latest one seen, not the last one offered — otherwise a straggler
     * could un-keep a live player's entire history.
     */
    @Test fun `a login clock never runs backwards`() {
        val freshest = System.currentTimeMillis()
        store.noteSeen(alice, at = freshest).join()
        // A straggler: a task that queued behind a slow write and carries an older stamp.
        store.noteSeen(alice, at = freshest - TimeUnit.DAYS.toMillis(10)).join()
        pruneNow()
        val stored = seenAt(alice)
        assertTrue(stored != null && Math.abs(stored - freshest) < 1_000,
            "the newest stamp must win; the straggler at ten days has to be ignored, got $stored")
    }

    @Test fun `noteSeen persists rather than living only in memory`() {
        store.noteSeen(alice).join()
        assertTrue(seenAt(alice) != null, "a login that was never written down cannot outlive a restart")
    }

    // ── threads survive their own pruning ─────────────────────────────────────────────────────

    /**
     * Deleting one side of a conversation must not break the other half of it.
     *
     * The walk already stops at a missing parent — that is what makes a pruned thread degrade into
     * a shorter one instead of vanishing — but nothing said so before, and it is the property that
     * keeps `/meo replay` usable after a prune.
     */
    @Test fun `a reply whose parent was pruned still replays`() {
        store.insert(record("root", authorId = ghost, at = daysAgo(1))).join()
        store.insert(record("leaf", authorId = alice, recipientId = bob, anchor = "root", at = daysAgo(1))).join()
        store.noteSeen(alice).join()
        store.noteSeen(bob).join()
        store.noteSeen(ghost, at = daysAgo(20)).join()
        pruneNow()
        assertEquals(listOf("leaf"), store.thread("leaf").join().map { it.code })
    }

    @Test fun `an intact thread still walks`() {
        store.insert(record("root", authorId = alice, at = daysAgo(2))).join()
        store.insert(record("leaf", authorId = alice, recipientId = bob, anchor = "root", at = daysAgo(1))).join()
        store.noteSeen(alice).join()
        store.noteSeen(bob).join()
        assertEquals(listOf("root", "leaf"), store.thread("root").join().map { it.code })
    }

    @Test fun `replaying a pruned code is empty rather than an error`() {
        store.insert(record("g002", authorId = ghost, at = daysAgo(1))).join()
        store.noteSeen(ghost, at = daysAgo(20)).join()
        pruneNow()
        assertTrue(store.thread("g002").join().isEmpty())
    }

    /**
     * A colliding code must not destroy the older message.
     *
     * The write used to be `INSERT OR REPLACE`, which deletes the colliding row and writes a new
     * one in its place: the older message would be gone from the archive for good, and a reader
     * following the code would land on the newer conversation. A plain insert refuses instead, so
     * the failure is visible and nothing is lost.
     */
    @Test fun `a colliding code fails the write instead of replacing the older row`() {
        store.insert(record("dup1", authorId = alice, body = "第一句")).join()
        assertThrows(java.util.concurrent.CompletionException::class.java) {
            store.insert(record("dup1", authorId = bob, body = "第二句")).join()
        }
        assertEquals(
            listOf("第一句"),
            query("SELECT body FROM chat_log WHERE code = 'dup1'"),
            "the older message must survive a colliding write",
        )
    }

    /** The codes the log holds are readable, so a fresh code cannot collide with an old row. */
    @Test fun `the codes in the log can be read back`() {
        store.insert(record("aaa1", authorId = alice)).join()
        store.insert(record("bbb2", authorId = alice)).join()
        assertEquals(setOf("aaa1", "bbb2"), store.codes().toSet())
    }

    @Test fun `a code that was never written is empty rather than an error`() {
        assertTrue(store.thread("nope").join().isEmpty())
    }

    // ── the right to be forgotten ────────────────────────────────────────────────────────────

    /**
     * The player is in the row in two different roles, and the roles are not interchangeable: a
     * public line where they spoke, and a private line where somebody spoke to them. Deleting only
     * the first would leave their name as the `recipient` of a stranger's sentence.
     */
    @Test fun `forgetting a player removes what they wrote and what was written to them`() {
        store.insert(record("said", authorId = alice, body = "公频发言")).join()
        store.insert(record("received", authorId = bob, recipientId = alice, body = "私信内容")).join()
        store.insert(record("theirs", authorId = bob, body = "别人的发言")).join()

        val removed = store.forgetPlayer(alice).get(5, TimeUnit.SECONDS)

        assertEquals(2, removed)
        assertEquals(setOf("theirs"), codes())
    }

    /**
     * Deliberately **not** deleted, and this is the decision the test exists to hold.
     *
     * `chat_active` is presence, not memory: a uuid and the moment they logged in. Forgetting what
     * somebody said is not pretending they never came online — and the next join would rewrite the
     * row anyway, so deleting it would buy nothing and cost the retention sweep its one reliable
     * signal about who is still around.
     */
    @Test fun `forgetting a player leaves their login marker alone`() {
        store.insert(record("said", authorId = alice)).join()
        store.noteSeen(alice).join()

        store.forgetPlayer(alice).get(5, TimeUnit.SECONDS)

        assertEquals(null, seenAt(ghost), "and it is not written for somebody who was never here")
        assertTrue(seenAt(alice) != null, "presence is not memory: the login marker stays")
    }

    /** Somebody else's public line is not collateral, and an empty answer is not an error. */
    @Test fun `forgetting somebody who never wrote is a zero, not a failure`() {
        store.insert(record("theirs", authorId = bob)).join()
        assertEquals(0, store.forgetPlayer(ghost).get(5, TimeUnit.SECONDS))
        assertEquals(setOf("theirs"), codes())
    }
}