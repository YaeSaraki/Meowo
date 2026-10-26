package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The chat range: what each cat owns, and the world the cats live in.
 *
 * Two separate ideas, and the difference matters:
 *
 * - **[CatProfile.scope]** is a cat's **lane**. Without it every cat answered about everything and
 *   the answer came out in the same voice anyway — twelve cats that sound alike *and* know alike
 *   is the dullest possible outcome.
 * - **[PromptBuilder.WORLD_BOUNDARY]** is the **edge of the world**, and it is the same for all
 *   twelve. A cat does not know about the world outside Minecraft because nothing out there ever
 *   reached it; that is a fact about the cat, not a policy it is asked to follow.
 *
 * The scope is **conversation** scope, not tool scope. A cat with no tools still owns a topic it
 * talks about — that is the whole point of a tool-less cat having a voice — so the prompt has to
 * say "能聊的范围" out loud, or "这是你的地盘" and "你没有任何工具" sit next to each other and read
 * as a permission.
 */
class CatScopeTest {

    /** The body of one `## ` section, so a test can assert on the section rather than the whole prompt. */
    private fun section(prompt: String, heading: String): String =
        prompt.substringAfter(heading).substringBefore("\n## ").trim()

    // ── the lane ─────────────────────────────────────────────────────────────────────────────

    @Test fun `every cat declares the range it owns`() {
        for (cat in CatRegistry.all) {
            assertTrue(cat.scope.isNotBlank(), "${cat.displayName} has no scope")
        }
    }

    /** A cat with no lane is a cat that talks about everything, which is the thing being fixed. */
    @Test fun `no two cats claim the same range`() {
        val scopes = CatRegistry.all.map { it.scope }
        assertEquals(scopes.size, scopes.toSet().size, "two cats share a scope: $scopes")
    }

    @Test fun `the range reaches the prompt, under its own heading`() {
        for (cat in CatRegistry.all) {
            val prompt = PromptBuilder.systemPrompt(cat)
            val body = section(prompt, "## 你管什么")
            assertTrue(body.startsWith(cat.scope), "${cat.displayName}: the scope is not in its section: $body")
        }
    }

    /**
     * The scope and the persona are different jobs: one is *what this cat is*, the other is *what
     * this cat is on shift for*. A registry where they are the same sentence has a persona that
     * repeats the scope instead of adding a character to it.
     */
    @Test fun `the range is not a copy of the persona`() {
        for (cat in CatRegistry.all) {
            assertNotEquals(cat.persona, cat.scope, "${cat.id}: persona and scope are the same text")
        }
    }

    /**
     * The scope says what a cat *talks about*; the rule says what it can *look up*. Both are true
     * at once for a tool-less cat, so the prompt has to disambiguate — otherwise "这是你的地盘"
     * next to "你没有任何工具" reads as permission to answer.
     */
    @Test fun `a cat with no tools is told its range is about talking, not looking up`() {
        for (cat in CatRegistry.all) {
            val prompt = PromptBuilder.systemPrompt(cat)
            if (cat.answersFromData) {
                assertFalse(prompt.contains("能聊的范围"),
                    "${cat.displayName} has tools; the tool-less wording does not apply to it")
            } else {
                assertTrue(prompt.contains("能聊的范围"), "${cat.displayName} lost the distinction: $prompt")
            }
        }
    }

    // ── the edge of the world ────────────────────────────────────────────────────────────────

    /**
     * One boundary, written once, identical for every cat.
     *
     * Twelve cats would be twelve chances to phrase the edge more loosely, and the loosest
     * phrasing is the one that lets a cat start talking about school, the weather or the news.
     */
    @Test fun `the world's edge is drawn once, and it is the same edge for every cat`() {
        for (cat in CatRegistry.all) {
            val body = section(PromptBuilder.systemPrompt(cat), "## 你的世界")
            assertEquals(PromptBuilder.WORLD_BOUNDARY.trim(), body,
                "${cat.displayName} was given a different world")
        }
    }

    @Test fun `the boundary is in the prompt of every cat`() {
        for (cat in CatRegistry.all) {
            assertTrue(PromptBuilder.systemPrompt(cat).contains(PromptBuilder.WORLD_BOUNDARY),
                "${cat.displayName} was not told where the world ends")
        }
    }

    /**
     * The boundary is a fact about the cat, not a prohibition it is asked to respect.
     *
     * "现实世界的事别答" leaves a gap the model can fill with a confident guess about whatever the
     * player just brought up. "那边对你来说是空白的" does not: there is nothing there to guess from,
     * and the honest answer is the only one left.
     */
    @Test fun `the boundary says why the cat does not know, not that it must not answer`() {
        assertTrue(PromptBuilder.WORLD_BOUNDARY.contains("空白"),
            "the boundary has to leave the cat nothing to guess from")
        assertFalse(PromptBuilder.WORLD_BOUNDARY.contains("禁止"),
            "a prohibition invites a loophole")
    }

    /**
     * The per-cat scope may not reach past the world the boundary draws.
     *
     * A coarse guard, and deliberately so: the real boundary is [PromptBuilder.WORLD_BOUNDARY],
     * and this list only exists to catch a scope that wanders into the real world on its own.
     */
    @Test fun `no range reaches outside the server and Minecraft`() {
        val outOfWorld = listOf(
            "现实", "新闻", "天气", "股票", "工资", "房价", "学业", "考试", "作业",
            "医院", "生病", "政治", "电影", "小说", "编程", "数学", "生日",
        )
        for (cat in CatRegistry.all) {
            for (word in outOfWorld) {
                assertFalse(cat.scope.contains(word),
                    "${cat.id}: the scope reaches outside the world ('$word'): ${cat.scope}")
            }
        }
    }

    // ── the values a scope must never carry ─────────────────────────────────────────────────

    /** The same rule the samples obey: a static text may not carry something that reads as data. */
    @Test fun `no range carries a value a model could pass off as looked-up data`() {
        for (cat in CatRegistry.all) {
            val why = ProfileText.violation(cat.scope)
            assertNull(why, "${cat.id} scope carries $why: ${cat.scope}")
        }
    }

    // ── twelve cats, not one cat twelve times ───────────────────────────────────────────────

    /**
     * The dullest outcome is not a cat that answers everything — it is twelve cats that answer
     * everything *in the same voice*. The persona and the tone are the two fields that carry that
     * voice, so they are the two that must not repeat.
     */
    @Test fun `the twelve cats do not share a voice`() {
        val personas = CatRegistry.all.map { it.persona }
        val tones = CatRegistry.all.map { it.tone }
        assertEquals(personas.size, personas.toSet().size, "two cats share a persona: $personas")
        assertEquals(tones.size, tones.toSet().size, "two cats share a tone: $tones")
    }

    /**
     * A sample is a worked exchange, not a sentence.
     *
     * One line of prose in the sample slot would let a cat describe a voice instead of showing it,
     * and a description of a voice is exactly what made the cats interchangeable in the first
     * place.
     */
    @Test fun `every cat demonstrates its voice in a two-line exchange`() {
        for (cat in CatRegistry.all) {
            val lines = cat.sample.lines().filter { it.isNotBlank() }
            assertEquals(2, lines.size, "${cat.displayName} sample is not a two-line exchange: ${cat.sample}")
            assertTrue(lines[0].startsWith("玩家："), "${cat.displayName} sample has no player line: ${cat.sample}")
            assertTrue(lines[1].startsWith("${cat.displayName}："),
                "${cat.displayName} sample has no answer line: ${cat.sample}")
        }
    }
}
