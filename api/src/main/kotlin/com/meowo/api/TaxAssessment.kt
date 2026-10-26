package com.meowo.api

import java.math.BigDecimal
import java.math.RoundingMode

/** Fixed fee or percentage of the current wallet, assessed inside the reservation transaction. */
data class TaxAssessment(val reason: TaxReason, val rate: BigDecimal = BigDecimal.ZERO,
    val minimum: BigDecimal, val maximum: BigDecimal, val fixedAmount: BigDecimal? = null) {
    init {
        require(rate >= BigDecimal.ZERO && rate <= BigDecimal.ONE)
        require(minimum >= BigDecimal.ZERO && maximum >= minimum && maximum <= BigDecimal("999999999999.99"))
        minimum.setScale(2, RoundingMode.UNNECESSARY); maximum.setScale(2, RoundingMode.UNNECESSARY)
        require(fixedAmount == null || fixedAmount >= BigDecimal.ZERO)
    }
    fun amount(balance: BigDecimal): BigDecimal = (fixedAmount ?: balance.multiply(rate))
        .max(minimum).min(maximum).setScale(2, RoundingMode.CEILING)
}
/**
 * Why a charge is being assessed.
 *
 * A reason is stored with the transaction, so the ledger can answer "what was this for" years
 * later. That is the only reason it is an enum: a string field would drift.
 */
enum class TaxReason {
    /** Setting or moving a home. */
    HOME_SET,

    /** Travelling to a home. */
    HOME_TRAVEL,

    /**
     * Returning to where the player was before a death or another teleport.
     *
     * Its own reason rather than a kind of [HOME_TRAVEL] because it is a different promise: a home
     * is somewhere you chose, and `/back` is a refund of a trip that already happened.
     */
    BACK_TRAVEL,
}
