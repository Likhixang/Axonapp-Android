package cc.khixang.axonhub.observability

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.network.AxonException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Offline contract tests: no authentication, mutation, or inference traffic. SDL validation is also performed by the parent contract audit. */
class AnalyticsContractTest {
    @Test fun `all fixed documents are read only and select the exact analytics fields`() {
        assertEquals(setOf("earliestDate"), leafSelection(AnalyticsDocuments.ANALYTICS_METADATA))
        assertEquals(setOf("totalTokens", "totalInputTokens", "totalCachedInputTokens", "totalUncachedInputTokens", "totalOutputTokens", "totalRequests", "totalCost"), leafSelection(AnalyticsDocuments.ANALYTICS_OVERVIEW))
        assertEquals(setOf("date", "inputTokens", "cachedInputTokens", "uncachedInputTokens", "outputTokens", "totalTokens", "requestCount", "cost"), leafSelection(AnalyticsDocuments.ANALYTICS_DAILY_STATS))
        assertEquals(setOf("id", "name", "requestCount", "inputTokens", "cachedInputTokens", "outputTokens", "totalTokens", "cost"), leafSelection(AnalyticsDocuments.ANALYTICS_DIMENSION_STATS))
        val documents = AnalyticsDocuments::class.java.declaredFields.filter { it.type == String::class.java }.map { it.get(null) as String }
        assertEquals(28, documents.size)
        documents.forEach { document ->
            assertTrue(document.trimStart().startsWith("query "))
            assertFalse(document.contains("mutation "))
            assertFalse(document.contains("\\$"))
            assertFalse(document.contains("${'$'}{"))
            assertEquals(document.count { it == '{' }, document.count { it == '}' })
            assertEquals(document.count { it == '(' }, document.count { it == ')' })
            val declared = Regex("\\$([A-Za-z][A-Za-z0-9]*):").findAll(document).map { it.groupValues[1] }.toSet()
            val used = Regex("\\$([A-Za-z][A-Za-z0-9]*)").findAll(document).map { it.groupValues[1] }.toSet()
            assertEquals(declared, used)
        }
    }

