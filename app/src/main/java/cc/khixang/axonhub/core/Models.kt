package cc.khixang.axonhub.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.UUID

@Serializable
enum class AuthType { ADMIN, API_KEY }

@Serializable
data class AxonInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val allowHttp: Boolean = false,
    val authType: AuthType = AuthType.ADMIN,
    val adminEmail: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ChannelItem(
    val id: String, val name: String, val type: String, val baseUrl: String? = null,
    val status: String, val supportedModels: List<String> = emptyList(), val orderingWeight: Int = 0,
    val errorMessage: String? = null, val autoDisabledAt: String? = null,
    val tags: List<String> = emptyList(), val remark: String? = null,
) { val enabled get() = status.equals("enabled", true) }

@Serializable
data class ModelItem(
    val id: String, val modelId: String, val name: String, val developer: String = "",
    val type: String = "", val group: String = "", val icon: String? = null,
    val status: String = "", val remark: String? = null,
) { val enabled get() = status.equals("enabled", true) }

@Serializable
data class RequestItem(
    val id: String, val createdAt: String, val modelId: String, val source: String,
    val format: String, val status: String, val stream: Boolean, val clientIp: String,
    val latencyMs: Int? = null, val firstTokenLatencyMs: Int? = null,
    val reasoningDurationMs: Int? = null, val errorMessage: String? = null,
)

@Serializable
data class ApiKeyItem(
    val id: String, val name: String, val type: String, val status: String,
    val scopes: List<String> = emptyList(), val createdAt: String = "",
)

@Serializable
data class DashboardStats(
    val totalRequests: Long? = null, val failedRequests: Long? = null,
    val averageResponseTime: Double? = null, val requestsToday: Long? = null,
    val requestsThisWeek: Long? = null, val requestsThisMonth: Long? = null,
    val inputTokensToday: Long? = null, val outputTokensToday: Long? = null,
    val cachedTokensToday: Long? = null, val inputTokensMonth: Long? = null,
    val outputTokensMonth: Long? = null, val cachedTokensMonth: Long? = null,
    val inputTokensAll: Long? = null, val outputTokensAll: Long? = null,
    val cachedTokensAll: Long? = null, val totalCost: Double? = null,
)

@Serializable data class DailyStat(val date: String, val requests: Long? = null, val tokens: Long? = null, val cost: Double? = null)

@Serializable
data class ChannelPerformance(
    val channelId: String = "", val name: String, val type: String = "",
    val disabled: Boolean? = null, val success: Long? = null, val failed: Long? = null,
    val total: Long? = null, val successRate: Double? = null,
)

@Serializable
data class Snapshot(
    val dashboard: DashboardStats = DashboardStats(),
    val channels: List<ChannelItem> = emptyList(),
    val models: List<ModelItem> = emptyList(),
    val requests: List<RequestItem> = emptyList(),
    val apiKeys: List<ApiKeyItem> = emptyList(),
    val daily: List<DailyStat> = emptyList(),
    val channelPerformance: List<ChannelPerformance> = emptyList(),
)

data class Page<T>(val items: List<T>, val endCursor: String?, val total: Int?)
data class TargetFence(val instanceId: String, val projectId: String?, val generation: Long)

sealed interface LoadState<out T> {
    data object Idle : LoadState<Nothing>
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T, val updatedAt: Long = System.currentTimeMillis()) : LoadState<T>
    data class Failed(val message: String) : LoadState<Nothing>
}

data class ManagementRecord(val id: String, val value: JsonElement = JsonNull)
