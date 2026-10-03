package cc.khixang.axonhub.management

import cc.khixang.axonhub.core.DisplayFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Management adapter to the shared display rules; API/editor values remain exact. */
object ManagementFormat {
    private val decimal = Regex("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$")
    fun parse(text: String): BigDecimal? = if (decimal.matches(text)) text.toBigDecimalOrNull() else null
    fun number(text: String, locale: Locale = Locale.getDefault()): String = DisplayFormat.number(text, locale) ?: "—"
    fun money(text: String, locale: Locale = Locale.getDefault()): String = DisplayFormat.money(text, locale) ?: "—"
    fun isMoney(key: String) = DisplayFormat.isMoneyQuantity(key)
    fun isQuantity(key: String) = DisplayFormat.isDecimalQuantity(key)
    fun isToken(key: String) = DisplayFormat.isTokenQuantity(key)
    fun display(key: String, raw: String): String = when {
        isMoney(key) -> money(raw)
        isToken(key) -> compact(raw)
        isQuantity(key) -> number(raw)
        else -> raw
    }
    fun compact(text: String): String {
        val value = parse(text) ?: return "—"
        val scales = listOf(BigDecimal("1000") to "K", BigDecimal("1000000") to "M", BigDecimal("1000000000") to "B")
        var index = scales.indexOfLast { value.abs() >= it.first }
        if (index in 0..1 && value.abs().divide(scales[index].first).setScale(1, RoundingMode.HALF_UP) >= BigDecimal("1000")) index++
        return if (index < 0) value.setScale(0, RoundingMode.HALF_EVEN).toPlainString()
        else value.divide(scales[index].first).setScale(1, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + scales[index].second
    }
}