    @Test fun `statistic documents and variables use only actual server arguments`() {
        assertEquals(17, AnalyticsMetric.entries.size)
        AnalyticsMetric.entries.forEach { metric ->
            assertTrue(metric.document.contains(metric.root))
            val variables = metric.variables(AnalyticsWindow.WEEK)
            when (metric.arguments) {
                AnalyticsArguments.FIXED -> { assertTrue(variables.isEmpty()); assertFalse(metric.document.contains("${'$'}timeWindow")) }
                AnalyticsArguments.WINDOW -> { assertEquals(setOf("timeWindow"), variables.keys); assertEquals("week", variables["timeWindow"].text); assertTrue(metric.document.contains("${'$'}timeWindow: String")) }
                AnalyticsArguments.WINDOW_LIMIT -> { assertEquals(setOf("timeWindow", "limit"), variables.keys); assertEquals(JsonNull, variables["limit"]); assertTrue(metric.document.contains("${'$'}limit: Int")) }
                AnalyticsArguments.FASTEST -> { assertEquals(setOf("input"), variables.keys); assertEquals(5, variables["input"]["limit"].intOrNull); assertEquals("week", variables["input"]["timeWindow"].text); assertTrue(metric.document.contains("${'$'}input: FastestChannelsInput!")) }
            }
            assertFalse(variables.containsKey("filter"))
            assertFalse(variables.containsKey("projectIDs"))
        }
        assertEquals(100, AnalyticsMetric.FASTEST_MODELS.variables(limit = 100)["input"]["limit"].intOrNull)
        assertThrows(IllegalArgumentException::class.java) { AnalyticsMetric.FASTEST_MODELS.variables(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { AnalyticsMetric.CHANNEL_SUCCESS.variables(limit = 101) }
        assertEquals(setOf("day", "week", "month"), AnalyticsWindow.entries.map { it.wire }.toSet())
        assertEquals(setOf("channel", "model", "apiKey", "user"), AnalyticsDimension.entries.map { it.wire }.toSet())
    }

    @Test fun `all statistic result selections match iOS`() {
        val expected = mapOf(
            AnalyticsMetric.CHANNEL_SUCCESS to "channelId channelName channelType channelDisabled successCount failedCount totalCount successRate",
            AnalyticsMetric.REQUEST_CHANNEL to "channelName count", AnalyticsMetric.REQUEST_MODEL to "modelId count", AnalyticsMetric.REQUEST_KEY to "apiKeyId apiKeyName count",
            AnalyticsMetric.TOKEN_CHANNEL to "channelId channelName inputTokens outputTokens cachedTokens reasoningTokens totalTokens",
            AnalyticsMetric.TOKEN_MODEL to "modelId inputTokens outputTokens cachedTokens reasoningTokens totalTokens",
            AnalyticsMetric.TOKEN_KEY to "apiKeyId apiKeyName inputTokens outputTokens cachedTokens reasoningTokens totalTokens",
            AnalyticsMetric.COST_CHANNEL to "channelName cost", AnalyticsMetric.COST_MODEL to "modelId cost", AnalyticsMetric.COST_KEY to "apiKeyId apiKeyName cost",
            AnalyticsMetric.USER_USAGE to "userId userName requestCount totalTokens totalCost", AnalyticsMetric.DAILY_REQUESTS to "date count tokens cost",
            AnalyticsMetric.TOP_PROJECTS to "projectId projectName projectDescription requestCount", AnalyticsMetric.MODEL_PERFORMANCE to "date modelId throughput ttftMs requestCount",
            AnalyticsMetric.CHANNEL_PERFORMANCE to "date channelId channelName throughput ttftMs requestCount",
            AnalyticsMetric.FASTEST_CHANNELS to "channelId channelName channelType throughput tokensCount latencyMs requestCount",
            AnalyticsMetric.FASTEST_MODELS to "modelId modelName throughput tokensCount latencyMs requestCount",
        )
        expected.forEach { (metric, fields) -> assertEquals(metric.name, fields.split(" ").toSet(), leafSelection(metric.document)) }
        assertTrue(AnalyticsDocuments.DASHBOARD_STATS.contains("averageResponseTime"))
        listOf("totalInputTokensThisWeek", "totalOutputTokensThisWeek", "totalCachedTokensThisWeek", "lastUpdated").forEach { assertTrue(AnalyticsDocuments.TOKEN_STATS.contains(it)) }
    }

    @Test fun `filter preserves model strings dates and complete dimension identifiers`() {
        assertTrue(AnalyticsFilter().json().isEmpty())
        val filter = AnalyticsFilter("2026-09-01", "2026-09-30", listOf("p", "p"), listOf("c"), listOf("gpt-5/example"), listOf("k"), listOf("u")).json()
        assertEquals(setOf("startTime", "endTime", "projectIDs", "channelIDs", "modelIDs", "apiKeyIDs", "userIDs"), filter.keys)
        assertEquals("2026-09-01", filter["startTime"].text)
        assertEquals(listOf("p"), filter["projectIDs"].arr.map { it.text })
        assertEquals("gpt-5/example", filter["modelIDs"].arr.single().text)
        assertEquals(listOf("a", "b", "c", "d"), AnalyticsFilter.identifiers("a， b; c\nd a"))
        assertThrows(IllegalArgumentException::class.java) { AnalyticsFilter("2026-10-02", "2026-10-01").json() }
        assertThrows(Exception::class.java) { AnalyticsFilter("2026-02-30").json() }
        assertThrows(IllegalArgumentException::class.java) { AnalyticsFilter("2026-9-1").json() }
        assertThrows(IllegalArgumentException::class.java) { AnalyticsFilter(projectIDs = listOf(" ")).json() }
    }

    @Test fun `resource pagination has complete relay shape and models use modelID not node id`() {
        AnalyticsResource.entries.forEach { kind ->
            listOf("${'$'}first: Int!", "${'$'}after: Cursor", "edges", "cursor", "node", "hasNextPage", "hasPreviousPage", "startCursor", "endCursor", "totalCount").forEach { assertTrue(kind.document.contains(it)) }
            assertEquals(setOf("first"), kind.variables().keys)
            assertEquals("cursor-2", kind.variables(after = "cursor-2")["after"].text)
        }
        val model = buildJsonObject { put("id", "relay-node"); put("modelID", "gpt-5"); put("name", "Model") }
        assertEquals("gpt-5", AnalyticsResource.MODEL.identifier(model))
        assertEquals("relay-node", AnalyticsResource.CHANNEL.identifier(model))
        val first = Json.parseToJsonElement("""{"edges":[{"cursor":"a","node":{"id":"n","name":"N"}}],"pageInfo":{"hasNextPage":true,"endCursor":"a"},"totalCount":2}""")
        val parsed = parseAnalyticsResourcePage(first)
        assertEquals(2, parsed.total); assertEquals("a", parsed.endCursor); assertEquals("n", parsed.items.single()["id"].text)
        val last = Json.parseToJsonElement("""{"edges":[],"pageInfo":{"hasNextPage":false,"endCursor":"a"},"totalCount":0}""")
        assertNull(parseAnalyticsResourcePage(last).endCursor)
        assertEquals(0, parseAnalyticsResourcePage(last).total)
        assertThrows(AxonException.InvalidResponse::class.java) { parseAnalyticsResourcePage(Json.parseToJsonElement("""{"edges":[],"pageInfo":{"hasNextPage":true}}""")) }
    }

    @Test fun `missing metrics remain null and sanitization preserves real numeric statistics`() {
        val raw = buildJsonObject { put("apiKeyName", "Engineering"); put("apiKeyId", "id-1"); put("token", "opaque"); put("totalTokens", 19); put("cost", JsonNull); put("name", "Bearer secret-value"); put("credentialCopy", "credential") }
        val safe = sanitizeAnalytics(raw, "credential")
        assertEquals("Engineering", safe["apiKeyName"].text)
        assertEquals("id-1", safe["apiKeyId"].text)
        assertEquals(19, safe["totalTokens"].intOrNull)
        assertEquals(JsonNull, safe["cost"])
        assertEquals(JsonNull, safe["requestCount"])
        assertFalse(safe.toString().contains("opaque"))
        assertFalse(safe.toString().contains("secret-value"))
        assertEquals(emptyList<JsonObject>(), rowsOrEmpty(JsonNull))
        assertThrows(AxonException.InvalidResponse::class.java) { rowsOrEmpty(JsonPrimitive(0)) }
    }

    private fun leafSelection(document: String): Set<String> = document.substringAfterLast('{').substringBefore('}').trim().split(Regex("\\s+")).filter(String::isNotBlank).toSet()
}
