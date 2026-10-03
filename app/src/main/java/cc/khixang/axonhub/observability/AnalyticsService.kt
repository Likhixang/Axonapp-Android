package cc.khixang.axonhub.observability

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.network.AxonException
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Read-only beta10 analytics contracts. Missing values stay JsonNull, never synthesized zeroes. */
enum class AnalyticsWindow(val wire: String) { DAY("day"), WEEK("week"), MONTH("month") }
enum class AnalyticsDimension(val wire: String) { CHANNEL("channel"), MODEL("model"), API_KEY("apiKey"), USER("user") }
enum class AnalyticsArguments { FIXED, WINDOW, WINDOW_LIMIT, FASTEST }
enum class AnalyticsMetric(val root: String, val document: String, val chartField: String, val arguments: AnalyticsArguments) {
    CHANNEL_SUCCESS("channelSuccessRates", AnalyticsDocuments.CHANNEL_SUCCESS_RATES, "successRate", AnalyticsArguments.WINDOW_LIMIT),
    REQUEST_CHANNEL("requestStatsByChannel", AnalyticsDocuments.REQUESTS_BY_CHANNEL, "count", AnalyticsArguments.WINDOW),
    REQUEST_MODEL("requestStatsByModel", AnalyticsDocuments.REQUESTS_BY_MODEL, "count", AnalyticsArguments.WINDOW),
    REQUEST_KEY("requestStatsByAPIKey", AnalyticsDocuments.REQUESTS_BY_API_KEY, "count", AnalyticsArguments.WINDOW),
    TOKEN_CHANNEL("tokenStatsByChannel", AnalyticsDocuments.TOKENS_BY_CHANNEL, "totalTokens", AnalyticsArguments.WINDOW),
    TOKEN_MODEL("tokenStatsByModel", AnalyticsDocuments.TOKENS_BY_MODEL, "totalTokens", AnalyticsArguments.WINDOW),
    TOKEN_KEY("tokenStatsByAPIKey", AnalyticsDocuments.TOKENS_BY_API_KEY, "totalTokens", AnalyticsArguments.WINDOW),
    COST_CHANNEL("costStatsByChannel", AnalyticsDocuments.COST_BY_CHANNEL, "cost", AnalyticsArguments.WINDOW),
    COST_MODEL("costStatsByModel", AnalyticsDocuments.COST_BY_MODEL, "cost", AnalyticsArguments.WINDOW),
    COST_KEY("costStatsByAPIKey", AnalyticsDocuments.COST_BY_API_KEY, "cost", AnalyticsArguments.WINDOW),
    USER_USAGE("usageStatsByUser", AnalyticsDocuments.USAGE_STATS_BY_USER, "totalTokens", AnalyticsArguments.WINDOW),
    DAILY_REQUESTS("dailyRequestStats", AnalyticsDocuments.DAILY_REQUEST_STATS, "count", AnalyticsArguments.FIXED),
    TOP_PROJECTS("topRequestsProjects", AnalyticsDocuments.TOP_PROJECTS, "requestCount", AnalyticsArguments.FIXED),
    MODEL_PERFORMANCE("modelPerformanceStats", AnalyticsDocuments.MODEL_PERFORMANCE_STATS, "throughput", AnalyticsArguments.FIXED),
    CHANNEL_PERFORMANCE("channelPerformanceStats", AnalyticsDocuments.CHANNEL_PERFORMANCE_STATS, "throughput", AnalyticsArguments.FIXED),
    FASTEST_CHANNELS("fastestChannels", AnalyticsDocuments.FASTEST_CHANNELS, "throughput", AnalyticsArguments.FASTEST),
    FASTEST_MODELS("fastestModels", AnalyticsDocuments.FASTEST_MODELS, "throughput", AnalyticsArguments.FASTEST);

    val supportsWindow get() = arguments != AnalyticsArguments.FIXED
    val supportsLimit get() = arguments in setOf(AnalyticsArguments.WINDOW_LIMIT, AnalyticsArguments.FASTEST)
    fun variables(window: AnalyticsWindow = AnalyticsWindow.DAY, limit: Int? = null): JsonObject {
        require(limit == null || limit in 1..100) { "limit must be between 1 and 100" }
        return buildJsonObject {
            when (arguments) {
                AnalyticsArguments.FIXED -> Unit
                AnalyticsArguments.WINDOW -> put("timeWindow", window.wire)
                AnalyticsArguments.WINDOW_LIMIT -> { put("timeWindow", window.wire); put("limit", limit?.let(::JsonPrimitive) ?: JsonNull) }
                AnalyticsArguments.FASTEST -> put("input", buildJsonObject { put("timeWindow", window.wire); put("limit", limit ?: 5) })
            }
        }
    }
}

