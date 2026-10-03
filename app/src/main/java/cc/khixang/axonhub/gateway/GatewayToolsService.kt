package cc.khixang.axonhub.gateway

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.management.AdminCatalog
import cc.khixang.axonhub.management.AdminField
import cc.khixang.axonhub.network.AxonException
import cc.khixang.axonhub.network.AxonSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Native Gateway tools. Each HTTP boundary revalidates the originally captured session. */
class GatewayToolsService(private val repository: AxonRepository, private val catalog: AdminCatalog) {
    private inner class Target(val session: AxonSession, val project: String?, val fence: TargetFence) {
        suspend fun request(document: String, variables: JsonObject = buildJsonObject {}): JsonObject {
            currentCoroutineContext().ensureActive(); repository.verify(fence)
            val data = repository.api.graphQl(session, document, variables, project)
            repository.verify(fence)
            return data
        }
        suspend fun channel(id: String): JsonElement = node(request(GatewayService.CHANNEL_DETAIL, vars(id)), id)
        suspend fun model(id: String): JsonElement {
            var document = GatewayService.MODEL_DETAIL
            repeat(256) {
                val record = node(request(document, vars(id)), id, "models")
                if (!gatewayTruncatedCondition(record["settings"])) return record
                document = document.replace("conditions { type }", "conditions { type logic field operator value conditions { type } }")
            }
            throw AxonException.InvalidResponse
        }
        suspend fun secrets(id: String): JsonElement = secretNode(request(GatewayService.CHANNEL_SECRETS, vars(id)), id)
        suspend fun prices(id: String): JsonArray {
            val record = node(request(GatewayToolDocuments.ChannelPrices, vars(id)), id)
            val prices = record["channelModelPrices"] as? JsonArray ?: throw AxonException.InvalidResponse
            val ids = prices.map { it["modelID"].text }
            if (ids.any(String::isBlank) || ids.distinct().size != ids.size) throw AxonException.InvalidResponse
            return JsonArray(prices.map { buildJsonObject { put("modelId", it["modelID"]); put("price", it["price"]) } })
        }
        suspend fun templates(): List<JsonElement> {
            val records = mutableListOf<JsonElement>(); val cursors = mutableSetOf<String>(); val ids = mutableSetOf<String>()
            var after: String? = null
            while (true) {
                val page = request(GatewayToolDocuments.ChannelTemplates, buildJsonObject { after?.let { put("after", it) } })["channelOverrideTemplates"]
                requirePage(page)
                page["edges"].arr.forEach { edge ->
                    val record = edge["node"]
                    if (record["id"].text.isBlank() || !ids.add(record["id"].text)) throw AxonException.InvalidResponse
                    records += record
                }
                if (page["pageInfo"]["hasNextPage"].boolOrNull == false) return records
                val next = page["pageInfo"]["endCursor"].text
                if (next.isBlank() || !cursors.add(next)) throw AxonException.InvalidResponse
                after = next
            }
        }
    }
    private suspend fun <T> target(write: Boolean = false, block: suspend (Target) -> T): T {
        val fence = repository.currentFence() // Before waiting for the mutation mutex.
        val action: suspend (AxonSession, String?) -> T = { session, project ->
            repository.verify(fence); block(Target(session, project, fence))
        }
        return if (write) repository.fencedMutation(action) else repository.fenced(action)
    }
    private fun vars(id: String) = buildJsonObject { require(id.isNotBlank()); put("id", id) }
    private fun node(data: JsonObject, id: String, root: String = "channels"): JsonElement {
        val edges = data[root]["edges"] as? JsonArray ?: throw AxonException.InvalidResponse
        if (edges.isEmpty()) return JsonNull
        val node = edges.singleOrNull()?.get("node") ?: throw AxonException.InvalidResponse
        if (node["id"].text != id) throw AxonException.InvalidResponse
        return node
    }
    private fun secretNode(data: JsonObject, id: String): JsonElement {
        val record = node(data, id)
        if (record["credentials"] !is JsonObject || record["disabledAPIKeys"] !is JsonArray) throw AxonException.Forbidden
        return record
    }
    private fun requirePage(page: JsonElement?) {
        if (page["edges"] !is JsonArray || page["pageInfo"]["hasNextPage"].boolOrNull == null) throw AxonException.InvalidResponse
    }
    private fun validate(value: JsonElement, type: String) {
        catalog.schema.validate(buildJsonObject { put("input", value) }, listOf(AdminField("input", type)), mutation = true)
        rejectGatewayMasked(value)
    }
    private fun authorize(authorized: Boolean) { require(authorized) { "Authorize sensitive reads first" } }
    private fun checkedKeys(keys: List<String>): List<String> {
        require(keys.isNotEmpty() && keys.distinct().size == keys.size && keys.all { it.isNotBlank() && !it.contains("••") && !it.matches(Regex("[*•]{3,}")) })
        return keys
    }
    private fun disabled(record: JsonElement) = record["disabledAPIKeys"].arr.map { it["key"].text }.toSet()
    private fun credentials(record: JsonElement) = record["credentials"]["apiKeys"].arr.map { it.text }.toSet() + record["credentials"]["apiKey"].text.takeIf(String::isNotBlank).let { setOfNotNull(it) }

