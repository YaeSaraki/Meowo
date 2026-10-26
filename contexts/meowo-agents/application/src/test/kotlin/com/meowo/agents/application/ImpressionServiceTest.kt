package com.meowo.agents.application

import com.meowo.agents.domain.CatRegistry
import com.meowo.agents.domain.ChatMessage
import com.meowo.agents.domain.Completion
import com.meowo.agents.domain.ImpressionPrompt
import com.meowo.agents.domain.LlmClient
import com.meowo.agents.domain.ToolSpec
import com.meowo.agents.infrastructure.PlayerProfileStore
import com.meowo.agents.infrastructure.StoredImpression
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * Layer 2 of the cat's memory, which for a long time did not exist at all.
 *
 * Everything asserted here is a behaviour that could not happen before: the prompt had a permanent
 * blank where the impression goes, `cat_impression` was a table nobody wrote to, and the carefully
 * built [ImpressionPrompt] had no caller anywhere in the project.
 */
class ImpressionServiceTest {

    @TempDir lateinit var dir: Path

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val econ = CatRegistry.byId("economy")!!
    private var store: PlayerProfileStore? = null

    /** Records what the model was asked and answers from a script. */
    private class Scripted(private var answer: String = "很实在，不抱怨") : LlmClient {

        fun says(text: String) { answer = text }
        var calls = 0
            private set
        val prompts = mutableListOf<List<ChatMessage>>()
        /** What the model was offered. The impression call must offer **nothing**. */
        val offeredTools = mutableListOf<List<ToolSpec>>()

        override fun complete(model: String, messages: List<ChatMessage>, tools: List<ToolSpec>): Completion {
            calls++
            prompts += messages.toList()
            offeredTools += tools.toList()
            return Completion.Text(answer)
        }
    }

    @BeforeEach fun open() { store = PlayerProfileStore(dir.resolve("profile.db")) }

    @AfterEach fun close() { store?.close() }

    private var clock = 1_000_000L

    private fun service(
        client: LlmClient = Scripted(),
        traits: String? = "句子不长，几乎都在公频说话",
        spend: (UUID) -> Boolean = { true },
        refreshMillis: Long = 30 * 60_000L,
        locale: String = "zh_CN",
    ): ImpressionService = ImpressionService(
        client = client,
        store = store!!,
        model = "test-model",
        nameOf = { if (it == alice) "Alice" else "Bob" },
        traitsOf = { traits },
        localeOf = { locale },
        spend = spend,
        now = { clock },
        refreshMillis = refreshMillis,
    )

    /** Counts meetings until the cat would decide it has an opinion. */
    private fun ImpressionService.meet(
        cat: com.meowo.agents.domain.CatProfile,
        player: UUID,
        times: Int = ImpressionPrompt.MIN_MEETINGS,
        topics: List<String> = emptyList(),
    ) {
        repeat(times) { afterTurn(cat, player, topics) }
    }

    // ── the gate that keeps the table from filling with nothing ──────────────────────────────

    /**
     * Below the threshold there is no opinion, and — more importantly — **no model call**.
     *
     * Two meetings is not a relationship, and a first-impression-of-nothing is worse than no
     * impression: it would be read by every prompt afterwards as something the cat actually thinks.
     */
    @Test fun `two meetings are not enough for an opinion`() {
        val client = Scripted()
        val s = service(client)
        s.afterTurn(econ, alice)
        s.afterTurn(econ, alice)
        assertEquals(0, client.calls, "a cat must not write an impression of nobody")
        assertNull(s.impressionOf(alice, econ.id))
    }

    @Test fun `three meetings produce one impression`() {
        val client = Scripted()
        val s = service(client)
        s.meet(econ, alice)
        assertEquals(1, client.calls)
        assertEquals("很实在，不抱怨", s.impressionOf(alice, econ.id))
    }

    /** A player who has not said enough has no traits, and traits are the only evidence there is. */
    @Test fun `no traits means no opinion, however many meetings`() {
        val client = Scripted()
        val s = service(client, traits = null)
        repeat(5) { s.afterTurn(econ, alice) }
        assertEquals(0, client.calls)
        assertNull(s.impressionOf(alice, econ.id))
    }

    // ── the prompt it actually sends ──────────────────────────────────────────────────────────

    /**
     * The writing prompt is [ImpressionPrompt]'s, not the chat one.
     *
     * An impression is formed with nobody present, which is exactly the situation that produced a
     * measured hallucination — a confident invented night and an invented visit count. The rules
     * that stop it are in that prompt, so the service must not go around it.
     */
    @Test fun `the impression prompt is the one written against the hallucination`() {
        val client = Scripted()
        service(client).meet(econ, alice)
        val system = (client.prompts.single().first() as ChatMessage.System).content
        assertTrue(system.contains("私人印象"), system.take(200))
        assertTrue(system.contains("只写你知道的事"), system.take(200))
        assertTrue(system.contains("${ImpressionPrompt.MAX_IMPRESSION_CHARS} 字以内"), system.take(200))
        assertTrue(system.contains("你和 Alice 聊过 3 次"), "the meeting count is what it is asked to weigh")
    }

