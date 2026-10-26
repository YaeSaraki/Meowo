package com.meowo.economy.domain

import com.meowo.economy.domain.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.util.UUID

class EconomyPolicyTest {
    private fun money(value: String) = Money.of(BigDecimal(value))
    @Test fun `tax uses balance before charging and applies floor cap and cent ceiling`() {
        val policy = EconomyPolicy()
        mapOf("0" to "1.00", "500" to "1.00", "1000" to "1.00", "1000.01" to "1.01",
            "12345.67" to "12.35", "233000" to "233.00", "999999999999.99" to "233.00").forEach { (balance, fee) ->
            assertEquals(money(fee), policy.fee(money(balance)))
        }
    }
    @Test fun `tax supports custom rates and zero cost while rejecting inconsistent limits`() {
        assertEquals(money("10"), EconomyPolicy(BigDecimal("0.1"), money("2"), money("50")).fee(money("100")))
        assertEquals(Money.ZERO, EconomyPolicy(BigDecimal.ZERO, Money.ZERO, Money.ZERO).fee(money("100")))
        assertThrows(IllegalArgumentException::class.java) { EconomyPolicy(rate=BigDecimal("1.01")) }
        assertThrows(IllegalArgumentException::class.java) { EconomyPolicy(rate=BigDecimal("-0.01")) }
        assertThrows(IllegalArgumentException::class.java) { EconomyPolicy(minimum=money("234")) }
    }
    @Test fun `ledger operations cannot bypass settlement using wallet change`() {
        assertThrows(IllegalArgumentException::class.java) { Wallet(UUID.randomUUID()).change(Operation.PAY_RECEIVED, money("1")) }
    }
    @Test fun `payment tax uses payment amount rounds upward and validates custom rates`() {
        val policy=EconomyPolicy()
        assertEquals(money("1"),policy.paymentFee(money("100")))
        assertEquals(money("0.03"),policy.paymentFee(money("2.50")))
        assertEquals(money("0.01"),policy.paymentFee(money("0.01")))
        assertEquals(Money.ZERO,policy.paymentFee(Money.ZERO))
        assertEquals(Money.ZERO,policy.copy(paymentRate=BigDecimal.ZERO).paymentFee(money("100")))
        assertThrows(IllegalArgumentException::class.java) { policy.copy(paymentRate=BigDecimal("1.01")) }
    }

}
