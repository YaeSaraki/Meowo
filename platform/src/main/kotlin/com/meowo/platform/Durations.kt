package com.meowo.platform

/**
 * A duration a human reads at a glance: `45m`, `3h 20m`, `389d 11h`.
 *
 * ## Why not `Duration.toString()`
 *
 * Because that is a **wire format**, not something anyone reads. A player with 9354 hours on the
 * server saw `PT9354H11M` in their own profile card — technically correct, completely useless, and
 * it looked like a bug. `CurrencyFormat` has the same problem in a different costume and solves it
 * the same way: decide what a person needs to read, not what a library emits.
 *
 * ## Rules
 *
 * Two units at most, largest first, so the number stays scannable:
 *
 * | Span | Shown |
 * |---|---|
 * | under an hour | `45m` |
 * | under a day | `3h 20m`, or just `5h` on a whole hour |
 * | a day or more | `389d 11h`, or just `12d` on a whole day |
 *
 * **A zero trailing unit is dropped** — `5h 0m` says nothing that `5h` does not. The leading unit is
 * never zero by construction. Negative input clamps to zero: a clock that went backwards must not
 * print a negative age.
 *
 * The units are letters rather than words on purpose — a hover card is a glance, and `d`/`h`/`m` read
 * the same on every client language.
 */
object Durations {

    private const val MINUTE = 60_000L

    /** Formats [millis]; negative values read as `0m`. */
    fun short(millis: Long): String {
        val minutes = millis.coerceAtLeast(0L) / MINUTE
        val hours = minutes / 60
        return when {
            minutes < 1 -> "0m"
            hours < 1 -> "${minutes}m"
            hours < 24 -> units(hours, "h", minutes % 60, "m")
            else -> units(hours / 24, "d", hours % 24, "h")
        }
    }

    /** `5h` when the remainder is zero, `3h 20m` when it is not. */
    private fun units(amount: Long, big: String, remainder: Long, small: String): String =
        if (remainder == 0L) "$amount$big" else "$amount$big $remainder$small"

    /** Milliseconds since [epochMillis], i.e. how long ago that was. Never negative. */
    fun since(epochMillis: Long, now: Long = System.currentTimeMillis()): String =
        short(now - epochMillis)
}