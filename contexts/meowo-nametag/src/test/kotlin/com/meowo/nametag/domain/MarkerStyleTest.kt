package com.meowo.nametag.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkerStyleTest {

    @Test fun `each state resolves to its own colour`() {
        assertNotEquals(MarkerStyle.AWAY.rgb, MarkerStyle.PRESENT.rgb)
        assertNotEquals(MarkerStyle.AWAY.rgb, MarkerStyle.STUDY.rgb)
        assertNotEquals(MarkerStyle.PRESENT.rgb, MarkerStyle.STUDY.rgb)
        assertEquals(MarkerStyle.AWAY, MarkerStyle.of(NametagState.AWAY))
        assertEquals(MarkerStyle.PRESENT, MarkerStyle.of(NametagState.PRESENT))
        assertEquals(MarkerStyle.STUDY, MarkerStyle.of(NametagState.STUDY))
    }

    @Test fun `the study mark is red, which is the point of it`() {
        // Regression for the request that made this a third state: a player mid-pomodoro must read
        // as studying, and "red" was the explicit requirement. Asserted on the parsed channels so a
        // future tweak to the hex cannot quietly slide it towards orange or pink.
        val (r, g, b) = channels(MarkerStyle.STUDY)
        assertTrue(r > g && r > b, "study must be red-dominant, got r=$r g=$g b=$b")
        assertTrue(r - g > 100 && r - b > 100, "study must be unmistakably red, not a muted brick: $r,$g,$b")
        assertEquals(0xD64545, MarkerStyle.STUDY.rgbValue())
    }

    @Test fun `studying outranks idle, because a focus session is deliberate`() {
        // The case the boolean could not express: a player who has gone quiet by the idle clock but
        // is mid-pomodoro has declared they are here on purpose. Amber "away" would be a lie.
        assertEquals(NametagState.STUDY, NametagState.resolve(afk = true, studying = true))
        assertEquals(NametagState.STUDY, NametagState.resolve(afk = false, studying = true))
        assertEquals(NametagState.AWAY, NametagState.resolve(afk = true, studying = false))
        assertEquals(NametagState.PRESENT, NametagState.resolve(afk = false, studying = false))
    }

    private fun channels(style: MarkerStyle) = Triple(
        style.rgb.substring(0, 2).toInt(16),
        style.rgb.substring(2, 4).toInt(16),
        style.rgb.substring(4, 6).toInt(16),
    )

    @Test fun `colours are soft rather than fully saturated`() {
        // A fully saturated amber reads as an alarm and a pure green as a status LED; neither
        // belongs floating over someone's head all day. Study is deliberately the exception: it is
        // the one state worth interrupting somebody over. This guards the intent, not the exact hue.
        for (style in listOf(MarkerStyle.AWAY, MarkerStyle.PRESENT)) {
            val rgb = style.rgb
            assertEquals(6, rgb.length, "${style.rgb} must be six hex digits")
            rgb.toLong(16) // must parse
            val (r, g, b) = channels(style)
            val spread = maxOf(r, g, b) - minOf(r, g, b)
            assertTrue(spread in 10..120, "${style.rgb} is either grey or fully saturated (spread=$spread)")
        }
    }

    @Test fun `the away colour leans warm and the present colour leans green`() {
        val (ar, ag, ab) = channels(MarkerStyle.AWAY)
        assertTrue(ar > ab && ag > ab, "away must be warm — red and green above blue, got ${ar},$ag,$ab")
        val (pr, pg, pb) = channels(MarkerStyle.PRESENT)
        assertTrue(pg >= pr && pg >= pb, "present must lean green, got r=$pr g=$pg b=$pb")
    }

    @Test fun `the colour resolves to a real RGB value, never null`() {
        // Regression: the mark used to be built with TextColor.fromHexString("0xC9A063"), which
        // Adventure rejects (it only accepts a '#' prefix) and returns null for. A null colour is
        // rendered in the client's default colour, so the marker silently never changed.
        assertEquals(0xC9A063, MarkerStyle.AWAY.rgbValue())
        assertEquals(0xA3D9A0, MarkerStyle.PRESENT.rgbValue())
        assertNotEquals(MarkerStyle.AWAY.rgbValue(), MarkerStyle.PRESENT.rgbValue())
    }

    @Test fun `a malformed colour fails loudly instead of rendering as the default`() {
        val broken = MarkerStyle(rgb = "nothex")
        val error = assertThrows(IllegalArgumentException::class.java) { broken.rgbValue() }
        assertTrue(error.message!!.contains("nothex"), "the message must name the bad value")
    }

    @Test fun `the mark is a quoted tick with a gap, and reads as one character`() {
        assertEquals("'", MarkerStyle.AWAY.glyph, "the mark is an apostrophe, not a dot")
        assertTrue(MarkerStyle.AWAY.gap.isNotEmpty(), "the mark must not touch the name")
        assertEquals(2, (MarkerStyle.AWAY.gap + MarkerStyle.AWAY.glyph).length)
    }
}
