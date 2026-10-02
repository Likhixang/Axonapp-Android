package cc.khixang.axonhub.observability

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.network.AxonException
import kotlinx.serialization.json.*

enum class ObserveKind(val root: String) { REQUESTS("requests"), TRACES("traces"), THREADS("threads"), USAGE("usageLogs") }

class ObservabilityService(private val repository: AxonRepository) {
    suspend fun page(kind: ObserveKind, first: Int = 25, after: String? = null, where: JsonObject = buildJsonObject {}): Page<JsonObject> = repository.fenced { session, project ->
        val vars = buildJsonObject {
            put("first", first.coerceIn(1, 100)); after?.let { put("after", it) }; put("where", where)
            put("order", buildJsonObject { put("field", "CREATED_AT"); put("direction", "DESC") })
        }
        val data = repository.api.graphQl(session, listDocument(kind), vars, project)[kind.root]
        Page(data["edges"].arr.map { sanitize(it["node"], session.token).obj }, data["pageInfo"]["endCursor"].text.takeIf { data["pageInfo"]["hasNextPage"].boolOrNull == true }, data["totalCount"].intOrNull)
    }

    suspend fun detail(kind: ObserveKind, id: String): JsonElement = repository.fenced { session, project ->
        val data = repository.api.graphQl(session, detailDocument(kind), buildJsonObject { put("id", id) }, project)["node"]
        if (data["id"].text != id) throw AxonException.InvalidResponse
        sanitize(data ?: JsonNull, session.token)
    }

    suspend fun content(kind: ObserveKind, id: String): JsonElement = repository.fenced { session, project ->
        if (kind !in setOf(ObserveKind.REQUESTS, ObserveKind.TRACES)) throw IllegalArgumentException("No content for this kind")
        val doc = if (kind == ObserveKind.REQUESTS) REQUEST_CONTENT else TRACE_CONTENT
        val data = repository.api.graphQl(session, doc, buildJsonObject { put("id", id) }, project)["node"]
        if (data["id"].text != id) throw AxonException.InvalidResponse
        sanitize(data ?: JsonNull, session.token)
    }

    suspend fun related(parent: ObserveKind, id: String, relation: String, first: Int = 25, after: String? = null): Page<JsonObject> = repository.fenced { session, project ->
        val document = when (parent to relation) {
            ObserveKind.REQUESTS to "executions" -> EXECUTIONS
            ObserveKind.REQUESTS to "usageLogs" -> REQUEST_USAGE
            ObserveKind.TRACES to "requests" -> TRACE_REQUESTS
            ObserveKind.THREADS to "traces" -> THREAD_TRACES
            else -> throw IllegalArgumentException("Unsupported relation")
        }
        val vars = buildJsonObject { put("id", id); put("first", first); after?.let { put("after", it) }; put("where", buildJsonObject {}) }
        val node = repository.api.graphQl(session, document, vars, project)["node"]
        val data = node[relation]
        Page(data["edges"].arr.map { sanitize(it["node"], session.token).obj }, data["pageInfo"]["endCursor"].text.takeIf { data["pageInfo"]["hasNextPage"].boolOrNull == true }, data["totalCount"].intOrNull)
    }

    suspend fun executionContent(id: String): JsonElement = repository.fenced { session, project ->
        sanitize(repository.api.graphQl(session, EXECUTION_CONTENT, buildJsonObject { put("id", id) }, project)["node"] ?: JsonNull, session.token)
    }

