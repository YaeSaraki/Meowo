package com.meowo.nametag.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The idle rule, tested without a server: this is the variable the nametag reads. */
class IdleStateTest {

    private val afkAfter = 60_000L

    @Test fun `a player who just moved is not away`() {
        val state = IdleState(lastMovedAtMillis = 1_000L, afkAfterMillis = afkAfter)
        assertFalse(state.isAfk(1_000L), "moving resets the idle run")
        assertEquals(0L, state.idleMillis(1_000L))
    }

    @Test fun `a player crosses into away exactly at the threshold`() {
        val state = IdleState(lastMovedAtMillis = 0L, afkAfterMillis = afkAfter)
        assertFalse(state.isAfk(afkAfter - 1), "one tick short of the threshold is still present")
        assertTrue(state.isAfk(afkAfter), "the threshold itself counts as away")
        assertTrue(state.isAfk(afkAfter + 10_000))
    }

    @Test fun `moving clears an idle run and a manual away`() {
        val state = IdleState(0L, afkAfter, manualAway = true)
        assertTrue(state.isAfk(1_000L), "a manual away is away regardless of the clock")
        val moved = state.moved(1_000L)
        assertFalse(moved.isAfk(1_000L), "walking must clear the marker")
        assertFalse(moved.manualAway)
    }

    @Test fun `toggleManual flips the flag and moving resets it`() {
        val state = IdleState(0L, afkAfter)
        val on = state.toggledManual(1_000L)
        assertTrue(on.manualAway)
        assertTrue(on.isAfk(1_000L), "manual away does not wait for the idle threshold")
        val off = on.toggledManual(2_000L)
        assertFalse(off.manualAway, "toggling again clears it")
        assertFalse(off.isAfk(2_000L))
    }

    @Test fun `idle time never goes negative when the clock jumps back`() {
        val state = IdleState(lastMovedAtMillis = 5_000L, afkAfterMillis = afkAfter)
        assertEquals(0L, state.idleMillis(1_000L), "a backwards clock must not produce a negative idle time")
        assertFalse(state.isAfk(1_000L))
    }

    @Test fun `a non-positive threshold is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { IdleState(0L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { IdleState(0L, -1L) }
    }
}
