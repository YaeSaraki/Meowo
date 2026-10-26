package com.meowo.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The duration shown in a profile card.
 *
 * The regression this exists for is a user-visible one: a player with 9354 hours on the server read
 * `PT9354H11M`, because the card called `Duration.toString()`. Correct, unreadable, and it looked like
 * a bug. A wire format is not a duration anyone reads.
 */
class DurationsTest {

    private fun minutes(count: Long) = count * 60_000

    @Test fun `the reported bug no longer renders as ISO-8601`() {
        // What the player saw: PT9354H11M — 9354 hours and 11 minutes.
        val millis = minutes(9354 * 60 + 11)
        val text = Durations.short(millis)
        assertEquals("389d 18h", text)
        assertFalse(text.startsWith("PT"), "an ISO-8601 duration is a wire format, not a reading: $text")
    }

    @Test fun `under an hour shows minutes only`() {
        assertEquals("45m", Durations.short(minutes(45)))
    }

    @Test fun `under a day shows hours and the leftover minutes`() {
        assertEquals("3h 20m", Durations.short(minutes(3 * 60 + 20)))
    }

    @Test fun `a zero trailing unit is dropped`() {
        assertEquals("5h", Durations.short(minutes(5 * 60)), "5h 0m says nothing that 5h does not")
        assertEquals("12d", Durations.short(minutes(12 * 24 * 60)))
    }

    @Test fun `less than a minute still reads as a duration`() {
        assertEquals("0m", Durations.short(59_999))
        assertEquals("0m", Durations.short(0))
    }

    @Test fun `a clock that went backwards does not print a negative age`() {
        assertEquals("0m", Durations.short(-5_000))
    }

    @Test fun `since measures the gap and clamps at zero`() {
        val start = 1_000_000L
        assertEquals("2h", Durations.since(start, now = start + minutes(120)))
        assertEquals("0m", Durations.since(start, now = start - minutes(10)))
    }
}