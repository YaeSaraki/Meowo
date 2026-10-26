package com.meowo.economy.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId

/** rate is a fraction: 0.001 means 0.1%. Round up to the smallest currency unit. */
data class EconomyPolicy(
    val rate: BigDecimal = BigDecimal("0.001"),
    val minimum: Money = Money.of(BigDecimal.ONE),
    val maximum: Money = Money.of(BigDecimal("233")),
    val dailyEnabled: Boolean = true,
    val dailyAmount: Money = Money.of(BigDecimal.ONE),
    val zone: ZoneId = ZoneId.of("Asia/Shanghai"),
    val paymentRate: BigDecimal = BigDecimal("0.01"),
) {
    init { require(rate >= BigDecimal.ZERO && rate <= BigDecimal.ONE); require(minimum.minor <= maximum.minor); require(paymentRate >= BigDecimal.ZERO && paymentRate <= BigDecimal.ONE) }
    fun paymentFee(amount: Money): Money = Money.of(amount.decimal().multiply(paymentRate).setScale(2, RoundingMode.CEILING))
    fun fee(balance: Money): Money = Money.fromMinor(Money.of(balance.decimal().multiply(rate).setScale(2, RoundingMode.CEILING)).minor
        .coerceIn(minimum.minor, maximum.minor))
}
