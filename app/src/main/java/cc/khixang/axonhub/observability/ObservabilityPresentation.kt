package cc.khixang.axonhub.observability

import cc.khixang.axonhub.core.*
import kotlinx.serialization.json.*
import java.util.Locale

internal fun observabilityIdentifier(key: String): Boolean {
    val normalized = key.lowercase(Locale.ROOT)
    return normalized in setOf("id", "gid", "__typename", "cursor", "externalid") ||
        (normalized.endsWith("id") || normalized.endsWith("ids")) && !normalized.contains("model")
}

internal fun observabilityScalar(value: JsonElement?, key: String, locale: Locale = Locale.getDefault()): String {
    if (value == null || value is JsonNull) return "—"
    if (value !is JsonPrimitive) return "—"
    val text = value.content
    return when {
        DisplayFormat.isTokenQuantity(key) -> text.toDoubleOrNull()?.let(DisplayFormat::compact) ?: "—"
        DisplayFormat.isMoneyQuantity(key) -> DisplayFormat.money(text, locale) ?: "—"
        !value.isString || DisplayFormat.isDecimalQuantity(key) -> DisplayFormat.number(text, locale) ?: if (value.booleanOrNull != null) text else "—"
        key.endsWith("At") || key in setOf("startTime", "endTime", "timestamp", "date", "earliestDate", "lastUpdated") -> DisplayFormat.date(text, locale)
        else -> text
    }
}

internal fun channelHealthRate(success: Long?, failed: Long?): Double? {
    if (success == null || failed == null) return null
    val good = success.coerceAtLeast(0).toDouble(); val bad = failed.coerceAtLeast(0).toDouble()
    return (good + bad).takeIf { it > 0 }?.let { good / it * 100 }
}

internal fun mergeChannelHealthTokens(rates: List<JsonObject>, tokens: List<JsonObject>): List<JsonObject> {
    val byID = tokens.filter { it["channelId"].text.isNotBlank() }.associateBy { it["channelId"].text }
    return rates.map { rate ->
        val token = byID[rate["channelId"].text] ?: return@map rate
        JsonObject(rate + token.filterKeys { it in setOf("inputTokens", "outputTokens", "cachedTokens", "reasoningTokens", "totalTokens") })
    }
}

internal fun observabilityRowName(row: JsonObject): String {
    val name = listOf("name", "channelName", "modelName", "modelId", "apiKeyName", "userName", "projectName").firstNotNullOfOrNull { row[it].text.takeIf(String::isNotBlank) }
    val date = row["date"].text.takeIf(String::isNotBlank)?.let { DisplayFormat.date(it) }
    return listOfNotNull(name, date).joinToString(" · ").ifBlank { "—" }
}

internal fun visibleDashboardRows(rows: List<JsonObject>, search: String, channelType: String, warningsOnly: Boolean, sortField: String, descending: Boolean): List<JsonObject> = rows.filter { row ->
    (search.isBlank() || observabilityRowName(row).contains(search, true)) &&
        (channelType.isBlank() || row["channelType"].text == channelType) &&
        (!warningsOnly || row["channelDisabled"].boolOrNull == true || row["failedCount"].longOrNull?.let { it > 0 } == true)
}.sortedWith { left, right ->
    // Decimal scalars must not lose ordering precision by converting through Double.
    val a = left[sortField].text.toBigDecimalOrNull(); val b = right[sortField].text.toBigDecimalOrNull()
    when { a == null && b == null -> 0; a == null -> 1; b == null -> -1; descending -> b.compareTo(a); else -> a.compareTo(b) }
}
