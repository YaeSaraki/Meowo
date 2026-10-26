package com.meowo.economy.domain.model

import com.meowo.api.EconomyError
import com.meowo.api.EconomyException
import java.math.BigDecimal
import java.math.RoundingMode

/** Exact fixed-point currency. Rejects rounding, negatives, enormous values and overflow. */
@JvmInline
value class Money private constructor(val minor: Long) {
    fun decimal(): BigDecimal = BigDecimal.valueOf(minor, 2)
    operator fun plus(other: Money): Money {
        if (other.minor > MAX_MINOR - minor) throw EconomyException(EconomyError.LIMIT_EXCEEDED)
        return fromMinor(minor + other.minor)
    }
    operator fun minus(other: Money): Money {
        if (other.minor > minor) throw EconomyException(EconomyError.INSUFFICIENT_FUNDS)
        return fromMinor(minor - other.minor)
    }
    companion object {
        const val MAX_MINOR = 999_999_999_999_99L
        val ZERO = Money(0)
        fun fromMinor(value: Long): Money {
            if (value < 0 || value > MAX_MINOR) throw EconomyException(EconomyError.INVALID_AMOUNT)
            return Money(value)
        }
        fun of(value: BigDecimal): Money {
            if (value.signum() < 0 || value > BigDecimal.valueOf(MAX_MINOR, 2)) throw EconomyException(EconomyError.INVALID_AMOUNT)
            return try { fromMinor(value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()) }
            catch (_: ArithmeticException) { throw EconomyException(EconomyError.INVALID_AMOUNT) }
        }
    }
}
