package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ConversationMemoryTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val econ = ConversationKey(alice, "economy")
    private val nest = ConversationKey(alice, "home")
    private val bobEcon = ConversationKey(bob, "economy")

    @Test fun `a session is isolated per cat as well as per player`() {
        val memory = ConversationMemory()
        memory.append(econ, Turn(Speaker.CAT, "你有 100"))
        memory.append(nest, Turn(Speaker.CAT, "你有 3 个家"))

        assertEquals(listOf(Turn(Speaker.CAT, "你有 100")), memory.recent(econ))
        assertEquals(listOf(Turn(Speaker.CAT, "你有 3 个家")), memory.recent(nest))
    }

    @Test fun `a session is isolated per player`() {
        val memory = ConversationMemory()
        memory.append(econ, Turn(Speaker.CAT, "Alice 的秘密"))
        assertTrue(memory.recent(bobEcon).isEmpty(), "Bob must not see Alice's conversation")
    }

    @Test fun `one player talking to one cat cannot be replayed by another cat`() {
        // Without the cat in the key, NestCat would say "you just asked EconCat about your money".
        val memory = ConversationMemory()
        memory.append(econ, Turn(Speaker.CAT, "多少"))
        assertTrue(memory.recent(nest).isEmpty())
    }

    @Test fun `only the last few turns are kept`() {
        val memory = ConversationMemory(turnsPerKey = 3)
        repeat(10) { memory.append(econ, Turn(Speaker.CAT, "turn $it")) }
        val kept = memory.recent(econ, limit = 3)
        assertEquals(3, kept.size)
        assertEquals("turn 9", kept.last().text, "the newest turn must survive the trim")
    }

    @Test fun `the window is evicted, not merely capped`() {
        // A soft cap would let a long-running server accumulate one window per player per cat.
        val memory = ConversationMemory(maxKeys = 8)
        repeat(10_000) { memory.append(ConversationKey(UUID.randomUUID(), "economy"), Turn(Speaker.CAT, "$it")) }
        assertTrue(memory.size <= 8, "the container must evict; it was ${memory.size}")
    }

    @Test fun `quitting clears only that player's sessions`() {
        val memory = ConversationMemory()
        memory.append(econ, Turn(Speaker.CAT, "a"))
        memory.append(nest, Turn(Speaker.CAT, "b"))
        memory.append(bobEcon, Turn(Speaker.CAT, "c"))

        memory.forgetPlayer(alice)
        assertTrue(memory.recent(econ).isEmpty())
        assertTrue(memory.recent(nest).isEmpty())
        assertEquals(1, memory.recent(bobEcon).size, "Bob's session must survive")
    }

    @Test fun `an unknown key reads as empty rather than null`() {
        val memory = ConversationMemory()
        assertTrue(memory.recent(ConversationKey(UUID.randomUUID(), "nope")).isEmpty())
    }

    @Test fun `two keys for the same player are different objects`() {
        assertNotEquals(econ, nest)
        assertEquals(alice, econ.player)
        assertEquals("economy", econ.cat)
    }

    /**
     * The window used to hold six turns and the prompt was fed three — **one and a half exchanges**.
     * A player who talked to a cat for ten minutes and came back was greeted by a stranger, which
     * reads as the cat not caring rather than as it forgetting.
     */
    @Test fun `the default window holds ten exchanges`() {
        val memory = ConversationMemory()
        repeat(30) { memory.append(econ, Turn(Speaker.PLAYER, "第${it}句")) }
        assertEquals(20, memory.recent(econ, limit = 40).size, "twenty turns is ten exchanges")
    }

    @Test fun `a shorter limit returns the newest turns`() {
        val memory = ConversationMemory()
        repeat(8) { memory.append(econ, Turn(Speaker.PLAYER, "第${it}句")) }
        assertEquals(listOf("第6句", "第7句"), memory.recent(econ, limit = 2).map { it.text })
    }

    @Test fun `clear empties everything`() {
        val memory = ConversationMemory()
        memory.append(econ, Turn(Speaker.CAT, "a"))
        memory.clear()
        assertEquals(0, memory.size)
    }
}