    /**
     * The topic slot has a caller now, and it is the one the field's own KDoc describes: short
     * labels, and an instruction that they are coarse categories rather than events — otherwise a
     * model handed "挖矿" will happily write "他昨天挖了矿", which is a count and a date it was
     * never given.
     */
    @Test fun `topics reach the prompt as labels and are marked as categories, not events`() {
        val client = Scripted()
        service(client).meet(econ, alice, topics = listOf("挖矿", "战斗"))

        val system = (client.prompts.single().first() as ChatMessage.System).content
        assertTrue(system.contains("挖矿、战斗"), system.take(300))
        assertTrue(system.contains("大类标签"), "and told what they are not: ${system.take(300)}")
        assertTrue(system.contains("不是某一次具体的事"), "a label must not be usable as an event: ${system.take(300)}")
    }

    /** No topics recognised means the line is simply absent, not padded with a guess. */
    @Test fun `a conversation with nothing recognisable prompts no topic line at all`() {
        val client = Scripted()
        service(client).meet(econ, alice)

        val system = (client.prompts.single().first() as ChatMessage.System).content
        assertFalse(system.contains("聊过的话题"), "an invented subject is worse than no subject: ${system.take(300)}")
    }

    /**
     * The request refuses free text at construction. `TopicTagger` cannot return it, but a caller
     * can, and this is the last place before the words would be pasted into a prompt about a person
     * — so it is checked where the promise is written down.
     */
    @Test fun `the request refuses a topic that is not a label`() {
        val said = "我们聊过他的猫叫旺财"
        val thrown = runCatching {
            ImpressionPrompt.Request(
                cat = econ, playerName = "Alice", meetings = 3, traits = "短句", topics = listOf(said),
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException, "a player's own sentence must not be a topic: $thrown")
    }

    /** It is a separate request, with no tools: this is thinking, not answering. */
    @Test fun `the impression call offers no tools`() {
        val client = Scripted()
        val s = ImpressionService(
            client = client, store = store!!, model = "m", nameOf = { "Alice" },
            traitsOf = { "短句" }, localeOf = { "zh_CN" }, spend = { true },
            now = { clock },
        )
        s.meet(econ, alice)
        assertEquals(2, client.prompts.single().size, "system plus user, and nothing else")
        // The tools are the point of this assertion, and they were never looked at: the fake threw
        // the argument away, so the test could only ever count messages. A call that started
        // offering tools would have let the model run one, and every impression would have stopped
        // being written — with the suite still green.
        assertTrue(
            client.offeredTools.single().isEmpty(),
            "the impression call must offer no tools: ${client.offeredTools.single()}",
        )
    }

    // ── what may be stored ────────────────────────────────────────────────────────────────────

    /**
     * The output is checked before it reaches the database, not trusted.
     *
     * Every invented count this prompt has produced was a count a reader would take as a fact. The
     * guard used to test for digits, which catches neither of the two examples in the prompt's own
     * KDoc: "去过玩家的农场三次" and "半夜两点" are characters, not digits, and both sailed through
     * the check that was written specifically to stop them.
     */
    @Test fun `an invented count is never stored`() {
        for (hallucination in listOf(
            "他半夜不睡，去过玩家的农场三次",
            "他说他见过 4 个玩家",
            "连续聊了两天",
            "去过一次主城",
        )) {
            val client = Scripted(answer = hallucination)
            val s = service(client)
            s.meet(econ, alice)
            assertNull(s.impressionOf(alice, econ.id), "an invented count must not be stored: $hallucination")
            assertNull(store!!.impressionOf(econ.id, alice.toString()))
        }
    }

    /**
     * The narrowness matters as much as the guard.
     *
     * Ordinary Chinese full of numerals is exactly what an impression should be. Bare numerals are
     * everywhere — "不一定", "一直", "第一" — so a rule that refused them would refuse nearly
     * everything, and a guard that refuses most of what it is meant to keep ends up switched off.
     */
    @Test fun `ordinary words that merely contain a numeral are kept`() {
        for (ordinary in listOf("很实在，不抱怨", "不一定爱说话，但问什么都答", "一直很客气", "说话很直接")) {
            assertEquals(ordinary, ImpressionPrompt.acceptImpression(ordinary), "must not over-refuse: $ordinary")
        }
    }

    @Test fun `a prompt echo is never stored`() {
        val client = Scripted(answer = "## 输出\n玩家：Alice")
        val s = service(client)
        s.meet(econ, alice)
        assertNull(s.impressionOf(alice, econ.id))
    }

    @Test fun `an empty answer stores nothing and costs nothing more`() {
        val client = Scripted(answer = "   ")
        val s = service(client)
        s.meet(econ, alice)
        assertEquals(1, client.calls)
        assertNull(s.impressionOf(alice, econ.id))
    }

    // ── money ─────────────────────────────────────────────────────────────────────────────────

    /** Out of allowance, the cat keeps the impression it already had instead of spending anything. */
    @Test fun `no allowance means no call`() {
        val client = Scripted()
        val s = service(client, spend = { false })
        s.meet(econ, alice)
        assertEquals(0, client.calls)
        assertNull(s.impressionOf(alice, econ.id))
    }

    /**
     * One refresh, not one per message.
     *
     * Without the interval a player who met a cat ten times in a minute would buy ten model calls,
     * and the out-of-band allowance would be the first thing on the server to run out.
     */
    @Test fun `an impression is not rewritten on every meeting`() {
        val client = Scripted()
        val s = service(client)
        s.meet(econ, alice)
        repeat(20) { s.afterTurn(econ, alice) }
        assertEquals(1, client.calls, "one opinion per interval, however often they talk")
    }

    /** Past the interval it is refreshed — an impression of someone who has changed is not worth keeping. */
    @Test fun `a stale impression is refreshed when they come back`() {
        val client = Scripted()
        val s = service(client, refreshMillis = 1_000L)
        s.meet(econ, alice)
        clock += 5_000
        client.says("比以前话多了")
        s.afterTurn(econ, alice)
        assertEquals(2, client.calls)
        assertEquals("比以前话多了", s.impressionOf(alice, econ.id))
    }

    // ── isolation ────────────────────────────────────────────────────────────────────────────

    /** The whole point of keying a session by (player, cat): one cat never inherits another's opinion. */
    @Test fun `one cat's impression is not another's`() {
        val client = Scripted()
        val s = service(client)
        s.meet(econ, alice)
        val mine = s.impressionOf(alice, econ.id)
        assertNull(s.impressionOf(alice, "home"), "NestCat has never met Alice")
        assertNull(s.impressionOf(bob, econ.id), "and this is not about Bob either")
        assertEquals(mine, s.impressionOf(alice, econ.id))
    }

    @Test fun `meetings are counted per cat`() {
        val s = service()
        repeat(2) { s.afterTurn(econ, alice) }
        repeat(1) { s.afterTurn(CatRegistry.byId("home")!!, alice) }
        assertEquals(2, s.meetingsOf(alice, econ.id))
        assertEquals(1, s.meetingsOf(alice, "home"))
    }

    // ── persistence ──────────────────────────────────────────────────────────────────────────

    /** A restart must not lose what a cat thought, or "the cat remembers you" is a session feature. */
    @Test fun `an impression survives a restart`() {
        service().meet(econ, alice)
        val reopened = PlayerProfileStore(dir.resolve("profile.db"))
        try {
            val s = ImpressionService(
                client = Scripted(), store = reopened, model = "m", nameOf = { "Alice" },
                traitsOf = { "短句" }, localeOf = { "zh_CN" }, spend = { true },
            )
            assertEquals("很实在，不抱怨", s.impressionOf(alice, econ.id))
        } finally {
            reopened.close()
        }
    }

    /** A read is cached, so a question does not hit the database every time. */
    @Test fun `a returning player is read once, not once per question`() {
        service().meet(econ, alice)
        val s = service()
        repeat(5) { assertEquals("很实在，不抱怨", s.impressionOf(alice, econ.id)) }
        assertEquals(1, s.cached())
    }

    @Test fun `logging out forgets the meetings and the cache`() {
        val s = service()
        s.meet(econ, alice)
        assertEquals(1, s.cached())
        s.forget(alice)
        assertEquals(0, s.cached())
        assertEquals(0, s.meetingsOf(alice, econ.id))
    }

    @Test fun `refreshing twice updates one row rather than adding another`() {
        val client = Scripted()
        val s = service(client, refreshMillis = 1_000L)
        s.meet(econ, alice)
        clock += 5_000
        client.says("比以前话多了")
        s.afterTurn(econ, alice)
        // `note_count` only moves when the ON CONFLICT clause ran, which is what proves the second
        // write hit the same (cat, player) row instead of inserting a second one.
        val row = store!!.impressionOf(econ.id, alice.toString())
        assertEquals("比以前话多了", row?.impression)
        assertEquals(2, row?.noteCount)
    }

    @Test fun `an empty stored impression reads as no impression`() {
        store!!.upsertImpression(StoredImpression(econ.id, alice.toString(), "", 1, clock))
        assertNull(service().impressionOf(alice, econ.id))
    }

    /** English players get an English impression; the writer follows the asker's language. */
    @Test fun `the impression follows the player's language`() {
        val client = Scripted(answer = "blunt, does not complain")
        val s = service(client, locale = "en_US")
        s.meet(econ, alice)
        assertTrue((client.prompts.single().first() as ChatMessage.System).content.contains("用英文写"))
        assertEquals("blunt, does not complain", s.impressionOf(alice, econ.id))
    }

    @Test fun `nothing written is nothing to forget`() {
        val s = service()
        s.forget(alice)
        assertFalse(s.cached() > 0)
    }
}