    suspend fun channelKeys(id: String, authorized: Boolean): JsonElement {
        authorize(authorized)
        return target { it.secrets(id) }
    }
    suspend fun keyAction(id: String, action: ChannelKeyAction, authorized: Boolean, key: String = "", keys: List<String> = emptyList(), model: String = ""): JsonElement {
        authorize(authorized)
        if (action in setOf(ChannelKeyAction.ENABLE, ChannelKeyAction.DISABLE, ChannelKeyAction.TEST)) checkedKeys(listOf(key))
        if (action in setOf(ChannelKeyAction.ENABLE_SELECTED, ChannelKeyAction.DELETE_DISABLED)) checkedKeys(keys)
        require(action != ChannelKeyAction.DELETE_DISABLED || "__oauth__" !in keys)
        return target(write = true) { t ->
            val before = t.secrets(id)
            if (action in setOf(ChannelKeyAction.ENABLE_SELECTED, ChannelKeyAction.DELETE_DISABLED) && !disabled(before).containsAll(keys)) throw AxonException.TargetChanged
            if (action in setOf(ChannelKeyAction.ENABLE, ChannelKeyAction.DISABLE, ChannelKeyAction.TEST) && key !in credentials(before) && key !in disabled(before) && !(key == "__oauth__" && before["credentials"]["oauth"] is JsonObject)) throw IllegalArgumentException("Unknown key")
            val variables = buildJsonObject {
                put("id", id)
                if (action in setOf(ChannelKeyAction.ENABLE, ChannelKeyAction.DISABLE, ChannelKeyAction.TEST)) put("key", key)
                if (action in setOf(ChannelKeyAction.ENABLE_SELECTED, ChannelKeyAction.DELETE_DISABLED)) put("keys", JsonArray(keys.map(::JsonPrimitive)))
                if (action in setOf(ChannelKeyAction.TEST, ChannelKeyAction.TEST_ALL)) model.takeIf(String::isNotBlank)?.let { put("model", it) }
            }
            val result = t.request(action.document, variables)[action.root]
            if (action == ChannelKeyAction.TEST_ALL) {
                val rows = result["results"] as? JsonArray ?: throw AxonException.InvalidResponse
                val total = result["total"].intOrNull ?: throw AxonException.InvalidResponse
                val success = result["successCount"].intOrNull ?: throw AxonException.InvalidResponse
                val failed = result["failedCount"].intOrNull ?: throw AxonException.InvalidResponse
                if (result["channelID"].text != id || total < 0 || success < 0 || failed < 0 || rows.size != total || success + failed != total || rows.count { it["success"].boolOrNull == true } != success || rows.any { it["success"].boolOrNull == null }) throw AxonException.InvalidResponse
                return@target buildJsonObject { put("total", total); put("successCount", success); put("failedCount", failed); put("results", JsonArray(rows.map(::safeKeyTest))) }
            }
            if (action == ChannelKeyAction.TEST) return@target safeKeyTest(result)
            if (result.boolOrNull != true && result["success"].boolOrNull != true) throw AxonException.VerificationFailed
            val saved = t.secrets(id); val disabled = disabled(saved)
            val verified = when (action) {
                ChannelKeyAction.DISABLE -> key in disabled
                ChannelKeyAction.ENABLE -> key !in disabled
                ChannelKeyAction.ENABLE_ALL -> disabled.isEmpty()
                ChannelKeyAction.ENABLE_SELECTED -> keys.none { it in disabled }
                ChannelKeyAction.DELETE_DISABLED -> keys.none { it in disabled || it in credentials(saved) }
                else -> false
            }
            if (!verified) throw AxonException.VerificationFailed
            saved
        }
    }
    private fun safeKeyTest(result: JsonElement?): JsonObject {
        if (result["success"].boolOrNull == null || result["latency"].doubleOrNull?.let { !it.isFinite() || it < 0 } != false || result["disabled"].boolOrNull == null) throw AxonException.InvalidResponse
        return buildJsonObject { put("success", result["success"]); put("latency", result["latency"]); put("disabled", result["disabled"]) }
    }