    suspend fun changeRetention(kind: ObserveKind, id: String, action: String): JsonElement = repository.fencedMutation { session, project ->
        if (kind !in setOf(ObserveKind.TRACES, ObserveKind.THREADS) || action !in setOf("archive", "unarchive", "retain", "unretain")) throw IllegalArgumentException("Unsupported action")
        val (root, document) = when (kind to action) {
            ObserveKind.TRACES to "archive" -> "archiveTrace" to ARCHIVE_TRACE
            ObserveKind.TRACES to "unarchive" -> "unarchiveTrace" to UNARCHIVE_TRACE
            ObserveKind.TRACES to "retain" -> "retainTrace" to RETAIN_TRACE
            ObserveKind.TRACES to "unretain" -> "unretainTrace" to UNRETAIN_TRACE
            ObserveKind.THREADS to "archive" -> "archiveThread" to ARCHIVE_THREAD
            ObserveKind.THREADS to "unarchive" -> "unarchiveThread" to UNARCHIVE_THREAD
            ObserveKind.THREADS to "retain" -> "retainThread" to RETAIN_THREAD
            ObserveKind.THREADS to "unretain" -> "unretainThread" to UNRETAIN_THREAD
            else -> throw IllegalArgumentException("Unsupported action")
        }
        if (repository.api.graphQl(session, document, buildJsonObject { put("id", id) }, project)[root].boolOrNull != true) throw AxonException.VerificationFailed
        val actual = repository.api.graphQl(session, detailDocument(kind), buildJsonObject { put("id", id) }, project)["node"]
        val expected = when (action) { "archive" -> "archived"; "retain" -> "retained"; else -> "active" }
        if (actual["id"].text != id || actual["status"].text != expected) throw AxonException.VerificationFailed
        sanitize(actual ?: JsonNull, session.token)
    }

    fun sanitize(value: JsonElement, credential: String = ""): JsonElement = sanitizeObservability(value, credential)

    private fun listDocument(kind: ObserveKind) = when (kind) { ObserveKind.REQUESTS -> REQUESTS; ObserveKind.TRACES -> TRACES; ObserveKind.THREADS -> THREADS; ObserveKind.USAGE -> USAGE }
    private fun detailDocument(kind: ObserveKind) = when (kind) { ObserveKind.REQUESTS -> REQUEST_DETAIL; ObserveKind.TRACES -> TRACE_DETAIL; ObserveKind.THREADS -> THREAD_DETAIL; ObserveKind.USAGE -> USAGE_DETAIL }

