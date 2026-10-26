package com.meowo.economy.domain

import com.meowo.api.*
import com.meowo.economy.domain.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class MoneyTest {
    @ParameterizedTest @ValueSource(strings=["-1", "0.001", "1000000000000", "1E100", "1E-100"])
    fun `invalid amounts are rejected`(input: String) {
        assertEquals(EconomyError.INVALID_AMOUNT, assertThrows(EconomyException::class.java) { Money.of(BigDecimal(input)) }.code)
    }
    @Test fun `normalizes exact decimal scale`() {
        assertEquals(Money.of(BigDecimal("1.00")), Money.of(BigDecimal("1.000")))
        assertEquals(BigDecimal("0.30"), (Money.of(BigDecimal("0.1")) + Money.of(BigDecimal("0.2"))).decimal())
        assertEquals(Money.ZERO, Money.fromMinor(0))
        assertEquals(Money.ZERO, Money.fromMinor(1) - Money.fromMinor(1))
    }
    @Test fun `limits and overdrafts cannot overflow`() {
        val max = Money.fromMinor(Money.MAX_MINOR)
        assertEquals(max, max + Money.ZERO)
        assertEquals(EconomyError.LIMIT_EXCEEDED, assertThrows(EconomyException::class.java) { max + Money.fromMinor(1) }.code)
        assertEquals(EconomyError.INSUFFICIENT_FUNDS, assertThrows(EconomyException::class.java) { Money.ZERO - Money.fromMinor(1) }.code)
        assertThrows(EconomyException::class.java) { Money.fromMinor(-1) }
        assertThrows(EconomyException::class.java) { Money.fromMinor(Long.MAX_VALUE) }
    }
}
