package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The extra traits, and the line they must not cross.
 *
 * Everything here is countable and descriptive: it says how somebody talks, never what they are.
 * A profile that judged a person would propagate into every cat's prompt, and one cat's bad day
 * would become everybody's belief.
 */
class StyleSignalsTest {

    private fun say(c: BehaviorCounters, text: String, hour: Int = 20) = c.onMessage(text, true, false, hour)

    private fun counters(n: Int, text: String): BehaviorCounters {
        val c = BehaviorCounters()
        repeat(n) { say(c, text) }
        return c
    }

    @Test fun `a questioner is noticed`() {
        val c = BehaviorCounters()
        repeat(30) { index -> say(c, if (index % 2 == 0) "这是真的吗？" else "好的") }
        val texts = TraitMath.styleSignals(c).map { it.text }
        assertTrue("爱问问题" in texts, texts.toString())
    }

    @Test fun `swearing is described, not judged`() {
        val c = counters(40, "卧槽 这也太离谱了")
        val texts = TraitMath.styleSignals(c).map { it.text }
        assertTrue("爱爆粗口" in texts, texts.toString())
        // And the description stays a description.
        for (w in listOf("粗鲁", "没素质", "礼貌", "素质差")) {
            assertFalse(texts.any { it.contains(w) }, texts.toString())
        }
    }

    @Test fun `a habit needs its own sample floor, not the global one`() {
        // Twenty messages is the floor for "爱问问题" and thirty for "爱爆粗口", so twenty curses do
        // not yet earn a word about somebody's language.
        val twenty = counters(20, "卧槽")
        assertFalse("爱爆粗口" in TraitMath.styleSignals(twenty).map { it.text })
        val thirty = counters(30, "卧槽")
        assertTrue("爱爆粗口" in TraitMath.styleSignals(thirty).map { it.text })
    }

    @Test fun `plain speech produces no style habits at all`() {
        val c = counters(50, "我先去挖矿了")
        assertTrue(TraitMath.styleSignals(c).isEmpty(), TraitMath.styleSignals(c).toString())
    }

    /** One signal per test: a combined case reports only that "an assertTrue failed". */
    @Test fun `exclamations are counted`() {
        assertSignal("语气很冲", "太好了！")
    }

    @Test fun `ellipses are counted`() {
        assertSignal("爱用省略号", "好吧…")
    }

    @Test fun `tildes are counted`() {
        assertSignal("爱用波浪号", "好呀~")
    }

    @Test fun `very short replies are counted`() {
        assertSignal("回得很短", "嗯")
    }

    @Test fun `long messages are counted`() {
        val long = "这是一段相当长的消息，用来确认长段统计确实在工作，而不是把短消息也算进去。" +
            "顺便再加一句让它真的超过六十个字才算数，才算数。"
        assertTrue(long.length > 60, "the fixture itself must be long enough, was ${long.length}")
        assertSignal("爱写长段", long)
    }

    private fun assertSignal(expected: String, text: String) {
        val found = TraitMath.styleSignals(counters(40, text)).map { it.text }
        assertTrue(expected in found, "expected '$expected' from '$text', got $found")
    }

    @Test fun `shouting is only shouting in a language that has no capitals`() {
        assertTrue("会打全大写" in TraitMath.styleSignals(counters(40, "STOP DOING THAT")).map { it.text })
        // Three capitals is a name or an acronym, not a habit.
        assertFalse("会打全大写" in TraitMath.styleSignals(counters(40, "好的，NPC 在吗")).map { it.text })
    }

    @Test fun `the description stays bounded`() {
        val c = BehaviorCounters()
        repeat(60) { say(c, "这是什么？！好吧…嗯~") }
        val traits = TraitMath.traitsOf(c.snapshot(), c)!!
        assertTrue(
            traits.count { it == '，' } + 1 <= TraitMath.MAX_TRAIT_PARTS + 3,
            "traits must not grow without bound: $traits",
        )
    }

    @Test fun `a stored summary alone still yields the coarse traits`() {
        // No live counters: the four coarse traits are all that can honestly be said.
        val shape = ChatShape(50, 3.0, 100.0, 0.0, 21)
        val traits = TraitMath.traitsOf(shape)!!
        assertTrue(traits.contains("很短"), traits)
        assertTrue(traits.contains("公频"), traits)
    }

    @Test fun `describing style never invents biography`() {
        val c = counters(40, "我叫小明，45 岁")
        val traits = TraitMath.traitsOf(c.snapshot(), c)!!
        for (w in listOf("45", "小明", "岁")) assertFalse(traits.contains(w), traits)
    }

    @Test fun `style traits respect the minimum sample floor too`() {
        val c = counters(4, "这是什么？")
        assertNull(TraitMath.traitsOf(c.snapshot(), c))
    }
}
