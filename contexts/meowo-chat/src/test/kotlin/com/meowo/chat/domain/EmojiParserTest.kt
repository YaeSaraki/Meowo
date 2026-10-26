package com.meowo.chat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `` `name `` → emoji, plus the two things a player actually touches: the pinyin they type and the
 * TAB list they choose from.
 *
 * The completion half lives here on purpose. Deciding *what* to offer needs no Bukkit and no
 * Brigadier, so it is plain domain code with plain tests; only the hand-off to the client is a bridge.
 */
class EmojiParserTest {

    private fun apply(text: String) = EmojiParser.apply(text).text

    // ── substitution ───────────────────────────────────────────────────────────────────────────

    @Test fun `a backtick name becomes its emoji`() {
        assertEquals("你好 😄", apply("你好 `smile"))
    }

    @Test fun `the closing backtick is optional and never left behind`() {
        assertEquals("😢", apply("`sad"))
        assertEquals("😢", apply("`sad`"))
        assertEquals("a 😢 b", apply("a `sad` b"))
    }

    /** The vocabulary players actually type. Their IME is in Chinese; `sad` is not what they reach for. */
    @Test fun `pinyin initials resolve to the same emoji as the English name`() {
        assertEquals(apply("`sad"), apply("`sx"))
        assertEquals(apply("`happy"), apply("`kx"), "kx is the same idea as happy")
        assertEquals(apply("`smile"), apply("`wx"))
    }

    @Test fun `an unknown name is left exactly as typed`() {
        assertEquals("`not_an_emoji", apply("`not_an_emoji"))
        assertEquals("``", apply("``"))
    }

    @Test fun `a trigger with no name is not an emoji`() {
        assertEquals("`", apply("`"))
        assertEquals("a ` b", apply("a ` b"))
    }

    @Test fun `a colon is no longer a trigger`() {
        // The old `::name::` syntax is gone; `:smile:` must survive as ordinary text, because `:` is
        // still a colon in `/meo chat` arguments and in timestamps.
        assertEquals(":smile:", apply(":smile:"))
    }

    @Test fun `substitution is recorded as a rule hit`() {
        val hits = EmojiParser.apply("`sad").hits
        assertEquals(1, hits.size)
        assertEquals(RuleHit.Kind.EMOJI, hits.first().kind)
    }

    // ── completion ─────────────────────────────────────────────────────────────────────────────

    // ── unique-prefix resolution (the feature Tab cannot do here) ──────────────────────────────

    @Test fun `a unique prefix resolves without any keystroke`() {
        assertEquals("😢", apply("`sa"), "sa can only be sad")
        assertEquals("🎂", apply("`da"), "da can only be dan")
        assertEquals("🔥", apply("`hu"), "hu can only be huo")
    }

    @Test fun `an ambiguous prefix is left exactly as typed`() {
        // Guessing here would silently send the wrong face, which is worse than sending none.
        assertEquals("`s", apply("`s"), "s matches sad, sx, sleep, snow, star and more")
        assertEquals("`t", apply("`t"), "t matches thumbsup, thumbsdown, thinking and time")
        assertEquals("`mo", apply("`mo"), "mo matches moon, money, mao, meow and muscle")
    }

    @Test fun `a single letter never guesses`() {
        assertEquals("`k", apply("`k"))
    }

    @Test fun `an unknown prefix stays unknown`() {
        assertEquals("`zzzz", apply("`zzzz"))
    }

    @Test fun `an exact name always wins over a longer match`() {
        assertEquals("😢", apply("`sad"))
        assertEquals("☕", apply("`kf"))
    }

    @Test fun `resolve reports nothing for what it cannot be sure about`() {
        assertEquals("😄", EmojiParser.resolve("smile"))
        assertEquals("😄", EmojiParser.resolve("kx"), "an initial resolves like the name it stands for")
        assertEquals("😢", EmojiParser.resolve("sa"))
        assertNull(EmojiParser.resolve("s"), "one letter is never a guess")
        assertNull(EmojiParser.resolve("k3"))
        assertNull(EmojiParser.resolve("zzz"))
    }

    @Test fun `every default key resolves through its own prefix`() {
        for (key in EmojiParser.DEFAULTS.keys) {
            assertEquals(
                EmojiParser.DEFAULTS[key], EmojiParser.resolve(key),
                "`$key does not resolve to its own emoji",
            )
        }
    }

    @Test fun `a bare trigger offers every name`() {
        assertEquals(EmojiParser.DEFAULTS.size, EmojiParser.complete("`").size)
    }

    @Test fun `a prefix narrows to the names that start with it`() {
        val found = EmojiParser.complete("`sa")
        assertEquals(listOf("sad`"), found)
    }

    @Test fun `a pinyin abbreviation completes to the readable name`() {
        // Typing initials should hand back the word a reader wants, not the initials again.
        assertTrue(EmojiParser.complete("`sx").contains("sad`"), "sx must lead to sad")
        assertTrue(EmojiParser.complete("`kx").contains("smile`"), "kx must lead to the smile row")
        assertTrue(EmojiParser.complete("`wx").contains("smile`"))
    }

    @Test fun `an exact abbreviation offers both spellings, never one emoji twice`() {
        val offered = EmojiParser.complete("`sx")
        assertEquals(listOf("sad`", "sx`"), offered)
        assertEquals(
            apply("`sad"),
            // Each offer arrives as `name` — closing mark already attached — so it resolves on its own.
            offered.map { apply("`" + it.dropLast(1)) }.distinct().single(),
            "every offer must land on the same character",
        )
    }

    /** Tab fills in the closing mark so the message still goes through `apply` and stays editable. */
    @Test fun `every completion carries its closing backtick`() {
        assertTrue(EmojiParser.complete("`sa").all { it.endsWith("`") })
    }

    @Test fun `an unknown prefix offers nothing rather than everything`() {
        assertEquals(emptyList<String>(), EmojiParser.complete("`zzzz"))
    }

    // ── what counts as an open fragment ────────────────────────────────────────────────────────

    @Test fun `an open fragment at the end of the buffer is reported`() {
        assertEquals("`", EmojiParser.fragment("hi `"))
        assertEquals("`s", EmojiParser.fragment("hi `s"))
        assertEquals("`smile", EmojiParser.fragment("`smile"))
    }

    /**
     * A backtick at the end whose previous character is part of a name is the **closing** one.
     * Offering completions after it would make the client insert text after the emoji instead of
     * replacing the name.
     */
    @Test fun `a closed fragment is not offered again`() {
        assertNull(EmojiParser.fragment("`smile`"))
        assertNull(EmojiParser.fragment("hi `smile`"))
    }

    @Test fun `a buffer with no trigger offers nothing`() {
        assertNull(EmojiParser.fragment("hello"))
        assertNull(EmojiParser.fragment(""))
        assertNull(EmojiParser.fragment("/meo msg Al"))
    }

    @Test fun `the fragment is the tail, so the rest of the line is untouched`() {
        val fragment = EmojiParser.fragment("hey `sm")
        assertEquals("`sm", fragment)
        assertTrue(fragment!!.startsWith("`"), "the trigger has to be part of the replaced range")
    }

    @Test fun `every default key is reachable from its own fragment`() {
        // A table entry the completion cannot reach is an entry only its author knows about.
        for (key in EmojiParser.DEFAULTS.keys) {
            val offered = EmojiParser.complete("`$key")
            assertTrue(offered.contains("$key`"), "`$key is missing from its own completion")
            assertEquals(EmojiParser.DEFAULTS[key], apply("`$key"), "`$key does not resolve to its own emoji")
        }
    }
}