    suspend fun diagnostics(id: String): JsonElement = target { node(it.request(GatewayToolDocuments.ChannelDiagnostics, vars(id)), id).also { result -> if (result is JsonNull) throw AxonException.InvalidResponse }.redacted() }
    suspend fun resetQuota(id: String): JsonElement = target(write = true) { t ->
        if (t.request(GatewayToolDocuments.ChannelQuotaReset, vars(id))["resetChannelQuotaNow"].boolOrNull != true) throw AxonException.VerificationFailed
        node(t.request(GatewayToolDocuments.ChannelDiagnostics, vars(id)), id).also { if (it is JsonNull) throw AxonException.VerificationFailed }.redacted()
    }
    suspend fun testHistory(id: String, after: String? = null): JsonElement = target { t ->
        val page = t.request(GatewayToolDocuments.ChannelTestHistory, buildJsonObject { put("id", id); after?.let { put("after", it) } })["requests"] ?: throw AxonException.InvalidResponse
        requirePage(page)
        val ids = page["edges"].arr.map { it["node"]["id"].text }
        if (ids.any(String::isBlank) || ids.distinct().size != ids.size || page["totalCount"].intOrNull?.let { it < ids.size } != false) throw AxonException.InvalidResponse
        if (page["pageInfo"]["hasNextPage"].boolOrNull == true && (page["pageInfo"]["endCursor"].text.isBlank() || page["pageInfo"]["endCursor"].text == after)) throw AxonException.InvalidResponse
        page
    }
    suspend fun clearError(id: String) = target(write = true) { t ->
        val result = t.request(GatewayToolDocuments.ChannelClearError, vars(id))["updateChannel"]
        if (result["id"].text != id || result["errorMessage"] !is JsonNull) throw AxonException.VerificationFailed
        val saved = t.channel(id)
        if (saved["id"].text != id || saved["errorMessage"] !is JsonNull) throw AxonException.VerificationFailed
    }
    suspend fun channelPrices(id: String): JsonArray = target { t -> t.prices(id) }
    suspend fun savePrices(id: String, input: JsonArray): JsonArray {
        validate(input, "[SaveChannelModelPriceInput!]!")
        val modelIds = input.map { it["modelId"].text }; require(modelIds.all(String::isNotBlank) && modelIds.distinct().size == modelIds.size)
        return target(write = true) { t ->
            if (t.channel(id)["id"].text != id) throw AxonException.InvalidResponse
            val result = t.request(GatewayToolDocuments.ChannelSavePrices, buildJsonObject { put("id", id); put("input", input) })["saveChannelModelPrices"]
            if (result !is JsonArray || result.size != input.size || result.map { it["modelID"].text }.toSet() != modelIds.toSet()) throw AxonException.VerificationFailed
            val saved = t.prices(id)
            input.forEach { expected -> if (saved.none { it["modelId"] == expected["modelId"] && gatewayMatches(it["price"], expected["price"]) }) throw AxonException.VerificationFailed }
            saved
        }
    }
    suspend fun routePreview(associations: JsonArray): JsonArray {
        validate(associations, "[ModelAssociationInput!]!")
        return target { it.request(GatewayToolDocuments.ModelRouteConnections, buildJsonObject { put("associations", associations) })["queryModelChannelConnections"] as? JsonArray ?: throw AxonException.InvalidResponse }
    }
    suspend fun unassociatedChannels(): JsonArray = target { it.request(GatewayToolDocuments.ModelUnassociatedChannels)["queryUnassociatedChannels"] as? JsonArray ?: throw AxonException.InvalidResponse }
    suspend fun providersCatalog(refresh: Boolean = false): JsonElement = target(write = refresh) { t ->
        // Reuse bundled AdminDocuments for the shared refresh contract; catalog reads need filtered:false.
        val refreshed = if (refresh) t.request(catalog.document(catalog.operation("refreshProvidersCatalog")))["refreshProvidersCatalog"] else null
        val actual = t.request(GatewayToolDocuments.ModelProvidersCatalog)["providersCatalog"] ?: throw AxonException.InvalidResponse
        if (actual["data"] !is JsonObject || actual["fetchedAt"] is JsonNull) throw AxonException.InvalidResponse
        if (refreshed != null && (actual["data"] != refreshed["data"] || actual["fetchedAt"] != refreshed["fetchedAt"])) throw AxonException.VerificationFailed
        actual
    }
    suspend fun createModels(inputs: JsonArray): List<String> {
        require(inputs.isNotEmpty()); validate(inputs, "[CreateModelInput!]!")
        require(inputs.all { it["name"].text.isNotBlank() && it["modelID"].text.isNotBlank() })
        val submitted = JsonArray(inputs.map { JsonObject(it.obj + ("modelCard" to normalizeGatewayModelCard(it["modelCard"].obj))) })
        return target(write = true) { t ->
            val result = t.request(GatewayToolDocuments.ModelBulkCreate, buildJsonObject { put("inputs", submitted) })["bulkCreateModels"]
            val ids = result.arr.map { it["id"].text }
            if (ids.size != submitted.size || ids.any(String::isBlank) || ids.distinct().size != ids.size) throw AxonException.VerificationFailed
            ids.zip(submitted).forEach { (id, expected) -> if (!gatewayMatches(t.model(id), expected)) throw AxonException.VerificationFailed }
            ids
        }
    }
    suspend fun batchLifecycle(ids: List<String>, channel: Boolean, action: GatewayLifecycleAction): GatewayBatchResult {
        checkedKeys(ids); require(channel || action != GatewayLifecycleAction.RECOVER)
        return target(write = true) { t -> runGatewayBatch(ids) { id ->
            val before = if (channel) t.channel(id) else t.model(id)
            if (before["id"].text != id) throw AxonException.InvalidResponse
            val doc = when {
                channel && action == GatewayLifecycleAction.ARCHIVE -> GatewayToolDocuments.ChannelBulkArchive
                channel && action == GatewayLifecycleAction.DELETE -> GatewayToolDocuments.ChannelBulkDelete
                channel -> GatewayToolDocuments.ChannelBulkRecover
                action == GatewayLifecycleAction.ARCHIVE -> GatewayToolDocuments.ModelBulkArchive
                else -> GatewayToolDocuments.ModelBulkDelete
            }
            val root = "bulk${action.root}${if (channel) "Channels" else "Models"}"
            if (t.request(doc, buildJsonObject { put("ids", JsonArray(listOf(JsonPrimitive(id)))) })[root].boolOrNull != true) throw AxonException.VerificationFailed
            val saved = if (channel) t.channel(id) else t.model(id)
            if (action == GatewayLifecycleAction.DELETE) { if (saved !is JsonNull) throw AxonException.VerificationFailed }
            else if (saved["id"].text != id || saved["status"].text != action.status) throw AxonException.VerificationFailed
        } }
    }