/** YYYY-MM-DD is inclusive in the SERVER timezone, not a converted UTC instant. Model IDs are modelID strings, not Relay node IDs. */
data class AnalyticsFilter(
    val startTime: String? = null, val endTime: String? = null,
    val projectIDs: List<String> = emptyList(), val channelIDs: List<String> = emptyList(),
    val modelIDs: List<String> = emptyList(), val apiKeyIDs: List<String> = emptyList(), val userIDs: List<String> = emptyList(),
) {
    fun json(): JsonObject {
        fun date(value: String?): LocalDate? = value?.let {
            require(it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "Expected YYYY-MM-DD" }
            LocalDate.parse(it)
        }
        val start = date(startTime); val end = date(endTime)
        require(start == null || end == null || start <= end) { "Start date is later than end date" }
        return buildJsonObject {
            startTime?.let { put("startTime", it) }; endTime?.let { put("endTime", it) }
            listOf("projectIDs" to projectIDs, "channelIDs" to channelIDs, "modelIDs" to modelIDs, "apiKeyIDs" to apiKeyIDs, "userIDs" to userIDs).forEach { (key, values) ->
                require(values.all { it.isNotBlank() && it == it.trim() }) { "Empty or padded identifier" }
                if (values.isNotEmpty()) put(key, JsonArray(values.distinct().map(::JsonPrimitive)))
            }
        }
    }
    companion object {
        fun identifiers(text: String): List<String> = text.split(Regex("[,，;；\\s]+")).map(String::trim).filter(String::isNotBlank).distinct()
    }
}

enum class AnalyticsResource(val root: String, val filterKey: String, val document: String) {
    PROJECT("projects", "projectIDs", AnalyticsDocuments.PROJECTS),
    CHANNEL("channels", "channelIDs", AnalyticsDocuments.CHANNELS),
    MODEL("models", "modelIDs", AnalyticsDocuments.MODELS),
    API_KEY("apiKeys", "apiKeyIDs", AnalyticsDocuments.API_KEYS),
    USER("users", "userIDs", AnalyticsDocuments.USERS);
    fun identifier(row: JsonObject): String = row[if (this == MODEL) "modelID" else "id"].text
    fun label(row: JsonObject): String = if (this == USER) listOf(row["firstName"].text, row["lastName"].text).filter(String::isNotBlank).joinToString(" ").ifBlank { row["email"].text.ifBlank { row["id"].text } }
        else row["name"].text.ifBlank { identifier(row) }
    fun variables(first: Int = 25, after: String? = null): JsonObject {
        require(first in 1..100)
        return buildJsonObject { put("first", first); after?.let { require(it.isNotBlank()); put("after", it) } }
    }
}

data class AnalyticsBundle(val metadata: JsonElement, val overview: JsonElement, val daily: List<JsonObject>, val breakdown: List<JsonObject>)
data class AnalyticsDashboard(val overview: JsonElement, val tokens: JsonElement, val rows: List<JsonObject>)

