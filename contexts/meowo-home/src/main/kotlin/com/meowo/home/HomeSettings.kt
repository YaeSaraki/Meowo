package com.meowo.home

import com.meowo.api.TaxAssessment
import com.meowo.api.TaxReason
import java.math.BigDecimal

/**
 * @param force whether `/home <number> force` may skip the landing-spot check entirely. The escape
 *   hatch for a home whose saved coordinates are no longer standable — see [HomeContext] for why the
 *   strict check strands more homes than the server would expect.
 */
data class HomeSettings(val limit: Int = 10, val setRate: BigDecimal = BigDecimal("0.05"),
    val minimum: BigDecimal = BigDecimal("100"), val maximum: BigDecimal = BigDecimal("2333"),
    val distanceDivisor: BigDecimal = BigDecimal.TEN, val crossWorldFee: BigDecimal = BigDecimal("2333"),
    val crossWorld: Boolean = true, val countdownSeconds: Int = 3, val particles: Boolean = true,
    val locator: Boolean = true, val bossbar: Boolean = true, val force: Boolean = true) {
    init {
        require(limit in 1..1000 && countdownSeconds in 1..30 && distanceDivisor > BigDecimal.ZERO)
        TaxAssessment(TaxReason.HOME_SET,setRate,minimum,maximum)
        require(crossWorldFee >= minimum && crossWorldFee <= maximum)
    }
    fun setAssessment() = TaxAssessment(TaxReason.HOME_SET,setRate,minimum,maximum)

    /**
     * What an admin's correction costs: nothing, while still going through the settlement.
     *
     * Free for two reasons that pull the same way. The player did not ask for it — nobody applied
     * for their home to be moved — and the case that needs it most is an **offline** player whose
     * home is broken, where charging either party means the correction fails for want of funds.
     * Billing the player would also hand every admin a way to drain any wallet on the server.
     *
     * Zero rather than a skipped charge, on purpose: the ticket, the ledger row and the rollback
     * all still happen, so an admin edit is auditable years later exactly like a player's own —
     * `reason=HOME_SET`, `fee=0`. The alternative (bypassing settlement) would make the free path
     * the one path that leaves no trace, which is how a free action turns into an invisible one.
     */
    fun adminSetAssessment() = TaxAssessment(TaxReason.HOME_SET,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)
    fun travelAssessment(distance: Double?) = TaxAssessment(TaxReason.HOME_TRAVEL,minimum=minimum,maximum=maximum,
        fixedAmount=if(distance==null) crossWorldFee else BigDecimal.valueOf(distance).divide(distanceDivisor,8,java.math.RoundingMode.CEILING))
}