    suspend fun bulkChannels(mode: ChannelImportMode, input: JsonObject, authorized: Boolean): JsonObject {
        if (mode != ChannelImportMode.ORDERING) authorize(authorized)
        validate(input, mode.type + "!")
        val source = if (mode == ChannelImportMode.CREATE) input["apiKeys"].arr else input["channels"].arr
        require(source.isNotEmpty())
        if (mode == ChannelImportMode.CREATE) checkedKeys(source.map { it.text })
        if (mode == ChannelImportMode.ORDERING) checkedKeys(source.map { it["id"].text })
        return target(write = true) { t ->
            if (mode == ChannelImportMode.ORDERING) source.forEach { if (t.channel(it["id"].text)["id"].text != it["id"].text) throw AxonException.InvalidResponse }
            val result = t.request(mode.document, buildJsonObject { put("input", input) })[mode.root]
            val records = if (mode == ChannelImportMode.CREATE) result.arr else result["channels"].arr
            val ids = records.map { it["id"].text }
            if (ids.any(String::isBlank) || ids.distinct().size != ids.size) throw AxonException.VerificationFailed
            if (mode == ChannelImportMode.CREATE && ids.size != source.size) throw AxonException.VerificationFailed
            if (mode == ChannelImportMode.IMPORT && (result["success"].boolOrNull != true || result["created"].intOrNull != records.size || result["failed"].intOrNull?.let { it < 0 || it + records.size != source.size } != false)) throw AxonException.VerificationFailed
            if (mode == ChannelImportMode.ORDERING && (result["success"].boolOrNull != true || result["updated"].intOrNull != source.size || ids.toSet() != source.map { it["id"].text }.toSet())) throw AxonException.VerificationFailed
            records.forEach { row ->
                val saved = t.channel(row["id"].text)
                if (saved["id"].text != row["id"].text) throw AxonException.VerificationFailed
                if (mode == ChannelImportMode.ORDERING) {
                    val expected = source.first { it["id"].text == row["id"].text }["orderingWeight"]
                    if (saved["orderingWeight"] != expected || row["orderingWeight"] != expected) throw AxonException.VerificationFailed
                } else if (mode == ChannelImportMode.CREATE) {
                    val expected = JsonObject(input.filterKeys { it !in setOf("name", "apiKeys", "settings") })
                    if (!gatewayMatches(saved, expected)) throw AxonException.VerificationFailed
                    val secret = t.secrets(row["id"].text)
                    if (input["settings"] is JsonObject && !gatewayMatches(secret["settings"], input["settings"])) throw AxonException.VerificationFailed
                    if (credentials(secret).intersect(source.map { it.text }.toSet()).isEmpty()) throw AxonException.VerificationFailed
                } else {
                    val matching = source.filter { candidate -> gatewayMatches(saved, JsonObject(candidate.obj.filterKeys { it != "apiKey" })) }
                    if (matching.isEmpty()) throw AxonException.VerificationFailed
                    val expectedKeys = matching.map { it["apiKey"].text }.filter(String::isNotBlank).toSet()
                    if (expectedKeys.isNotEmpty() && credentials(t.secrets(row["id"].text)).intersect(expectedKeys).isEmpty()) throw AxonException.VerificationFailed
                }
            }
            buildJsonObject { put("verified", records.size); put("failed", if (mode == ChannelImportMode.IMPORT) result["failed"] else JsonPrimitive(0)) }
        }
    }
    suspend fun duplicateChannel(id: String, input: JsonObject, authorized: Boolean): String {
        authorize(authorized); validate(input, "CreateChannelInput!"); require(input["name"].text.isNotBlank())
        return target(write = true) { t ->
            if (t.channel(id)["id"].text != id) throw AxonException.InvalidResponse
            val savedId = t.request(GatewayToolDocuments.ChannelDuplicate, buildJsonObject { put("sourceID", id); put("input", input) })["duplicateChannel"]["id"].text
            if (savedId.isBlank() || savedId == id) throw AxonException.VerificationFailed
            val saved = t.channel(savedId); val secret = t.secrets(savedId)
            if (!gatewayMatches(saved, JsonObject(input.filterKeys { it != "credentials" && it != "settings" })) || !gatewayMatches(secret["settings"], input["settings"].takeUnless { it is JsonNull } ?: buildJsonObject {}) || !gatewayCredentialMatches(secret["credentials"], input["credentials"])) throw AxonException.VerificationFailed
            savedId
        }
    }

