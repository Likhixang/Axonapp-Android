package cc.khixang.axonhub.gateway

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.network.AxonException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

class GatewayService(private val repository: AxonRepository) {
    private suspend fun <T> fencedMutation(block: suspend (cc.khixang.axonhub.network.AxonSession, String?) -> T): T {
        val fence = repository.currentFence()
        return repository.fencedMutation { session, project -> repository.verify(fence); block(session, project) }
    }
    suspend fun channelDetail(id: String): JsonElement = repository.fenced { session, project -> channelDetail(session, project, id) }
    suspend fun channelSecrets(id: String): JsonElement = repository.fenced { session, project ->
        val data = repository.api.graphQl(session, CHANNEL_SECRETS, buildJsonObject { put("id", id) }, project)
        val node = data["channels"]["edges"].arr.firstOrNull()?.get("node")?.takeIf { it["id"].text == id } ?: throw AxonException.InvalidResponse
        if (node["credentials"] !is JsonObject || node["disabledAPIKeys"] !is JsonArray) throw AxonException.Forbidden
        node
    }
    suspend fun modelDetail(id: String): JsonElement = repository.fenced { session, project ->
        modelDetail(session, project, id)
    }

    private suspend fun modelDetail(session: cc.khixang.axonhub.network.AxonSession, project: String?, id: String): JsonElement {
        var document = MODEL_DETAIL
        repeat(256) {
            val node = repository.api.graphQl(session, document, buildJsonObject { put("id", id) }, project)["models"]["edges"].arr.firstOrNull()?.get("node") ?: return JsonNull
            if (!hasTruncatedCondition(node["settings"])) return node
            document = document.replace("conditions { type }", "conditions { type logic field operator value conditions { type } }")
        }
        throw AxonException.InvalidResponse
    }

    suspend fun saveChannel(id: String?, draft: JsonObject, baseline: JsonElement = JsonNull, credentialsAuthorized: Boolean = false): String = fencedMutation { session, project ->
        rejectGatewayMasked(draft)
        if (id != null) ensureFreshChannel(session, project, id, baseline)
        val input = if (id == null) draft else dirtyPatch(draft, baseline.obj).toMutableMap().also { patch ->
            if (("credentials" in patch || "settings" in patch) && !credentialsAuthorized) throw IllegalArgumentException("Authorize a full configuration read before replacing credentials or settings")
            patch["credentials"]?.let { rejectMasked(it, sensitive = true) }
        }.let(::JsonObject)
        if (input.isEmpty()) return@fencedMutation id ?: throw IllegalArgumentException("No channel changes")
        val doc = if (id == null) CREATE_CHANNEL else UPDATE_CHANNEL
        val vars = buildJsonObject { id?.let { put("id", it) }; put("input", input) }
        val root = if (id == null) "createChannel" else "updateChannel"
        val result = repository.api.graphQl(session, doc, vars, project)[root]
        val resultId = result["id"].text.ifBlank { throw AxonException.VerificationFailed }
        if (id != null && resultId != id) throw AxonException.VerificationFailed
        val actual = channelDetail(session, project, resultId)
        if (actual["id"].text != resultId) throw AxonException.VerificationFailed
        verifySubset(actual, JsonObject(input.filterKeys { it != "settings" && it != "credentials" }))
        if ("settings" in input || "credentials" in input) {
            val secrets = repository.api.graphQl(session, CHANNEL_SECRETS, buildJsonObject { put("id", resultId) }, project)["channels"]["edges"].arr.firstOrNull()?.get("node") ?: throw AxonException.VerificationFailed
            if (secrets["id"].text != resultId || secrets["credentials"] !is JsonObject) throw AxonException.VerificationFailed
            input["credentials"]?.let { if (!credentialMatches(secrets["credentials"], it)) throw AxonException.VerificationFailed }
            input["settings"]?.let { if (!matches(secrets["settings"], it)) throw AxonException.VerificationFailed }
        }
        resultId
    }