    companion object {
        const val REQUESTS = """query ObserveRequestPage(${ '$' }first: Int!, ${ '$' }after: Cursor, ${ '$' }where: RequestWhereInput, ${ '$' }order: RequestOrder) { requests(first: ${ '$' }first, after: ${ '$' }after, where: ${ '$' }where, orderBy: ${ '$' }order) { edges { cursor node { id createdAt updatedAt projectID traceID channelID apiKeyID source modelID reasoningEffort format status stream clientIP metricsLatencyMs metricsFirstTokenLatencyMs metricsReasoningDurationMs contentSaved } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"""
        const val TRACES = """query ObserveTracePage(${ '$' }first: Int!, ${ '$' }after: Cursor, ${ '$' }where: TraceWhereInput, ${ '$' }order: TraceOrder) { traces(first: ${ '$' }first, after: ${ '$' }after, where: ${ '$' }where, orderBy: ${ '$' }order) { edges { cursor node { id traceID threadID projectID status createdAt updatedAt firstUserQuery firstText usageMetadata { totalInputTokens totalOutputTokens totalTokens totalCachedTokens totalCachedWriteTokens totalCost } } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"""
        const val THREADS = """query ObserveThreadPage(${ '$' }first: Int!, ${ '$' }after: Cursor, ${ '$' }where: ThreadWhereInput, ${ '$' }order: ThreadOrder) { threads(first: ${ '$' }first, after: ${ '$' }after, where: ${ '$' }where, orderBy: ${ '$' }order) { edges { cursor node { id threadID projectID status createdAt updatedAt firstUserQuery archivedTracesCount usageMetadata { totalInputTokens totalOutputTokens totalTokens totalCachedTokens totalCachedWriteTokens totalCost } } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"""
        const val USAGE = """query ObserveUsageLogPage(${ '$' }first: Int!, ${ '$' }after: Cursor, ${ '$' }where: UsageLogWhereInput, ${ '$' }order: UsageLogOrder) { usageLogs(first: ${ '$' }first, after: ${ '$' }after, where: ${ '$' }where, orderBy: ${ '$' }order) { edges { cursor node { id createdAt requestID projectID channelID apiKeyID modelID promptTokens completionTokens totalTokens promptAudioTokens promptCachedTokens promptWriteCachedTokens completionReasoningTokens source format totalCost costItems { itemCode quantity subtotal } } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"""
        const val REQUEST_DETAIL = "query ObserveRequestDetail(${'$'}id: ID!) { node(id: ${'$'}id) { ... on Request { id createdAt updatedAt projectID traceID channelID apiKeyID source modelID reasoningEffort format status stream clientIP metricsLatencyMs metricsFirstTokenLatencyMs metricsReasoningDurationMs contentSaved } } }"
        const val TRACE_DETAIL = "query ObserveTraceDetail(${'$'}id: ID!) { node(id: ${'$'}id) { ... on Trace { id traceID threadID projectID status createdAt updatedAt firstUserQuery firstText usageMetadata { totalInputTokens totalOutputTokens totalTokens totalCachedTokens totalCachedWriteTokens totalCost } } } }"
        const val THREAD_DETAIL = "query ObserveThreadDetail(${'$'}id: ID!) { node(id: ${'$'}id) { ... on Thread { id threadID projectID status createdAt updatedAt firstUserQuery archivedTracesCount usageMetadata { totalInputTokens totalOutputTokens totalTokens totalCachedTokens totalCachedWriteTokens totalCost } } } }"
        const val USAGE_DETAIL = "query ObserveUsageLogDetail(${'$'}id: ID!) { node(id: ${'$'}id) { ... on UsageLog { id createdAt requestID projectID channelID apiKeyID modelID promptTokens completionTokens totalTokens promptAudioTokens promptCachedTokens promptWriteCachedTokens completionReasoningTokens source format totalCost costItems { itemCode quantity subtotal } } } }"
        const val REQUEST_CONTENT = "query ObserveRequestContent(${'$'}id: ID!) { node(id: ${'$'}id) { ... on Request { id requestHeaders requestBody responseBody responseChunks } } }"
        const val EXECUTION_CONTENT = "query ObserveRequestExecutionContent(${'$'}id: ID!) { node(id: ${'$'}id) { ... on RequestExecution { id requestID requestHeaders requestBody responseBody responseChunks errorMessage requestURL responseStatusCode } } }"
        const val TRACE_CONTENT = "query ObserveTraceContent(${'$'}id: ID!) { node(id: ${'$'}id) { ... on Trace { id rawRootSegment } } }"
        const val EXECUTIONS = "query ObserveExecutions(${'$'}id: ID!, ${'$'}first: Int!, ${'$'}after: Cursor, ${'$'}where: RequestExecutionWhereInput) { node(id: ${'$'}id) { ... on Request { id executions(first: ${'$'}first, after: ${'$'}after, where: ${'$'}where, orderBy: {field: CREATED_AT, direction: ASC}) { edges { cursor node { id createdAt updatedAt requestID channelID projectID modelID format reasoningEffort status stream responseStatusCode passThroughApplied metricsLatencyMs metricsFirstTokenLatencyMs metricsReasoningDurationMs } } pageInfo { hasNextPage endCursor } totalCount } } } }"
        const val REQUEST_USAGE = "query ObserveRequestUsage(${'$'}id: ID!, ${'$'}first: Int!, ${'$'}after: Cursor) { node(id: ${'$'}id) { ... on Request { id usageLogs(first: ${'$'}first, after: ${'$'}after, orderBy: {field: CREATED_AT, direction: ASC}) { edges { cursor node { id createdAt requestID projectID channelID apiKeyID modelID promptTokens completionTokens totalTokens source format totalCost } } pageInfo { hasNextPage endCursor } totalCount } } } }"
        const val TRACE_REQUESTS = "query ObserveTraceRequests(${'$'}id: ID!, ${'$'}first: Int!, ${'$'}after: Cursor, ${'$'}where: RequestWhereInput) { node(id: ${'$'}id) { ... on Trace { id requests(first: ${'$'}first, after: ${'$'}after, where: ${'$'}where, orderBy: {field: CREATED_AT, direction: ASC}) { edges { cursor node { id createdAt projectID traceID channelID apiKeyID source modelID format status stream clientIP } } pageInfo { hasNextPage endCursor } totalCount } } } }"
        const val THREAD_TRACES = "query ObserveThreadTraces(${'$'}id: ID!, ${'$'}first: Int!, ${'$'}after: Cursor, ${'$'}where: TraceWhereInput) { node(id: ${'$'}id) { ... on Thread { id traces(first: ${'$'}first, after: ${'$'}after, where: ${'$'}where, orderBy: {field: CREATED_AT, direction: ASC}) { edges { cursor node { id traceID threadID projectID status createdAt firstUserQuery firstText usageMetadata { totalTokens totalCost } } } pageInfo { hasNextPage endCursor } totalCount } } } }"
        const val ARCHIVE_TRACE = "mutation ObserveArchiveTrace(${'$'}id: ID!) { archiveTrace(id: ${'$'}id) }"
        const val UNARCHIVE_TRACE = "mutation ObserveUnarchiveTrace(${'$'}id: ID!) { unarchiveTrace(id: ${'$'}id) }"
        const val RETAIN_TRACE = "mutation ObserveRetainTrace(${'$'}id: ID!) { retainTrace(id: ${'$'}id) }"
        const val UNRETAIN_TRACE = "mutation ObserveUnretainTrace(${'$'}id: ID!) { unretainTrace(id: ${'$'}id) }"
        const val ARCHIVE_THREAD = "mutation ObserveArchiveThread(${'$'}id: ID!) { archiveThread(id: ${'$'}id) }"
        const val UNARCHIVE_THREAD = "mutation ObserveUnarchiveThread(${'$'}id: ID!) { unarchiveThread(id: ${'$'}id) }"
        const val RETAIN_THREAD = "mutation ObserveRetainThread(${'$'}id: ID!) { retainThread(id: ${'$'}id) }"
        const val UNRETAIN_THREAD = "mutation ObserveUnretainThread(${'$'}id: ID!) { unretainThread(id: ${'$'}id) }"
    }
}