    suspend fun channelTemplates(authorized: Boolean): List<JsonElement> { authorize(authorized); return target { it.templates() } }
    suspend fun saveTemplate(id: String?, input: JsonObject, baseline: JsonElement, authorized: Boolean): String {
        authorize(authorized); validate(input, "CreateChannelOverrideTemplateInput!"); require(input["name"].text.isNotBlank())
        return target(write = true) { t ->
            if (id != null && t.templates().firstOrNull { it["id"].text == id } != baseline) throw AxonException.TargetChanged
            val result = t.request(if (id == null) GatewayToolDocuments.ChannelTemplateCreate else GatewayToolDocuments.ChannelTemplateEdit, buildJsonObject { id?.let { put("id", it) }; put("input", input) })[if (id == null) "createChannelOverrideTemplate" else "updateChannelOverrideTemplate"]
            val savedId = result["id"].text
            if (savedId.isBlank() || (id != null && savedId != id)) throw AxonException.VerificationFailed
            val actual = t.templates().firstOrNull { it["id"].text == savedId } ?: throw AxonException.VerificationFailed
            if (!gatewayMatches(actual, input)) throw AxonException.VerificationFailed
            savedId
        }
    }
    suspend fun deleteTemplate(id: String, baseline: JsonElement, authorized: Boolean) {
        authorize(authorized)
        target(write = true) { t ->
            if (t.templates().firstOrNull { it["id"].text == id } != baseline) throw AxonException.TargetChanged
            if (t.request(GatewayToolDocuments.ChannelTemplateDelete, vars(id))["deleteChannelOverrideTemplate"].boolOrNull != true || t.templates().any { it["id"].text == id }) throw AxonException.VerificationFailed
        }
    }
    suspend fun applyTemplate(template: JsonElement?, ids: List<String>, replace: Boolean, authorized: Boolean) {
        authorize(authorized); checkedKeys(ids)
        target(write = true) { t ->
            if (template != null && t.templates().firstOrNull { it["id"] == template["id"] } != template) throw AxonException.TargetChanged
            val expected = ids.associateWith { id ->
                val settings = t.secrets(id)["settings"].obj.toMutableMap()
                listOf("headerOverrideOperations", "bodyOverrideOperations").forEach { key ->
                    settings[key] = when {
                        template == null -> JsonArray(emptyList())
                        replace -> JsonArray(template[key].arr)
                        else -> mergeGatewayOverrides(settings[key].arr, template[key].arr, key.startsWith("header"))
                    }
                }
                JsonObject(settings)
            }
            val result = t.request(if (template == null) GatewayToolDocuments.ChannelTemplateClear else GatewayToolDocuments.ChannelTemplateApply, buildJsonObject { put("input", buildJsonObject { put("channelIDs", JsonArray(ids.map(::JsonPrimitive))); if (template != null) { put("templateID", template["id"]); put("mode", if (replace) "REPLACE" else "MERGE") } }) })[if (template == null) "clearChannelOverrideTemplates" else "applyChannelOverrideTemplate"]
            if (result["success"].boolOrNull != true || result["updated"].intOrNull != ids.size || result["channels"].arr.map { it["id"].text }.toSet() != ids.toSet()) throw AxonException.VerificationFailed
            ids.forEach { if (!gatewayMatches(t.secrets(it)["settings"], expected.getValue(it))) throw AxonException.VerificationFailed }
        }
    }
}

