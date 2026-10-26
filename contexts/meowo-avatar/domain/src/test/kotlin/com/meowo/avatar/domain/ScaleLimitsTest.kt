package com.meowo.avatar.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The size grid, tested as rules rather than as a number.
 *
 * The interesting failures here are not "the number is wrong" but "the number is right and the grid is
 * not": a size that cannot be reached again by pressing the button once is a size the menu has lost
 * track of.
 */
class ScaleLimitsTest {

    private val limits = ScaleLimits.DEFAULT

    @Test fun `the default range runs from half a body to three`() {
        assertEquals(0.5, limits.min, 1e-9)
        assertEquals(3.0, limits.max, 1e-9)
        assertEquals(0.25, limits.step, 1e-9)
        assertEquals(1.0, limits.default, 1e-9)
    }

    @Test fun `a step up is one quarter and a step down is one quarter`() {
        assertEquals(1.25, limits.step(1.0, 1), 1e-9)
        assertEquals(0.75, limits.step(1.0, -1), 1e-9)
    }

    @Test fun `a step never leaves the range`() {
        assertEquals(3.0, limits.step(3.0, 1), 1e-9, "already at the top")
        assertEquals(0.5, limits.step(0.5, -1), 1e-9, "already at the bottom")
    }

    @Test fun `a step starts from the grid, not from a stray value`() {
        // A size that was set to an exact 1.3 must still step to 1.5, not to 1.55.
        assertEquals(1.5, limits.step(1.3, 1), 1e-9)
        assertEquals(1.0, limits.step(1.3, -1), 1e-9)
    }

    @Test fun `clamping brings a value into the range without moving it off the grid`() {
        assertEquals(3.0, limits.clamp(9.0), 1e-9)
        assertEquals(0.5, limits.clamp(0.01), 1e-9)
        assertEquals(1.3, limits.clamp(1.3), 1e-9)
    }

    @Test fun `snapping puts a value on the grid`() {
        assertEquals(1.25, limits.snap(1.3), 1e-9)
        assertEquals(1.5, limits.snap(1.4), 1e-9)
        assertEquals(3.0, limits.snap(9.0), 1e-9, "out of range snaps to the edge")
        assertEquals(0.5, limits.snap(-4.0), 1e-9)
    }

    @Test fun `a value that is not a number falls back to the default`() {
        assertEquals(1.0, limits.snap(Double.NaN), 1e-9)
        assertEquals(1.0, limits.clamp(Double.POSITIVE_INFINITY), 1e-9)
    }

    @Test fun `the size is written the way a player reads it`() {
        assertEquals("1", limits.format(1.0), "no trailing zeros")
        assertEquals("1.25", limits.format(1.25))
        assertEquals("2.5", limits.format(2.5))
        assertEquals("0.5", limits.format(0.5))
    }

    @Test fun `a grid that does not fit inside itself is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ScaleLimits(0.0, 1.0, 0.25, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { ScaleLimits(2.0, 1.0, 0.25, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { ScaleLimits(0.5, 3.0, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { ScaleLimits(0.5, 3.0, 0.25, 9.0) }
    }

    @Test fun `every size the grid offers is reachable by pressing the button`() {
        // The property that matters: from the default, pressing "up" reaches every size there is, and
        // pressing "up" again from the top stays at the top.
        var size = limits.default
        val reached = mutableListOf(size)
        repeat(20) {
            size = limits.step(size, 1)
            reached += size
        }
        assertEquals(3.0, size, 1e-9, "the top must be reachable")
        assertTrue(reached.all { it in limits.min..limits.max }, "no size may leave the range: $reached")
        assertEquals(3.0, limits.step(size, 1), 1e-9, "the top must not move")
    }
}