    suspend fun saveModel(id: String?, draft: JsonObject, baseline: JsonElement = JsonNull): String = fencedMutation { session, project ->
        if (id != null) ensureFreshModel(session, project, id, baseline)
        val input = (if (id == null) draft else dirtyPatch(draft, baseline.obj)).toMutableMap().also { fields ->
            fields["modelCard"]?.let { fields["modelCard"] = normalizeModelCard(it.obj) }
        }.let(::JsonObject)
        if (input.isEmpty()) return@fencedMutation id ?: throw IllegalArgumentException("No model changes")
        val doc = if (id == null) CREATE_MODEL else UPDATE_MODEL
        val root = if (id == null) "createModel" else "updateModel"
        val vars = buildJsonObject { id?.let { put("id", it) }; put("input", input) }
        val resultId = repository.api.graphQl(session, doc, vars, project)[root]["id"].text.ifBlank { throw AxonException.VerificationFailed }
        if (id != null && resultId != id) throw AxonException.VerificationFailed
        val actual = modelDetail(session, project, resultId)
        if (actual["id"].text != resultId) throw AxonException.VerificationFailed
        verifySubset(actual, input)
        resultId
    }

    suspend fun deleteChannel(id: String) = delete(id, true)
    suspend fun deleteModel(id: String) = delete(id, false)
    private suspend fun delete(id: String, channel: Boolean) = fencedMutation { session, project ->
        val before = if (channel) channelDetail(session, project, id) else modelDetail(session, project, id)
        if (before["id"].text != id) throw AxonException.InvalidResponse
        val doc = if (channel) DELETE_CHANNEL else DELETE_MODEL; val root = if (channel) "deleteChannel" else "deleteModel"
        if (repository.api.graphQl(session, doc, buildJsonObject { put("id", id) }, project)[root].boolOrNull != true) throw AxonException.VerificationFailed
        val actual = if (channel) channelDetail(session, project, id) else repository.api.graphQl(session, MODEL_DETAIL, buildJsonObject { put("id", id) }, project)["models"]["edges"].arr.firstOrNull()
        if (actual !is JsonNull && actual != null) throw AxonException.VerificationFailed
    }

    suspend fun setChannelEnabled(id: String, enabled: Boolean) = status(id, enabled, true)
    suspend fun setModelEnabled(id: String, enabled: Boolean) = status(id, enabled, false)
    private suspend fun status(id: String, enabled: Boolean, channel: Boolean) = fencedMutation { session, project ->
        updateStatus(session, project, id, enabled, channel)
    }

    /** Serial, individually read-back writes, never an atomic bulk mutation. */
    suspend fun setChannelsEnabled(ids: List<String>, enabled: Boolean): GatewayBatchResult = batchStatus(ids, enabled, true)
    suspend fun setModelsEnabled(ids: List<String>, enabled: Boolean): GatewayBatchResult = batchStatus(ids, enabled, false)

    private suspend fun batchStatus(ids: List<String>, enabled: Boolean, channel: Boolean): GatewayBatchResult {
        // Capture before waiting for the mutation mutex: a queued batch must not silently retarget.
        val fence = repository.currentFence()
        return fencedMutation { session, project ->
            repository.verify(fence)
            runGatewayBatch(ids) { id ->
                repository.verify(fence)
                updateStatus(session, project, id, enabled, channel)
                repository.verify(fence)
            }
        }
    }

    private suspend fun updateStatus(session: cc.khixang.axonhub.network.AxonSession, project: String?, id: String, enabled: Boolean, channel: Boolean) {
        val expected = if (enabled) "enabled" else "disabled"
        val doc = if (channel) CHANNEL_STATUS else MODEL_STATUS; val root = if (channel) "updateChannelStatus" else "updateModelStatus"
        val result = repository.api.graphQl(session, doc, buildJsonObject { put("id", id); put("status", expected) }, project)[root]
        if (channel && (result["id"].text != id || result["status"].text != expected)) throw AxonException.VerificationFailed
        if (!channel && result.boolOrNull != true) throw AxonException.VerificationFailed
        val actual = if (channel) channelDetail(session, project, id) else modelDetail(session, project, id)
        if (actual["id"].text != id || actual["status"].text != expected) throw AxonException.VerificationFailed
    }