internal fun sanitizeObservability(value: JsonElement, credential: String = ""): JsonElement = when (value) {
    is JsonObject -> {
        if ("key" in value && "value" in value) buildJsonObject { put("key", sanitizeObservability(value["key"] ?: JsonNull, credential)); put("value", "[REDACTED]") }
        else buildJsonObject { value.forEach { (key, item) ->
            val normalized = key.lowercase().filter(Char::isLetterOrDigit)
            val secret = normalized !in setOf("apikeyid", "apikeyids") && (normalized in setOf("requestheaders", "responseheaders", "headers", "authorization", "proxyauthorization", "cookie", "setcookie", "token", "auth", "authentication") || listOf("password", "secret", "credential", "apikey", "accesstoken", "refreshtoken").any(normalized::contains))
            put(key, if (secret) JsonPrimitive("[REDACTED]") else sanitizeObservability(item, credential))
        } }
    }
    is JsonArray -> JsonArray(value.map { sanitizeObservability(it, credential) })
    is JsonPrimitive -> if (value.isString) JsonPrimitive(redactObservabilityText(value.content, credential)) else value
    else -> value
}

private fun redactObservabilityText(input: String, credential: String): String {
    val trimmed = input.trim()
    if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
        runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()?.let { parsed ->
            if (parsed is JsonObject || parsed is JsonArray) return sanitizeObservability(parsed, credential).toString()
        }
    }
    var text = if (credential.isNotEmpty()) input.replace(credential, "[REDACTED]") else input
    runCatching { java.net.URI(text) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }?.let { uri ->
        text = java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
    }
    listOf(
        Regex("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+"),
        Regex("(?i)(?:authorization|cookie|x-api-key|api[-_]?key|password|secret|access[-_]?token|refresh[-_]?token)\\s*[=:]\\s*[^\\r\\n,;]+"),
        Regex("\\bsk-[A-Za-z0-9_-]{8,}"), Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
    ).forEach { text = it.replace(text, "[REDACTED]") }
    return text
}
