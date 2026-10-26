package com.meowo.platform

import java.math.BigDecimal

/**
 * Renders an amount as `12.50 ฅ` — number first, symbol after.
 *
 * The full name is deliberately **not** repeated here. It appears only where the unit is actually
 * being introduced (`eco-usage`); everywhere else the symbol alone is the shorter, less noisy form.
 * Running totals are left to the vanilla statistics screen, which already reports them per block.
 */
data class CurrencyFormat(val name: String = "Meo", val symbol: String = "ฅ") {
    init { require(name.isNotBlank() && name.length <= 32 && name.none { it.isISOControl() }); require(symbol.length <= 16 && symbol.none { it.isISOControl() }) }
    fun format(amount: BigDecimal): String = "${amount.setScale(2).toPlainString()} $symbol"
}