    suspend fun testChannel(id: String, model: String?): Pair<Boolean, Int> = fencedMutation { session, project ->
        val input = buildJsonObject { put("channelID", id); model?.takeIf(String::isNotBlank)?.let { put("modelID", it) } }
        val result = repository.api.graphQl(session, TEST_CHANNEL, buildJsonObject { put("input", input) }, project)["testChannel"]
        val success = result["success"].boolOrNull ?: throw AxonException.InvalidResponse
        val latency = result["latency"].doubleOrNull?.takeIf { it.isFinite() && it >= 0 } ?: throw AxonException.InvalidResponse
        success to (latency * 1000).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    suspend fun fetchUpstreamModels(input: JsonObject): List<String> = repository.fenced { session, project ->
        val result = repository.api.graphQl(session, FETCH_MODELS, buildJsonObject { put("input", input) }, project)["fetchModels"]
        if (result["error"].text.isNotBlank()) throw AxonException.InvalidResponse
        result["models"].arr.map { it["id"].text }.filter(String::isNotBlank)
    }

    suspend fun syncModels(id: String, pattern: String?): List<String> = fencedMutation { session, project ->
        val vars = buildJsonObject { put("id", id); pattern?.let { put("pattern", it) } }
        val result = repository.api.graphQl(session, SYNC_MODELS, vars, project)["syncChannelModels"]
        if (result["channelID"].text != id) throw AxonException.VerificationFailed
        val models = result["supportedModels"].arr.map { it.text }
        if (channelDetail(session, project, id)["supportedModels"].arr.map { it.text } != models) throw AxonException.VerificationFailed
        models
    }

    suspend fun oauth(provider: String, action: String, body: JsonObject): JsonObject = repository.fenced { session, _ ->
        repository.api.oauth(session, provider, action, body)
    }

    private suspend fun channelDetail(session: cc.khixang.axonhub.network.AxonSession, project: String?, id: String): JsonElement =
        repository.api.graphQl(session, CHANNEL_DETAIL, buildJsonObject { put("id", id) }, project)["channels"]["edges"].arr.firstOrNull()?.get("node") ?: JsonNull

    internal fun dirtyPatch(candidate: JsonObject, baseline: JsonObject): JsonObject = JsonObject(candidate.filter { (key, value) -> value != baseline[key] })
    private fun rejectMasked(value: JsonElement, sensitive: Boolean = false) {
        when (value) {
            is JsonObject -> value.forEach { (key, item) -> rejectMasked(item, sensitive || SensitiveFields.matches(key)) }
            is JsonArray -> value.forEach { rejectMasked(it, sensitive) }
            is JsonPrimitive -> if (sensitive && value.text.let { it.contains("••") || it.matches(Regex("[*•]{3,}")) }) throw IllegalArgumentException("Masked credentials cannot be submitted")
            else -> Unit
        }
    }
    private fun verifySubset(actual: JsonElement, expected: JsonObject) {
        expected.forEach { (key, value) -> if (!SensitiveFields.matches(key) && !matches(actual[key], value)) throw AxonException.VerificationFailed }
    }
    private fun matches(actual: JsonElement, expected: JsonElement): Boolean = when (expected) {
        is JsonObject -> expected.all { matches(actual[it.key], it.value) }
        is JsonArray -> actual.arr.size == expected.size && actual.arr.zip(expected).all { matches(it.first, it.second) }
        else -> gatewayMatches(actual, expected)
    }
    private fun hasTruncatedCondition(value: JsonElement): Boolean = when (value) {
        is JsonObject -> (value.size == 1 && "type" in value) || value.values.any(::hasTruncatedCondition)
        is JsonArray -> value.any(::hasTruncatedCondition)
        else -> false
    }

    private suspend fun ensureFreshChannel(session: cc.khixang.axonhub.network.AxonSession, project: String?, id: String, baseline: JsonElement) {
        val current = channelDetail(session, project, id)
        if (current["id"].text != id || baseline["updatedAt"].text.isBlank() || current["updatedAt"].text != baseline["updatedAt"].text) throw AxonException.TargetChanged
    }

    private suspend fun ensureFreshModel(session: cc.khixang.axonhub.network.AxonSession, project: String?, id: String, baseline: JsonElement) {
        val current = repository.api.graphQl(session, MODEL_DETAIL, buildJsonObject { put("id", id) }, project)["models"]["edges"].arr.firstOrNull()?.get("node") ?: throw AxonException.InvalidResponse
        if (current["id"].text != id || baseline["updatedAt"].text.isBlank() || current["updatedAt"].text != baseline["updatedAt"].text) throw AxonException.TargetChanged
    }

    private fun credentialMatches(actual: JsonElement, expected: JsonElement): Boolean {
        val normalizedActual = actual.obj.toMutableMap()
        val normalizedExpected = expected.obj.toMutableMap()
        normalizedExpected["apiKey"]?.text?.takeIf(String::isNotBlank)?.let { key ->
            val keys = normalizedActual["apiKeys"].arr.map { it.text }
            if (normalizedActual["apiKey"].text != key && key !in keys) return false
            normalizedExpected.remove("apiKey")
        }
        return normalizedExpected.all { (key, value) -> matches(normalizedActual[key] ?: JsonNull, value) }
    }

    private fun normalizeModelCard(card: JsonObject): JsonObject {
        val defaults = buildJsonObject {
            put("reasoning", buildJsonObject { put("supported", false); put("default", false) })
            put("toolCall", false); put("temperature", false); put("vision", false)
            put("modalities", buildJsonObject { put("input", buildJsonArray {}); put("output", buildJsonArray {}) })
            put("cost", buildJsonObject { put("input", 0); put("output", 0); put("cacheRead", 0); put("cacheWrite", 0) })
            put("limit", buildJsonObject { put("context", 0); put("output", 0) })
            put("knowledge", ""); put("releaseDate", ""); put("lastUpdated", "")
        }
        return buildJsonObject { defaults.forEach { (key, value) -> put(key, if (value is JsonObject && card[key] is JsonObject) JsonObject(value + card[key]!!.obj) else card[key] ?: value) } }
    }

    companion object {
        const val CHANNEL_DETAIL = """query ChannelDetail(${ '$' }id: ID!) { channels(first: 1, where: {id: ${ '$' }id}) { edges { node { id name type baseURL status supportedModels defaultTestModel tags orderingWeight remark errorMessage autoSyncSupportedModels autoSyncModelPattern manualModels updatedAt endpoints { apiFormat path baseURL transport } defaultEndpoints { apiFormat path baseURL transport } policies { stream apiKeyAutoDisableRules { statusCodes keywordPatterns times action disableDurationMinutes disableUntilCron disableUntilTimezone } } settings { extraModelPrefix modelMappings { from to } autoTrimedModelPrefixes hideOriginalModels hideMappedModels lowercaseModelId proxy { type disableConnectionReuse } transformOptions { forceArrayInstructions forceArrayInputs replaceDeveloperRoleWithSystem reasoningEffortMapping { from to } } passThroughUserAgent passThroughBody rateLimit { rpm tpm maxConcurrent queueSize queueTimeoutMs } retryableStatusCodes retryableErrorPatterns { pattern regex } modelProtocols { model apiFormats enabled } } } } } }"""
        const val CHANNEL_SECRETS = """query ChannelSecrets(${ '$' }id: ID!) { channels(first: 1, where: {id: ${ '$' }id}) { edges { node { id updatedAt credentials { apiKey apiKeys gcp { region projectID jsonData } oauth { accessToken refreshToken clientID expiresAt tokenType scopes } } disabledAPIKeys { key disabledAt errorCode expiresAt } settings { extraModelPrefix modelMappings { from to } autoTrimedModelPrefixes hideOriginalModels hideMappedModels lowercaseModelId proxy { type url username password disableConnectionReuse } transformOptions { forceArrayInstructions forceArrayInputs replaceDeveloperRoleWithSystem reasoningEffortMapping { from to } } headerOverrideOperations { op path from to value condition match { path eq } index splat } bodyOverrideOperations { op path from to value condition match { path eq } index splat } passThroughUserAgent passThroughBody rateLimit { rpm tpm maxConcurrent queueSize queueTimeoutMs } retryableStatusCodes retryableErrorPatterns { pattern regex } modelProtocols { model apiFormats enabled } providerQuota { commandCode { authCookie } } } } } } }"""
        const val MODEL_DETAIL = """
            query ModelDetail(${ '$' }id: ID!) {
              models(first: 1, where: {id: ${ '$' }id}) { edges { node {
                id name modelID developer type group icon status remark updatedAt associatedChannelCount
                modelCard {
                  reasoning { supported default } toolCall temperature vision modalities { input output }
                  cost { input output cacheRead cacheWrite } limit { context output } knowledge releaseDate lastUpdated
                }
                settings { disableDeveloperSettingsInheritance loadBalancerStrategy traceStickyMode
                  associations { type priority disabled
                    channelModel { channelId modelId } channelRegex { channelId pattern }
                    regex { pattern exclude { channelNamePattern channelIds channelTags } }
                    modelId { modelId exclude { channelNamePattern channelIds channelTags } }
                    channelTagsModel { channelTags modelId } channelTagsRegex { channelTags pattern }
                    when { enabled condition { type logic field operator value
                      conditions { type logic field operator value
                        conditions { type logic field operator value conditions { type } }
                      }
                    } }
                  }
                }
              } } }
            }
        """
        const val CREATE_CHANNEL = "mutation CreateChannel(${ '$' }input: CreateChannelInput!) { createChannel(input: ${ '$' }input) { id } }"
        const val UPDATE_CHANNEL = "mutation EditChannel(${ '$' }id: ID!, ${ '$' }input: UpdateChannelInput!) { updateChannel(id: ${ '$' }id, input: ${ '$' }input) { id } }"
        const val DELETE_CHANNEL = "mutation DeleteChannel(${ '$' }id: ID!) { deleteChannel(id: ${ '$' }id) }"
        const val CREATE_MODEL = "mutation CreateModel(${ '$' }input: CreateModelInput!) { createModel(input: ${ '$' }input) { id } }"
        const val UPDATE_MODEL = "mutation EditModel(${ '$' }id: ID!, ${ '$' }input: UpdateModelInput!) { updateModel(id: ${ '$' }id, input: ${ '$' }input) { id } }"
        const val DELETE_MODEL = "mutation DeleteModel(${ '$' }id: ID!) { deleteModel(id: ${ '$' }id) }"
        const val CHANNEL_STATUS = "mutation UpdateChannelStatus(${ '$' }id: ID!, ${ '$' }status: ChannelStatus!) { updateChannelStatus(id: ${ '$' }id, status: ${ '$' }status) { id status } }"
        const val MODEL_STATUS = "mutation UpdateModelStatus(${ '$' }id: ID!, ${ '$' }status: ModelStatus!) { updateModelStatus(id: ${ '$' }id, status: ${ '$' }status) }"
        const val TEST_CHANNEL = "mutation TestChannel(${ '$' }input: TestChannelInput!) { testChannel(input: ${ '$' }input) { success latency error } }"
        const val FETCH_MODELS = "query ChannelFetchModels(${ '$' }input: FetchModelsInput!) { fetchModels(input: ${ '$' }input) { models { id } error } }"
        const val SYNC_MODELS = "mutation ChannelSyncModels(${ '$' }id: ID!, ${ '$' }pattern: String) { syncChannelModels(channelID: ${ '$' }id, pattern: ${ '$' }pattern) { channelID supportedModels } }"
    }
}

data class GatewayBatchItem(val id: String, val verified: Boolean, val error: String? = null, val attempted: Boolean = true)
data class GatewayBatchResult(val items: List<GatewayBatchItem>) {
    val succeeded get() = items.count { it.verified }
    val failed get() = items.count { it.attempted && !it.verified }
    val notAttempted get() = items.count { !it.attempted }
}

/** Keep provider/network exception prose out of every Gateway UI path. */
fun gatewayErrorMessage(error: Throwable): String = when (error) {
    AxonException.TargetChanged -> "实例或项目已变化，请返回列表重新打开；未继续执行后续操作。"
    AxonException.VerificationFailed -> "服务器写入结果未通过读回校验；可能已生效，请刷新确认。"
    AxonException.Unauthorized, AxonException.InvalidCredentials -> "认证已失效，请重新登录后重试。"
    AxonException.Forbidden -> "没有执行此操作的权限。"
    AxonException.TimedOut -> "请求超时；写入可能已生效，请刷新确认后重试。"
    AxonException.Transport -> "无法连接服务器，请检查网络与证书。"
    is AxonException.HttpStatus -> "服务器返回 HTTP ${error.status}；请刷新确认后重试。"
    AxonException.Rejected -> "服务器拒绝操作，请检查权限与输入。"
    AxonException.InvalidResponse -> "服务器响应无效；请刷新确认后重试。"
    is IllegalArgumentException -> "输入或配置不完整。编辑凭据与设置前，请先授权读取完整配置。"
    else -> "操作未完成，请刷新确认后重试。错误详情已隐藏以保护凭据。"
}

internal suspend fun runGatewayBatch(ids: List<String>, operation: suspend (String) -> Unit): GatewayBatchResult {
    require(ids.all { it.isNotBlank() }) { "Empty Gateway target" }
    val results = mutableListOf<GatewayBatchItem>()
    var stopped: String? = null
    for (id in ids.distinct()) {
        currentCoroutineContext().ensureActive()
        val reason = stopped
        if (reason != null) {
            results += GatewayBatchItem(id, verified = false, error = reason, attempted = false)
            continue
        }
        try {
            operation(id)
            results += GatewayBatchItem(id, verified = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = gatewayErrorMessage(error)
            results += GatewayBatchItem(id, verified = false, error = message)
            if (error == AxonException.TargetChanged || error == AxonException.Unauthorized || error == AxonException.InvalidCredentials) stopped = message
        }
    }
    return GatewayBatchResult(results)
}

enum class GatewayStatusFilter(val label: String) {
    ALL("全部"), ENABLED("启用中"), DISABLED("已禁用"), OTHER("其他状态");
    fun matches(status: String): Boolean = when (this) {
        ALL -> true
        ENABLED -> status == "enabled"
        DISABLED -> status == "disabled"
        OTHER -> status != "enabled" && status != "disabled"
    }
}

object ChannelTypes {
    val all = "openai openai_responses atlascloud cline codex vercel anthropic anthropic_aws anthropic_gcp gemini_openai gemini gemini_vertex deepseek deepseek_anthropic deepinfra qiniu fireworks doubao doubao_anthropic moonshot moonshot_anthropic zhipu zai zhipu_anthropic zai_anthropic anthropic_fake openai_fake openrouter xiaomi xiaomi_anthropic xai xai_responses xai_subscription ppio siliconflow volcengine volcengine_anthropic longcat longcat_anthropic minimax minimax_anthropic aihubmix aihubmix_anthropic burncloud modelscope bailian bailian_anthropic moonshot_coding jina github github_copilot claudecode cerebras antigravity nanogpt nanogpt_responses opencode_go opencode_go_anthropic ollama ollama_anthropic evolink evolink_anthropic groq qiniu_anthropic fenno zenmux zenmux_responses zenmux_anthropic zenmux_gemini commandcode commandcode_anthropic".split(' ')
}