enum class ChannelKeyAction(val label: String, val root: String, internal val document: String) {
    TEST_ALL("测试全部密钥", "testChannelAPIKeys", GatewayToolDocuments.ChannelTestKeys),
    TEST("测试指定密钥", "testChannelAPIKey", GatewayToolDocuments.ChannelTestKey),
    ENABLE("启用指定密钥", "enableChannelAPIKey", GatewayToolDocuments.ChannelEnableKey),
    DISABLE("禁用指定密钥", "disableChannelAPIKey", GatewayToolDocuments.ChannelDisableKey),
    ENABLE_ALL("启用全部密钥", "enableAllChannelAPIKeys", GatewayToolDocuments.ChannelEnableAllKeys),
    ENABLE_SELECTED("启用所选密钥", "enableSelectedChannelAPIKeys", GatewayToolDocuments.ChannelEnableSelectedKeys),
    DELETE_DISABLED("删除所选密钥", "deleteDisabledChannelAPIKeys", GatewayToolDocuments.ChannelDeleteDisabledKeys)
}
enum class GatewayLifecycleAction(val label: String, val root: String, val status: String) {
    ARCHIVE("归档", "Archive", "archived"), RECOVER("恢复", "Recover", "enabled"), DELETE("永久删除", "Delete", "")
}
enum class ChannelImportMode(val label: String, val type: String, val root: String, internal val document: String) {
    IMPORT("导入渠道", "BulkImportChannelsInput", "bulkImportChannels", GatewayToolDocuments.ChannelBulkImport),
    CREATE("按密钥创建渠道", "BulkCreateChannelsInput", "bulkCreateChannels", GatewayToolDocuments.ChannelBulkCreate),
    ORDERING("排序权重", "BulkUpdateChannelOrderingInput", "bulkUpdateChannelOrdering", GatewayToolDocuments.ChannelBulkOrdering)
}
internal fun gatewayTruncatedCondition(value: JsonElement): Boolean = when (value) {
    is JsonObject -> (value.size == 1 && "type" in value) || value.values.any(::gatewayTruncatedCondition)
    is JsonArray -> value.any(::gatewayTruncatedCondition)
    else -> false
}
internal fun rejectGatewayMasked(value: JsonElement, sensitive: Boolean = false) {
    when (value) {
        is JsonObject -> value.forEach { (key, item) -> rejectGatewayMasked(item, sensitive || SensitiveFields.matches(key) || key in setOf("headerOverrideOperations", "bodyOverrideOperations")) }
        is JsonArray -> value.forEach { rejectGatewayMasked(it, sensitive) }
        is JsonPrimitive -> if (sensitive && (value.text.contains("••") || value.text.matches(Regex("[*•]{3,}")))) throw IllegalArgumentException("Masked credentials")
        else -> Unit
    }
}
internal fun gatewayMatches(actual: JsonElement?, expected: JsonElement?): Boolean = when (expected) {
    is JsonObject -> expected.all { gatewayMatches(actual[it.key], it.value) }
    is JsonArray -> actual is JsonArray && actual.size == expected.size && actual.zip(expected).all { gatewayMatches(it.first, it.second) }
    else -> if (actual is JsonPrimitive && expected is JsonPrimitive && !actual.isString && !expected.isString && actual.content.toBigDecimalOrNull() != null && expected.content.toBigDecimalOrNull() != null) actual.content.toBigDecimal().compareTo(expected.content.toBigDecimal()) == 0 else actual == expected
}
internal fun gatewayCredentialMatches(actual: JsonElement?, expected: JsonElement?): Boolean = expected.obj.all { (key, value) ->
    if (key == "apiKey" && value.text.isNotBlank()) actual["apiKey"] == value || value in actual["apiKeys"].arr else gatewayMatches(actual[key], value)
}
/** Matches official Go ordering and the iOS implementation; headers are case-insensitive. */
internal fun mergeGatewayOverrides(existing: List<JsonElement>, template: List<JsonElement>, header: Boolean): JsonArray {
    fun replacing(op: JsonElement) = if (header) op["op"].text == "set" else op["op"].text in setOf("set", "set_if_absent", "delete")
    fun path(op: JsonElement) = op["path"].text.let { if (header) it.lowercase() else it }
    if (header) {
        val result = existing.toMutableList()
        template.forEach { op ->
            val index = if (op["op"].text in setOf("rename", "copy")) -1 else result.indexOfFirst { it["op"].text in setOf("set", "delete") && path(it) == path(op) }
            if (index < 0) result += op else result[index] = op
        }
        return JsonArray(result)
    }
    val groups = template.filter(::replacing).groupBy(::path); val emitted = mutableSetOf<String>(); val result = mutableListOf<JsonElement>()
    existing.forEach { op ->
        val replacements = groups[path(op)]
        if (replacing(op) && replacements != null) { if (emitted.add(path(op))) result += replacements } else result += op
    }
    result += template.filter { !replacing(it) || path(it) !in emitted }
    return JsonArray(result)
}
internal fun normalizeGatewayModelCard(card: JsonObject): JsonObject {
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