class AnalyticsService(private val repository: AxonRepository) {
    /** All reads in a bundle use one registered cancellable repository job and one fence/session. */
    suspend fun analytics(filter: AnalyticsFilter, dimension: AnalyticsDimension, fence: TargetFence = repository.currentFence()): AnalyticsBundle = repository.fenced { session, project ->
        repository.verify(fence)
        val variables = buildJsonObject { put("filter", filter.json()) }
        suspend fun read(document: String, root: String, vars: JsonObject = variables): JsonElement {
            repository.verify(fence)
            val data = repository.api.graphQl(session, document, vars, project)
            repository.verify(fence)
            return sanitizeAnalytics(data[root] ?: throw AxonException.InvalidResponse, session.token)
        }
        val metadata = read(AnalyticsDocuments.ANALYTICS_METADATA, "analyticsMetadata", buildJsonObject {})
        val overview = read(AnalyticsDocuments.ANALYTICS_OVERVIEW, "analyticsOverview")
        val daily = read(AnalyticsDocuments.ANALYTICS_DAILY_STATS, "analyticsDailyStats")
        val rows = read(AnalyticsDocuments.ANALYTICS_DIMENSION_STATS, "analyticsDimensionStats", buildJsonObject { put("filter", filter.json()); put("dimension", dimension.wire) })
        AnalyticsBundle(metadata, overview, rowsOrEmpty(daily), rowsOrEmpty(rows))
    }
    suspend fun statistic(metric: AnalyticsMetric, window: AnalyticsWindow = AnalyticsWindow.DAY, limit: Int? = null, fence: TargetFence = repository.currentFence()): JsonElement = repository.fenced { session, project ->
        repository.verify(fence)
        val data = repository.api.graphQl(session, metric.document, metric.variables(window, limit), project)
        repository.verify(fence)
        sanitizeAnalytics(data[metric.root] ?: throw AxonException.InvalidResponse, session.token)
    }
    suspend fun dashboard(metric: AnalyticsMetric, window: AnalyticsWindow = AnalyticsWindow.DAY, limit: Int? = null, fence: TargetFence = repository.currentFence()): AnalyticsDashboard = repository.fenced { session, project ->
        suspend fun read(document: String, root: String, vars: JsonObject = buildJsonObject {}): JsonElement {
            repository.verify(fence)
            val data = repository.api.graphQl(session, document, vars, project)
            repository.verify(fence)
            return sanitizeAnalytics(data[root] ?: throw AxonException.InvalidResponse, session.token)
        }
        val overview = read(AnalyticsDocuments.DASHBOARD_STATS, "dashboardOverview")
        val tokens = read(AnalyticsDocuments.TOKEN_STATS, "tokenStats")
        val rows = rowsOrEmpty(read(metric.document, metric.root, metric.variables(window, limit)))
        val metricRows = if (metric == AnalyticsMetric.CHANNEL_SUCCESS) {
            val channelTokens = rowsOrEmpty(read(AnalyticsDocuments.TOKENS_BY_CHANNEL, "tokenStatsByChannel", AnalyticsMetric.TOKEN_CHANNEL.variables(window)))
            mergeChannelHealthTokens(rows, channelTokens)
        } else rows
        AnalyticsDashboard(overview, tokens, metricRows)
    }
    suspend fun resources(kind: AnalyticsResource, first: Int = 25, after: String? = null, fence: TargetFence = repository.currentFence()): Page<JsonObject> = repository.fenced { session, project ->
        repository.verify(fence)
        val data = repository.api.graphQl(session, kind.document, kind.variables(first, after), project)
        repository.verify(fence)
        parseAnalyticsResourcePage(data[kind.root] ?: throw AxonException.InvalidResponse, session.token)
    }
}

internal fun rowsOrEmpty(value: JsonElement): List<JsonObject> {
    if (value is JsonNull) return emptyList()
    if (value !is JsonArray || value.any { it !is JsonObject }) throw AxonException.InvalidResponse
    return value.map { it as JsonObject }
}

internal fun parseAnalyticsResourcePage(value: JsonElement, credential: String = ""): Page<JsonObject> {
    if (value !is JsonObject || value["edges"] !is JsonArray || value["pageInfo"] !is JsonObject) throw AxonException.InvalidResponse
    val info = value["pageInfo"]
    val next = info["hasNextPage"].boolOrNull ?: throw AxonException.InvalidResponse
    val cursor = info["endCursor"].text.takeIf(String::isNotBlank)
    if (next && cursor == null) throw AxonException.InvalidResponse
    val rows = value["edges"].arr.map { edge ->
        val node = edge["node"]
        if (node !is JsonObject || node["id"].text.isBlank()) throw AxonException.InvalidResponse
        sanitizeAnalytics(node, credential).obj
    }
    return Page(rows, cursor.takeIf { next }, value["totalCount"].intOrNull)
}

