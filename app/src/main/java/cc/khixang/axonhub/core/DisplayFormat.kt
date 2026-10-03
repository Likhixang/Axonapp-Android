package cc.khixang.axonhub.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** UI-only iOS DisplayFormat parity. Never send abbreviated/rounded values in API payloads. */
object DisplayFormat {
    private val decimal = Regex("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$")
    private val moneyKeys = setOf("cost", "totalcost", "price", "priceperunit", "subtotal", "flatfee", "usageperunit", "cacheread", "cachewrite")
    private val decimalKeys = moneyKeys + setOf("avgtokenspersecond", "throughput", "avgtimetofirsttokenms", "averageresponsetime", "successrate", "hitrate", "latency", "latencyms", "metricslatencyms", "metricsfirsttokenlatencyms", "metricsreasoningdurationms", "duration")
    fun isMoneyQuantity(key: String) = key.lowercase(Locale.ROOT) in moneyKeys
    fun isDecimalQuantity(key: String) = key.lowercase(Locale.ROOT) in decimalKeys
    fun isTokenQuantity(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return normalized.contains("tokens") && !normalized.contains("persecond") || normalized in setOf("maxtoken", "tokenlimit", "tokenquota")
    }
    private fun parse(text: String): BigDecimal? = if (decimal.matches(text)) runCatching { BigDecimal(text) }.getOrNull() else null
    private fun formatted(value: BigDecimal, min: Int, max: Int, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = min; maximumFractionDigits = max; roundingMode = RoundingMode.HALF_EVEN
        maximumIntegerDigits = Int.MAX_VALUE
    }.format(value)
    fun number(value: Double, locale: Locale = Locale.getDefault()): String = if (value.isFinite()) formatted(BigDecimal.valueOf(value), 0, 2, locale) else "—"
    fun number(text: String, locale: Locale = Locale.getDefault()): String? = parse(text)?.let { formatted(it, 0, 2, locale) }
    fun money(value: Double?, locale: Locale = Locale.getDefault()): String = if (value != null && value.isFinite()) formatted(BigDecimal.valueOf(value), 1, 1, locale) + " USD" else "—"
    fun money(text: String, locale: Locale = Locale.getDefault()): String? = parse(text)?.let { formatted(it, 1, 1, locale) + " USD" }
    fun percentage(value: Double?, locale: Locale = Locale.getDefault()): String = if (value != null && value.isFinite()) formatted(BigDecimal.valueOf(value), 1, 1, locale) + "%" else "—"
    fun compact(value: Double): String {
        if (!value.isFinite()) return "—"
        val scales = listOf(1000.0 to "K", 1000000.0 to "M", 1000000000.0 to "B")
        var index = scales.indexOfLast { kotlin.math.abs(value) >= it.first }
        if (index in 0..1 && BigDecimal.valueOf(kotlin.math.abs(value) / scales[index].first).setScale(1, RoundingMode.HALF_UP) >= BigDecimal(1000)) index++
        val divisor = if (index < 0) 1.0 else scales[index].first
        return NumberFormat.getNumberInstance(Locale.US).apply {
            isGroupingUsed = false; minimumFractionDigits = 0; maximumFractionDigits = if (index < 0) 0 else 1
            roundingMode = RoundingMode.HALF_EVEN
        }.format(value / divisor) + if (index < 0) "" else scales[index].second
    }
    fun date(text: String, locale: Locale = Locale.getDefault()): String {
        if (text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) {
            return runCatching {
                val day = LocalDate.parse(text)
                DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()))
            }.getOrDefault(text)
        }
        return runCatching { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date.from(OffsetDateTime.parse(text).toInstant())) }.getOrDefault(text)
    }
}
