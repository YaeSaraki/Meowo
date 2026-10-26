package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Who a cat says it is, how it behaves, and how much it says.
 *
 * Three separate failures, all of them reported from the same server:
 *
 * 1. **The name.** A persona that says "你是本服的精算师" teaches the model a second name. The cat
 *    then introduces itself as 精算师 and sends the player to "问房东" — and the player, who has
 *    only ever seen `<EconCat>` in chat, has no way to act on any of it. The display name is the
 *    only handle a player has, so it is the only name a cat may use.
 * 2. **The personality.** A paragraph of adjectives gave twelve interchangeable cats. A code
 *    gives a direction in one token, and a code can be **checked** — sixteen codes, twelve cats,
 *    all different.
 * 3. **The length.** Every cat was told to answer in "一两句", so a server full of terse cats
 *    read as a server full of mute NPCs. How much a cat says is now its personality's business;
 *    the hard bound only stops runaway output.
 */
class CatPersonalityTest {

    // ── the name a player can actually type ────────────────────────────────────────────────

    @Test fun `every cat opens with the name a player actually types`() {
        for (cat in CatRegistry.all) {
            assertTrue(cat.persona.startsWith("你是 ${cat.displayName}"),
                "${cat.displayName} does not open with its own name: ${cat.persona}")
        }
    }

    /** The job may be described; it may not be the name. */
    @Test fun `no cat is introduced by a job title instead of a name`() {
        for (cat in CatRegistry.all) {
            val opening = cat.persona.substringBefore("，")
            assertTrue(opening.contains(cat.displayName),
                "${cat.displayName} opens with a title, not a name: $opening")
        }
    }

    @Test fun `the prompt tells the cat its name is the one the player types`() {
        for (cat in CatRegistry.all) {
            val system = PromptBuilder.systemPrompt(cat)
            assertTrue(system.contains("你的名字就是 ${cat.displayName}"),
                "${cat.displayName} was not told its own name: $system")
        }
    }

    /**
     * The rule that keeps a cat from pointing the player at "房东".
     *
     * Without the list, the model has to invent a name for the cat it is handing off to, and the
     * name it invents is a translation of the job title — which no player can type.
     */
    @Test fun `the prompt lists every name a player can type, and forbids job titles`() {
        val system = PromptBuilder.systemPrompt(CatRegistry.fallback())
        for (cat in CatRegistry.all) {
            assertTrue(system.contains(cat.displayName), "${cat.displayName} is missing from the name list: $system")
        }
        for (title in listOf("快递员", "精算师", "房东", "地质学家", "风纪委员")) {
            assertTrue(system.contains("「$title」"),
                "the rule does not name '$title', so the model may still use it: $system")
        }
    }

    // ── personality ─────────────────────────────────────────────────────────────────────────

    @Test fun `every cat has a personality code, and the twelve are all different`() {
        for (cat in CatRegistry.all) {
            assertTrue(cat.mbti in CatProfile.MBTI_CODES, "${cat.id} has an unknown code: ${cat.mbti}")
        }
        val codes = CatRegistry.all.map { it.mbti }
        assertEquals(codes.size, codes.toSet().size, "two cats share a personality code: $codes")
    }

    /** A code on its own is a stereotype; the gloss is what makes it a character. */
    @Test fun `every cat says what its code means for it, and no two say it the same way`() {
        for (cat in CatRegistry.all) {
            assertTrue(cat.personality.isNotBlank(), "${cat.id} has a code and nothing behind it")
            assertTrue(PromptBuilder.systemPrompt(cat).contains(cat.personality),
                "${cat.displayName}: the personality never reaches the prompt")
        }
        val personalities = CatRegistry.all.map { it.personality }
        assertEquals(personalities.size, personalities.toSet().size, "two cats share a personality: $personalities")
    }

    /**
     * The code is a direction for the model, not a line of dialogue.
     *
     * Without this the model starts announcing itself: "作为一只 ISTJ，我建议你……". The player
     * never asked for a personality test.
     */
    @Test fun `the code is for the cat, not for the player`() {
        for (cat in CatRegistry.all) {
            val system = PromptBuilder.systemPrompt(cat)
            assertTrue(system.contains("别把「${cat.mbti}」这种词说给玩家听"),
                "${cat.displayName} was not told to keep the code to itself: $system")
        }
    }

    @Test fun `the personality has a section of its own`() {
        for (cat in CatRegistry.all) {
            assertTrue(PromptBuilder.systemPrompt(cat).contains("## 你的性格"),
                "${cat.displayName} has no personality section")
        }
    }

    // ── how much a cat says ─────────────────────────────────────────────────────────────────

    @Test fun `the reply bound leaves room to actually say something`() {
        assertTrue(PromptBuilder.MAX_REPLY_CHARS >= 300,
            "a bound of ${PromptBuilder.MAX_REPLY_CHARS} is what produced one-line cats")
    }

    /** The old rule is what made every cat the same length, whatever its personality said. */
    @Test fun `the prompt no longer tells every cat to answer in one or two lines`() {
        for (cat in CatRegistry.all) {
            assertFalse(PromptBuilder.systemPrompt(cat).contains("一次只回一两句"),
                "${cat.displayName} is still capped at one or two lines")
        }
    }

    /**
     * A few cats are meant to be terse — that is their character, not a system-wide setting.
     * If *every* cat is terse, the personality has done nothing.
     */
    @Test fun `not every cat is the terse kind`() {
        val terse = CatRegistry.all.count {
            it.tone.contains("简短") || it.tone.contains("惜字如金") || it.tone.contains("极简")
        }
        assertTrue(terse <= CatRegistry.all.size / 3,
            "$terse of ${CatRegistry.all.size} cats are told to be short — the personalities are not showing")
    }
}