/** Allow schema API-key *names* and identifiers, but never raw keys. Keep real numeric token metrics. */
internal fun sanitizeAnalytics(value: JsonElement, credential: String = ""): JsonElement = when (value) {
    is JsonArray -> JsonArray(value.map { sanitizeAnalytics(it, credential) })
    is JsonObject -> buildJsonObject {
        value.forEach { (key, item) ->
            if (key in setOf("apiKeyName", "apiKeyId", "apiKeyID", "apiKeyIDs")) put(key, sanitizeAnalytics(item, credential))
            else if (item is JsonObject || item is JsonArray) put(key, sanitizeObservability(buildJsonObject { put(key, sanitizeAnalytics(item, credential)) }, credential)[key])
            else put(key, sanitizeObservability(buildJsonObject { put(key, item) }, credential)[key])
        }
    }
    else -> sanitizeObservability(value, credential)
}

/** Exact fixed documents copied from ObservabilityAPI.swift; raw Kotlin strings escape every GraphQL dollar. */
object AnalyticsDocuments {
    const val ANALYTICS_METADATA = """query ObserveAnalyticsMetadata {
            analyticsMetadata {
              earliestDate
            }
          }"""
    const val ANALYTICS_OVERVIEW = """query ObserveAnalyticsOverview(${'$'}filter: AnalyticsFilter) {
            analyticsOverview(filter: ${'$'}filter) {
              totalTokens
              totalInputTokens
              totalCachedInputTokens
              totalUncachedInputTokens
              totalOutputTokens
              totalRequests
              totalCost
            }
          }"""
    const val ANALYTICS_DAILY_STATS = """query ObserveAnalyticsDailyStats(${'$'}filter: AnalyticsFilter) {
            analyticsDailyStats(filter: ${'$'}filter) {
              date
              inputTokens
              cachedInputTokens
              uncachedInputTokens
              outputTokens
              totalTokens
              requestCount
              cost
            }
          }"""
    const val ANALYTICS_DIMENSION_STATS = """query ObserveAnalyticsDimensionStats(${'$'}filter: AnalyticsFilter, ${'$'}dimension: String!) {
            analyticsDimensionStats(filter: ${'$'}filter, dimension: ${'$'}dimension) {
              id
              name
              requestCount
              inputTokens
              cachedInputTokens
              outputTokens
              totalTokens
              cost
            }
          }"""
    const val DASHBOARD_STATS = """query ObserveDashboardStats {
            dashboardOverview {
              totalRequests
              requestStats {
                requestsToday
                requestsThisWeek
                requestsLastWeek
                requestsThisMonth
              }
              failedRequests
              averageResponseTime
            }
          }"""
    const val REQUESTS_BY_CHANNEL = """query ObserveRequestsByChannel(${'$'}timeWindow: String) {
            requestStatsByChannel(timeWindow: ${'$'}timeWindow) {
              channelName
              count
            }
          }"""
    const val REQUESTS_BY_MODEL = """query ObserveRequestsByModel(${'$'}timeWindow: String) {
            requestStatsByModel(timeWindow: ${'$'}timeWindow) {
              modelId
              count
            }
          }"""
    const val REQUESTS_BY_API_KEY = """query ObserveRequestsByAPIKey(${'$'}timeWindow: String) {
            requestStatsByAPIKey(timeWindow: ${'$'}timeWindow) {
              apiKeyId
              apiKeyName
              count
            }
          }"""
    const val TOKENS_BY_API_KEY = """query ObserveTokensByAPIKey(${'$'}timeWindow: String) {
            tokenStatsByAPIKey(timeWindow: ${'$'}timeWindow) {
              apiKeyId
              apiKeyName
              inputTokens
              outputTokens
              cachedTokens
              reasoningTokens
              totalTokens
            }
          }"""
    const val TOKENS_BY_CHANNEL = """query ObserveTokensByChannel(${'$'}timeWindow: String) {
            tokenStatsByChannel(timeWindow: ${'$'}timeWindow) {
              channelId
              channelName
              inputTokens
              outputTokens
              cachedTokens
              reasoningTokens
              totalTokens
            }
          }"""
    const val TOKENS_BY_MODEL = """query ObserveTokensByModel(${'$'}timeWindow: String) {
            tokenStatsByModel(timeWindow: ${'$'}timeWindow) {
              modelId
              inputTokens
              outputTokens
              cachedTokens
              reasoningTokens
              totalTokens
            }
          }"""
    const val COST_BY_CHANNEL = """query ObserveCostByChannel(${'$'}timeWindow: String) {
            costStatsByChannel(timeWindow: ${'$'}timeWindow) {
              channelName
              cost
            }
          }"""
    const val COST_BY_MODEL = """query ObserveCostByModel(${'$'}timeWindow: String) {
            costStatsByModel(timeWindow: ${'$'}timeWindow) {
              modelId
              cost
            }
          }"""
    const val COST_BY_API_KEY = """query ObserveCostByAPIKey(${'$'}timeWindow: String) {
            costStatsByAPIKey(timeWindow: ${'$'}timeWindow) {
              apiKeyId
              apiKeyName
              cost
            }
          }"""
    const val DAILY_REQUEST_STATS = """query ObserveDailyRequestStats {
            dailyRequestStats {
              date
              count
              tokens
              cost
            }
          }"""
    const val TOP_PROJECTS = """query ObserveTopProjects {
            topRequestsProjects {
              projectId
              projectName
              projectDescription
              requestCount
            }
          }"""
    const val CHANNEL_SUCCESS_RATES = """query ObserveChannelSuccessRates(${'$'}timeWindow: String, ${'$'}limit: Int) {
            channelSuccessRates(timeWindow: ${'$'}timeWindow, limit: ${'$'}limit) {
              channelId
              channelName
              channelType
              channelDisabled
              successCount
              failedCount
              totalCount
              successRate
            }
          }"""
    const val USAGE_STATS_BY_USER = """query ObserveUsageStatsByUser(${'$'}timeWindow: String) {
            usageStatsByUser(timeWindow: ${'$'}timeWindow) {
              userId
              userName
              requestCount
              totalTokens
              totalCost
            }
          }"""
    const val MODEL_PERFORMANCE_STATS = """query ObserveModelPerformanceStats {
            modelPerformanceStats {
              date
              modelId
              throughput
              ttftMs
              requestCount
            }
          }"""
    const val CHANNEL_PERFORMANCE_STATS = """query ObserveChannelPerformanceStats {
            channelPerformanceStats {
              date
              channelId
              channelName
              throughput
              ttftMs
              requestCount
            }
          }"""
    const val TOKEN_STATS = """query ObserveTokenStats {
            tokenStats {
              totalInputTokensToday
              totalOutputTokensToday
              totalCachedTokensToday
              totalInputTokensThisWeek
              totalOutputTokensThisWeek
              totalCachedTokensThisWeek
              totalInputTokensThisMonth
              totalOutputTokensThisMonth
              totalCachedTokensThisMonth
              totalInputTokensAllTime
              totalOutputTokensAllTime
              totalCachedTokensAllTime
              lastUpdated
            }
          }"""
    const val FASTEST_CHANNELS = """query ObserveFastestChannels(${'$'}input: FastestChannelsInput!) {
            fastestChannels(input: ${'$'}input) {
              channelId
              channelName
              channelType
              throughput
              tokensCount
              latencyMs
              requestCount
            }
          }"""
    const val FASTEST_MODELS = """query ObserveFastestModels(${'$'}input: FastestChannelsInput!) {
            fastestModels(input: ${'$'}input) {
              modelId
              modelName
              throughput
              tokensCount
              latencyMs
              requestCount
            }
          }"""
    const val PROJECTS = """query AnalyticsSelectProjects(${'$'}first: Int!, ${'$'}after: Cursor) { projects(first: ${'$'}first, after: ${'$'}after) { edges { cursor node { id name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"""
    const val CHANNELS = """query AnalyticsSelectChannels(${'$'}first: Int!, ${'$'}after: Cursor) { channels(first: ${'$'}first, after: ${'$'}after) { edges { cursor node { id name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"""
    const val MODELS = """query AnalyticsSelectModels(${'$'}first: Int!, ${'$'}after: Cursor) { models(first: ${'$'}first, after: ${'$'}after) { edges { cursor node { id name modelID } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"""
    const val API_KEYS = """query AnalyticsSelectApiKeys(${'$'}first: Int!, ${'$'}after: Cursor) { apiKeys(first: ${'$'}first, after: ${'$'}after) { edges { cursor node { id name } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"""
    const val USERS = """query AnalyticsSelectUsers(${'$'}first: Int!, ${'$'}after: Cursor) { users(first: ${'$'}first, after: ${'$'}after) { edges { cursor node { id firstName lastName email } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"""
}
