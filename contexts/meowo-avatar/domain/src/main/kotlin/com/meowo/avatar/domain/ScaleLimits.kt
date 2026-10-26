package com.meowo.avatar.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The rules for a player's body size.
 *
 * A size is **not a free number**: it lives on a grid of [step]s between [min] and [max]. That is what
 * makes "one step bigger" a real step rather than a rounding accident, and it is why the menu's bigger and
 * smaller buttons can never leave a player on a size the grid does not contain.
 *
 * The numbers are **settings** and live in the config; the *rules* — what a step is, how a value is
 * brought into range, how it is written for a player — live here, where they can be tested without a
 * server.
 *
 * @param min the smallest body a player may have.
 * @param max the largest.
 * @param step how much one press of the bigger/smaller button changes.
 * @param default the size a player has until they choose one.
 */
data class ScaleLimits(
    val min: Double,
    val max: Double,
    val step: Double,
    val default: Double,
) {
    init {
        require(min > 0.0 && min.isFinite()) { "avatar scale min must be a positive number" }
        require(max >= min && max.isFinite()) { "avatar scale max must not be below the min" }
        require(step > 0.0 && step.isFinite()) { "avatar scale step must be a positive number" }
        require(default in min..max) { "the default avatar scale must be inside the range" }
    }

    /** The grid the size snaps to, as whole steps. */
    private val steps: IntRange = Math.round(min / step).toInt()..Math.round(max / step).toInt()

    /**
     * [value] brought inside the range, **without** moving it off the grid.
     *
     * Clamping and snapping are separate on purpose: clamping answers "is this allowed", snapping answers
     * "which size is this". A caller that sets an exact size clamps it; a caller that moves by one step
     * snaps first and then steps, so the two can never disagree about where the grid is.
     */
    fun clamp(value: Double): Double {
        if (!value.isFinite()) return default
        return value.coerceIn(min, max)
    }

    /** [value] brought inside the range **and** onto the grid. */
    fun snap(value: Double): Double {
        if (!value.isFinite()) return default
        val index = Math.round(value / step).toInt()
        return index.coerceIn(steps.first, steps.last) * step
    }

    /**
     * One step bigger (a positive [direction]) or one step smaller (a negative one).
     *
     * The result is always on the grid and always inside the range: a player who is already at the limit
     * gets the limit back, not a value past it.
     */
    fun step(current: Double, direction: Int): Double {
        val index = Math.round(current / step).toInt().coerceIn(steps.first, steps.last)
        val moved = index + if (direction < 0) -1 else 1
        return moved.coerceIn(steps.first, steps.last) * step
    }

    /**
     * The size as a player reads it: `1`, `1.25`, `2.5`.
     *
     * Trailing zeros are dropped, because a menu that says `1.00` reads as a different number from one
     * that says `1` — and the player is meant to be reading a size, not a formatted decimal.
     */
    fun format(value: Double): String =
        BigDecimal.valueOf(snap(value)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    companion object {
        /** Half a body to three of them, in quarter steps. */
        val DEFAULT = ScaleLimits(min = 0.5, max = 3.0, step = 0.25, default = 1.0)
    }
}
