package com.meowo.teleport

import com.meowo.api.TaxAssessment
import com.meowo.api.TaxReason
import java.math.BigDecimal

/**
 * @param backRate fraction of the wallet charged for a `/back` trip. Small on purpose: `/back` is
 *   mostly a rescue from a death, and a fee that punishes losing your items would just teach
 *   players to play safe rather than to use the command at all.
 * @param backMinimum what a `/back` costs even for a broke player, so it is never free.
 * @param backMaximum the ceiling, so a rich player cannot bankrupt themselves with a slip.
 * @param backOnDeath whether dying records a return point. Off would make `/back` useless in the
 *   situation it is most needed, so it is on by default.
 */
data class TeleportSettings(
    val requestSeconds: Int = 60,
    val particles: Boolean = true,
    val countdownSeconds: Int = 3,
    val backRate: BigDecimal = BigDecimal("0.001"),
    val backMinimum: BigDecimal = BigDecimal("10"),
    val backMaximum: BigDecimal = BigDecimal("233"),
    val backOnDeath: Boolean = true,
) {
    init {
        require(requestSeconds in 5..300)
        require(countdownSeconds in 0..30)
        // Constructing the assessment here makes the invariant a start-up check rather than a
        // runtime surprise on a player's first `/back`.
        backAssessment()
    }

    /** 0.1% of the balance, floored at [backMinimum] and capped at [backMaximum]. */
    fun backAssessment(): TaxAssessment = TaxAssessment(
        reason = TaxReason.BACK_TRAVEL,
        rate = backRate,
        minimum = backMinimum,
        maximum = backMaximum,
    )
}
