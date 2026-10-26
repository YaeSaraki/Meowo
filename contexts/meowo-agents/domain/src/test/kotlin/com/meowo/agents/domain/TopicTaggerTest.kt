package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tagger that fills `ImpressionPrompt.Request.topics`.
 *
 * The property under test is not accuracy. It is that a player's own sentence **cannot** come back
 * out of it: this text is pasted into a prompt that writes unprompted opinions about a person to
 * disk, and the sentence above the prompt says "只写你知道的事".
 */
class TopicTaggerTest {

    private fun window(vararg lines: String): List<Turn> =
        lines.map { Turn(Speaker.PLAYER, it) }

    /**
     * The one that matters, stated as a test because the KDoc claim is worth nothing if it is only
     * prose: the output is drawn from a closed table, so free text has no path through.
     */
    @Test fun `a player's own words can never come back out`() {
        val secret = "我叫小明的妈妈叫小红我们养了一只叫旺财的猫在山上的矿洞里"
        val labels = TopicTagger.labels(window(secret))

        assertTrue(labels.isNotEmpty(), "the sentence is full of triggers, so something should match")
        assertTrue(labels.all { it in TopicTagger.LABELS }, "every label must come from the table: $labels")
        for (word in listOf("小明", "小红", "旺财", "妈妈", "矿洞里")) {
            assertFalse(labels.any { it.contains(word) }, "\"$word\" must not survive into a prompt: $labels")
        }
    }

    @Test fun `recognises what a mining conversation was about`() {
        assertTrue("挖矿" in TopicTagger.labels(window("今天去挖矿了", "挖到好多钻石")))
    }

    /** Both languages go through the same pass, because a server with two languages has both. */
    @Test fun `english triggers work too`() {
        assertTrue("挖矿" in TopicTagger.labels(window("went mining, found so much diamond")))
        assertTrue("交易" in TopicTagger.labels(window("anyone want to trade? how much is that")))
    }

    /**
     * Ordering is by how often it came up, so a conversation that was really about one thing leads
     * with it rather than with whatever the table happens to list first.
     */
    @Test fun `the most mentioned topic comes first`() {
        val labels = TopicTagger.labels(window("挖矿", "挖矿", "矿", "挖矿", "农场", "战斗"))
        assertEquals("挖矿", labels.first())
    }

    /** Ties must not be decided by map order, or the same window would prompt differently twice. */
    @Test fun `a tie is broken the same way every time`() {
        val mixed = window("交易", "战斗")
        assertEquals(TopicTagger.labels(mixed), TopicTagger.labels(mixed))
        assertEquals(listOf("交易", "战斗"), TopicTagger.labels(mixed))
    }

    @Test fun `an empty window has no topics`() {
        assertTrue(TopicTagger.labels(emptyList()).isEmpty())
    }

    /**
     * Nothing recognisable is not a topic. Falling back to a default label would be inventing a
     * subject for a person out of nothing — the exact move this subsystem exists to avoid.
     */
    @Test fun `unrecognised small talk has no topics at all`() {
        assertTrue(TopicTagger.labels(window("早上好呀", "今天天气不错", "晚上吃什么")).isEmpty())
    }

    /** Bounded: twelve subjects in one window cannot become twelve labels in a 60-character row. */
    @Test fun `the label list is capped`() {
        val everything = listOf("挖矿", "建造", "农场", "战斗", "交易", "传送", "探索", "科技", "食物", "钓鱼", "求助", "音乐")

        val labels = TopicTagger.labels(everything.map { Turn(Speaker.PLAYER, it) })

        assertEquals(TopicTagger.MAX_TOPICS, labels.size, "every subject is present, so the cap is what bites: $labels")
        assertTrue(labels.all { it in TopicTagger.LABELS })
    }

    /**
     * Precision over recall. Each of these is a word a real player types constantly inside a sentence
     * that is about something else; a label firing on one of them is a false fact about them, fed to
     * a prompt whose whole job is not to have any.
     */
    @Test fun `words that belong to another subject do not fire`() {
        // 家 is in 大家 ("everyone"), not in 传送.
        assertTrue("传送" !in TopicTagger.labels(window("大家好呀")))
        // how are you is a greeting; it is not a request for help.
        assertTrue(TopicTagger.labels(window("how are you")).isEmpty())
        // 金 sits inside 金币, which is money. 交易 is still right here — but from 多少钱, not from 金.
        assertEquals(listOf("交易"), TopicTagger.labels(window("我有多少金币")))
    }

    /** Both sides of the conversation are the conversation. */
    @Test fun `the cat's own lines are counted too`() {
        val turns = listOf(Turn(Speaker.PLAYER, "嗯嗯"), Turn(Speaker.CAT, "要不要一起钓鱼"))
        assertTrue("钓鱼" in TopicTagger.labels(turns))
    }
}
