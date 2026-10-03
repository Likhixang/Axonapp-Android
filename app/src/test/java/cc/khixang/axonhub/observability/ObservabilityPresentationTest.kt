package cc.khixang.axonhub.observability

import cc.khixang.axonhub.core.DisplayFormat
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ObservabilityPresentationTest {
    @Test fun `USD uses one decimal and decimal strings never pass through double`() {
        assertEquals("1,234.6 USD", DisplayFormat.money("1234.56", Locale.US))
        assertEquals("9,007,199,254,740,993.1 USD", DisplayFormat.money("9007199254740993.1", Locale.US))
        assertEquals("0.0 USD", DisplayFormat.money("0.004", Locale.US))
        assertEquals("-1.2 USD", DisplayFormat.money("-1.25", Locale.US))
        assertEquals("1.2 USD", DisplayFormat.money(1.24, Locale.US))
        assertEquals("—", DisplayFormat.money(Double.NaN, Locale.US))
        listOf("NaN", "Infinity", "1,2", " 1.2", "", "1 USD").forEach { assertNull(DisplayFormat.money(it, Locale.US)) }
        assertEquals("1.234,6 USD", DisplayFormat.money("1234.56", Locale.GERMANY))
    }

    @Test fun `compact token boundary promotion is locale independent`() {
        assertEquals("999", DisplayFormat.compact(999.0))
        assertEquals("1K", DisplayFormat.compact(1000.0))
        assertEquals("999.9K", DisplayFormat.compact(999949.0))
        assertEquals("1M", DisplayFormat.compact(999950.0))
        assertEquals("-1M", DisplayFormat.compact(-999950.0))
        assertEquals("1B", DisplayFormat.compact(999950000.0))
        assertEquals("—", DisplayFormat.compact(Double.POSITIVE_INFINITY))
    }

    @Test fun `field presentation classifies units without reformatting names and model identifiers`() {
        assertEquals("12.3 USD", observabilityScalar(JsonPrimitive("12.34"), "totalCost", Locale.US))
        assertEquals("1.2K", observabilityScalar(JsonPrimitive(1234), "totalTokens", Locale.US))
        assertEquals("12.34", observabilityScalar(JsonPrimitive("12.345"), "throughput", Locale.US))
        assertEquals("—", observabilityScalar(JsonNull, "cost", Locale.US))
        assertEquals("—", observabilityScalar(JsonPrimitive("NaN"), "cost", Locale.US))
        assertEquals("00123", observabilityScalar(JsonPrimitive("00123"), "modelID", Locale.US))
        assertEquals("00123", observabilityScalar(JsonPrimitive("00123"), "name", Locale.US))
        assertTrue(observabilityIdentifier("channelId"))
        assertTrue(observabilityIdentifier("apiKeyID"))
        assertFalse(observabilityIdentifier("modelID"))
        assertFalse(observabilityIdentifier("totalTokens"))
    }

    @Test fun `channel health uses execution counts and no samples remains unknown`() {
        assertEquals("—", DisplayFormat.percentage(null, Locale.US))
        assertEquals("75.0%", DisplayFormat.percentage(75.0, Locale.US))
        assertNull(channelHealthRate(0L, 0L))
        assertNull(channelHealthRate(null, 1L))
        assertEquals(75.0, channelHealthRate(3L, 1L)!!, 0.0)
        assertEquals(0.0, channelHealthRate(-4L, 1L)!!, 0.0)
    }

    @Test fun `health token join uses channel ID and does not fabricate missing metrics`() {
        val rates = listOf(row("""{"channelId":"a","channelName":"Same","successCount":1} """), row("""{"channelId":"b","channelName":"Same"}"""), row("""{"channelName":"No ID"}"""))
        val tokens = listOf(row("""{"channelId":"a","inputTokens":1234,"totalTokens":4321}"""), row("""{"channelId":"c","totalTokens":999}"""), row("""{"totalTokens":999}"""))
        val merged = mergeChannelHealthTokens(rates, tokens)
        assertEquals(JsonPrimitive(4321), merged[0]["totalTokens"])
        assertEquals(JsonPrimitive(1), merged[0]["successCount"])
        assertNull(merged[0]["outputTokens"])
        assertNull(merged[1]["totalTokens"])
        assertNull(merged[2]["totalTokens"])
        assertNull(rates[0]["totalTokens"])
    }

    @Test fun `health filter includes failed or disabled channels and null sorting remains last`() {
        val rows = listOf(row("""{"channelName":"Beta","channelType":"openai","failedCount":1,"successRate":90}"""), row("""{"channelName":"alpha","channelType":"openai","channelDisabled":true}"""), row("""{"channelName":"Other","channelType":"anthropic","successRate":100}"""))
        val visible = visibleDashboardRows(rows, "", "openai", true, "successRate", false)
        assertEquals(listOf("Beta", "alpha"), visible.map { it["channelName"]!!.jsonPrimitive.content })
        assertEquals(1, visibleDashboardRows(rows, "ALPHA", "", true, "successRate", true).size)
        assertEquals(3, visibleDashboardRows(rows, "", "", false, "successRate", true).size)
    }

    @Test fun `USD decimal sorting keeps adjacent values above double precision distinct`() {
        val rows = listOf(row("""{"name":"larger","cost":"9007199254740993.1"}"""), row("""{"name":"smaller","cost":"9007199254740992.1"}"""))
        assertEquals("smaller", visibleDashboardRows(rows, "", "", false, "cost", false).first()["name"]!!.jsonPrimitive.content)
        assertEquals("larger", visibleDashboardRows(rows, "", "", false, "cost", true).first()["name"]!!.jsonPrimitive.content)
    }

    @Test fun `server date labels do not shift timezone and invalid dates are preserved`() {
        assertEquals("Oct 3, 2026", DisplayFormat.date("2026-10-03", Locale.US))
        assertEquals("2026-02-30", DisplayFormat.date("2026-02-30", Locale.US))
        assertEquals("not-a-date", DisplayFormat.date("not-a-date", Locale.US))
    }

    private fun row(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
