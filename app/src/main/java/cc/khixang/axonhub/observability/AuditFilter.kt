package cc.khixang.axonhub.observability

import cc.khixang.axonhub.core.*
import kotlinx.serialization.json.*
import java.time.OffsetDateTime

/** Server-side audit filters copied from ObservabilityFilters.whereInput. */
data class AuditFilter(
    val search: String = "", val statuses: Set<String> = emptySet(), val sources: Set<String> = emptySet(),
    val projects: String = "", val channels: String = "", val apiKeys: String = "",
    val format: String = "", val clientIP: String = "", val externalID: String = "", val stream: String = "all",
    val start: String? = null, val end: String? = null,
) {
    fun json(kind: ObserveKind): JsonObject = buildJsonObject {
        val startDate = start?.let(OffsetDateTime::parse); val endDate = end?.let(OffsetDateTime::parse)
        require(startDate == null || endDate == null || !startDate.toInstant().isAfter(endDate.toInstant()))
        start?.let { put("createdAtGTE", it) }; end?.let { put("createdAtLTE", it) }
        require(statuses.all { it in auditStatuses(kind) })
        if (statuses.isNotEmpty()) put("statusIn", JsonArray(statuses.sorted().map(::JsonPrimitive)))
        val projectIDs = AnalyticsFilter.identifiers(projects)
        if (projectIDs.isNotEmpty()) put("projectIDIn", JsonArray(projectIDs.map(::JsonPrimitive)))
        if (kind == ObserveKind.REQUESTS || kind == ObserveKind.USAGE) {
            if (search.isNotBlank()) put("modelIDContainsFold", search)
            val channelIDs = AnalyticsFilter.identifiers(channels)
            if (channelIDs.isNotEmpty()) put("channelIDIn", JsonArray(channelIDs.map(::JsonPrimitive)))
            val keyIDs = AnalyticsFilter.identifiers(apiKeys)
            if (keyIDs.isNotEmpty()) put("apiKeyIDIn", JsonArray(keyIDs.map { if (kind == ObserveKind.USAGE) {
                require(it.matches(Regex("[+-]?[0-9]+"))) { "Usage API-key IDs must be integers" }
                JsonPrimitive(it.toInt())
            } else JsonPrimitive(it) }))
            require(sources.all { it in setOf("api", "playground", "test") })
            if (sources.isNotEmpty()) put("sourceIn", JsonArray(sources.sorted().map(::JsonPrimitive)))
            if (format.isNotBlank()) put("formatContainsFold", format)
        } else if (search.isNotBlank()) put(if (kind == ObserveKind.TRACES) "traceIDContainsFold" else "threadIDContainsFold", search)
        if (kind == ObserveKind.REQUESTS) {
            if (clientIP.isNotBlank()) put("clientIPContains", clientIP)
            if (externalID.isNotBlank()) put("externalIDContains", externalID)
            require(stream in setOf("all", "yes", "no"))
            if (stream != "all") put("stream", stream == "yes")
        }
    }
}

fun auditStatuses(kind: ObserveKind): List<String> = when (kind) {
    ObserveKind.REQUESTS -> listOf("pending", "processing", "completed", "failed", "canceled")
    ObserveKind.TRACES, ObserveKind.THREADS -> listOf("active", "archived", "retained")
    ObserveKind.USAGE -> emptyList()
}

internal fun parseAuditPage(value: JsonElement?, credential: String = ""): Page<JsonObject> {
    if (value !is JsonObject || value["edges"] !is JsonArray || value["pageInfo"] !is JsonObject) throw cc.khixang.axonhub.network.AxonException.InvalidResponse
    val hasNext = value["pageInfo"]["hasNextPage"].boolOrNull ?: throw cc.khixang.axonhub.network.AxonException.InvalidResponse
    val cursor = value["pageInfo"]["endCursor"].text.takeIf(String::isNotBlank)
    if (hasNext && cursor == null) throw cc.khixang.axonhub.network.AxonException.InvalidResponse
    val rows = value["edges"].arr.map { edge ->
        val node = edge["node"]
        if (node !is JsonObject || node["id"].text.isBlank()) throw cc.khixang.axonhub.network.AxonException.InvalidResponse
        sanitizeObservability(node, credential).obj
    }
    return Page(rows, cursor.takeIf { hasNext }, value["totalCount"].intOrNull)
}
