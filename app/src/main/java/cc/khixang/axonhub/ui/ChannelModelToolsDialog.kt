package cc.khixang.axonhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.gateway.*
import kotlinx.serialization.json.*

@Composable internal fun ChannelModelToolsDialog(app: AxonHubApplication, channel: Boolean, id: String? = null, dismiss: () -> Unit) {
    val tools = remember { GatewayToolsService(app.repository, app.adminCatalog) }
    val snapshot = app.repository.snapshot.collectAsState().value
    val rows = (snapshot as? LoadState.Ready)?.value.let { current ->
        if (channel) current?.channels?.map { it.id to it.name }.orEmpty() else current?.models?.map { it.id to it.name }.orEmpty()
    }
    var selected by remember { mutableStateOf(id?.let { setOf(it) } ?: emptySet<String>()) }
    var action by remember { mutableStateOf(GatewayLifecycleAction.ARCHIVE) }
    var mode by remember { mutableStateOf(ChannelImportMode.IMPORT) }
    var input by remember { mutableStateOf<JsonElement>(if (channel) app.adminCatalog.schema.defaultValue(mode.type) else JsonArray(emptyList())) }
    var catalog by remember { mutableStateOf<JsonElement>(JsonNull) }
    var unassociated by remember { mutableStateOf<JsonElement>(JsonNull) }
    var provider by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var templates by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<GatewayBatchResult?>(null) }
    var importSummary by remember { mutableStateOf<JsonObject?>(null) }
    val reloadTitle = gt("重新读取服务器列表", "Reload server lists")
    val submitTitle = gt("提交批量配置", "Submit batch configuration")
    val catalogTitle = gt("载入模型目录", "Load model catalog")
    val refreshTitle = gt("刷新模型目录", "Refresh model catalog")
    val unassociatedTitle = gt("查找未关联模型", "Find unassociated models")
    val lifecycleTitle = gt(action.label, when (action) { GatewayLifecycleAction.ARCHIVE -> "Archive"; GatewayLifecycleAction.RECOVER -> "Recover"; GatewayLifecycleAction.DELETE -> "Permanently delete" })
    ChannelToolSurface(app, if (channel) gt("渠道操作", "Channel operations") else gt("模型操作", "Model operations"), dismiss) { runner ->
        GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(reloadTitle, recovery = true) {
            app.repository.refresh()
            if (app.repository.snapshot.value !is LoadState.Ready) throw cc.khixang.axonhub.network.AxonException.InvalidResponse
            results = null; importSummary = null; selected = emptySet(); null
        }) }) { Text(reloadTitle) }
        IosSectionTitle(gt("批量选择", "Batch selection"))
        rows.forEach { (entityId, name) -> Row { Text(name, Modifier.weight(1f)); Checkbox(entityId in selected, { selected = if (it) selected + entityId else selected - entityId }) } }
        GatewayTextButton(onClick = { selected = if (selected.size == rows.size) emptySet() else rows.map { it.first }.toSet() }) { Text(gt("全选 / 清空", "Select all / clear")) }
        val actions = GatewayLifecycleAction.entries.filter { channel || it != GatewayLifecycleAction.RECOVER }
        val actionLabels = actions.map { gt(it.label, when (it) { GatewayLifecycleAction.ARCHIVE -> "Archive"; GatewayLifecycleAction.RECOVER -> "Recover"; GatewayLifecycleAction.DELETE -> "Permanently delete" }) }
        GatewayEnum(gt("操作", "Operation"), lifecycleTitle, actionLabels) { label -> action = actions[actionLabels.indexOf(label)] }
        GatewayButton(onClick = {
            val captured = selected.sorted(); val capturedAction = action
            runner.submit(ChannelToolCommand("$lifecycleTitle · ${captured.joinToString { entityId -> rows.firstOrNull { it.first == entityId }?.second ?: entityId }}", write = true, destructive = true) {
                val result = tools.batchLifecycle(captured, channel, capturedAction)
                results = result; app.repository.refresh()
                if (result.failed > 0 || result.notAttempted > 0) throw cc.khixang.axonhub.network.AxonException.VerificationFailed
                "${result.succeeded} / ${result.items.size}"
            })
        }, enabled = selected.isNotEmpty() && !runner.uncertain, destructive = action == GatewayLifecycleAction.DELETE) { Text(gt("确认批量操作", "Confirm batch operation")) }
        Text(gt("逐项执行，不是原子操作；某项失败不会回滚已验证项。", "Runs item by item, not atomically. Failures do not roll back verified items."), style = MaterialTheme.typography.bodySmall)
        results?.let { result ->
            Text("${gt("已验证", "Verified")} ${result.succeeded} · ${gt("未验证", "Unverified")} ${result.failed} · ${gt("未执行", "Not attempted")} ${result.notAttempted}")
            result.items.forEach { item -> Text("${rows.firstOrNull { it.first == item.id }?.second ?: item.id} · ${if (item.verified) gt("已验证", "Verified") else gt("请刷新确认", "Reload to confirm")}", style = MaterialTheme.typography.bodySmall) }
        }
        if (channel) {
            IosSectionTitle(gt("导入与排序", "Import and ordering"))
            GatewayOutlineButton(onClick = { templates = true }) { Text(gt("渠道覆盖模板", "Channel override templates")) }
            val labels = ChannelImportMode.entries.map { gt(it.label, when (it) { ChannelImportMode.IMPORT -> "Import channels"; ChannelImportMode.CREATE -> "Create channels from keys"; ChannelImportMode.ORDERING -> "Ordering weights" }) }
            GatewayEnum(gt("批量配置", "Batch configuration"), labels[mode.ordinal], labels) { label -> mode = ChannelImportMode.entries[labels.indexOf(label)]; input = app.adminCatalog.schema.defaultValue(mode.type) }
            Text(gt("输入凭据仅保留在内存；提交前需明确授权。", "Credentials stay in memory; submission requires explicit authorization."), style = MaterialTheme.typography.bodySmall)
            key(mode) { SchemaValueEditor(app.adminCatalog.schema, mode.type + "!", input, { input = it }, gt("批量配置", "Batch configuration"), sensitive = mode != ChannelImportMode.ORDERING) }
        } else {
            IosSectionTitle(gt("模型目录", "Model catalog"))
            GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(catalogTitle, recovery = true) { catalog = tools.providersCatalog(); null }) }) { Text(catalogTitle) }
            GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(refreshTitle, write = true) { catalog = tools.providersCatalog(true); null }) }, enabled = !runner.uncertain) { Text(refreshTitle) }
            val providers = catalog["data"]["providers"].obj.keys.sorted()
            if (providers.isNotEmpty()) {
                GatewayEnum(gt("厂商", "Provider"), provider, providers) { provider = it }
                IosSearchField(search, { search = it }, gt("搜索模型", "Search models"))
                catalog["data"]["providers"][provider]["models"].arr.filter { search.isBlank() || it["id"].text.contains(search, true) }.forEach { model ->
                    val draft = catalogModelInput(model, provider)
                    GatewayTextButton(onClick = { if (input.arr.none { it["modelID"] == draft["modelID"] && it["developer"] == draft["developer"] }) input = JsonArray(input.arr + draft) }, modifier = Modifier.fillMaxWidth()) { Text("+ ${model["id"].text}") }
                }
            }
            GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(unassociatedTitle) { unassociated = tools.unassociatedChannels(); null }) }) { Text(unassociatedTitle) }
            unassociated.arr.forEach { row ->
                IosSectionTitle(row["channel"]["name"].text)
                row["models"].arr.forEach { model ->
                    GatewayTextButton(onClick = {
                        val channelNumber = gatewayNumericChannelId(row["channel"]["id"].text) ?: return@GatewayTextButton
                        val draft = catalogModelInput(buildJsonObject { put("id", model.text) }, "custom").let { candidate ->
                            JsonObject(candidate + ("settings" to buildJsonObject { put("associations", buildJsonArray { add(buildJsonObject { put("type", "channel_model"); put("channelModel", buildJsonObject { put("channelId", channelNumber); put("modelId", model.text) }) }) }) }))
                        }
                        if (input.arr.none { it["modelID"] == draft["modelID"] }) input = JsonArray(input.arr + draft)
                    }, enabled = gatewayNumericChannelId(row["channel"]["id"].text) != null, modifier = Modifier.fillMaxWidth()) { Text("+ ${model.text}") }
                }
            }
            Text("${gt("待导入模型", "Models to import")}: ${input.arr.size}")
            SchemaValueEditor(app.adminCatalog.schema, "[CreateModelInput!]!", input, { input = it }, gt("模型配置", "Model configuration"))
        }
        importSummary?.let { response ->
            Text("${gt("已创建并验证", "Created and verified")}: ${response["verified"].text} · ${gt("失败", "Failed")}: ${response["failed"].text}")
            if (response["failed"].intOrNull?.let { it > 0 } == true) GatewayNotice(gt("已创建记录不会回滚。请重新读取服务器列表并检查失败项，不要重复导入整批。", "Created records are not rolled back. Reload server lists and inspect failed items; do not reimport the entire batch."), true)
        }
        GatewayButton(onClick = {
            val captured = input; val capturedMode = mode
            runner.submit(ChannelToolCommand(submitTitle, write = true) {
                val result = if (channel) {
                    val response = tools.bulkChannels(capturedMode, captured.obj, true)
                    importSummary = response
                    app.repository.refresh()
                    // Preserve verified partial results, and require reload before another import.
                    if (response["success"].boolOrNull != true) throw cc.khixang.axonhub.network.AxonException.VerificationFailed
                    "${response["verified"].text} / ${response["failed"].text}"
                } else tools.createModels(captured as? JsonArray ?: throw IllegalArgumentException()).size.toString()
                app.repository.refresh(); result
            })
        }, enabled = !runner.uncertain && (channel || input.arr.isNotEmpty())) { Text(submitTitle) }
    }
    if (templates) ChannelTemplatesDialog(app, id, { templates = false })
}

@Composable internal fun ModelRouteDialog(app: AxonHubApplication, initial: JsonElement, dismiss: () -> Unit) {
    val tools = remember { GatewayToolsService(app.repository, app.adminCatalog) }
    var associations by remember { mutableStateOf<JsonElement>(initial["settings"]["associations"].takeUnless { it is JsonNull } ?: JsonArray(emptyList())) }
    var preview by remember { mutableStateOf<JsonElement>(JsonNull) }
    val title = gt("预览路由匹配", "Preview route matches")
    ChannelToolSurface(app, gt("模型路由预览", "Model routing preview"), dismiss) { runner ->
        Text(gt("编辑仅用于预览，不会保存模型。", "Edits are used for preview only; the model is not saved."), style = MaterialTheme.typography.bodySmall)
        SchemaValueEditor(app.adminCatalog.schema, "[ModelAssociationInput!]!", associations, { associations = it }, gt("关联规则", "Association rules"))
        GatewayButton(onClick = { val captured = associations; runner.submit(ChannelToolCommand(title, recovery = true) { preview = tools.routePreview(captured as? JsonArray ?: throw IllegalArgumentException()); null }) }) { Text(title) }
        preview.arr.forEach { row -> GatewayGroupedCard {
            BrandMark(row["channel"]["name"].text)
            Text("${row["channel"]["name"].text} · ${row["channel"]["status"].text} · ${gt("优先级", "Priority")} ${row["priority"].text}")
            row["models"].arr.forEach { model -> Text("${model["requestModel"].text} → ${model["actualModel"].text} · ${model["source"].text}", style = MaterialTheme.typography.bodySmall) }
        } }
    }
}
internal fun gatewayNumericChannelId(id: String): Int? = cc.khixang.axonhub.management.KeyEditorPolicy.channelNumericId(id)
internal fun catalogModelInput(model: JsonElement, developer: String): JsonObject {
    fun number(value: JsonElement) = JsonPrimitive(value.doubleOrNull ?: 0.0)
    val id = model["id"].text
    val type = model["type"].text.replace('-', '_').takeIf { it in setOf("chat", "embedding", "rerank", "image_generation", "video_generation") } ?: "chat"
    return buildJsonObject {
        put("modelID", id); put("name", model["display_name"].text.ifBlank { model["name"].text.ifBlank { id } })
        put("developer", developer); put("icon", developer); put("group", model["family"].text.ifBlank { developer }); put("type", type)
        put("settings", buildJsonObject { put("associations", JsonArray(emptyList())) })
        put("modelCard", buildJsonObject {
            put("reasoning", buildJsonObject { put("supported", model["reasoning"]["supported"].boolOrNull ?: false); put("default", model["reasoning"]["default"].boolOrNull ?: false) })
            put("toolCall", model["tool_call"].boolOrNull ?: false); put("temperature", model["temperature"].boolOrNull ?: false)
            put("vision", model["vision"].boolOrNull ?: model["modalities"]["input"].arr.any { it.text == "image" })
            put("modalities", buildJsonObject { put("input", JsonArray(model["modalities"]["input"].arr)); put("output", JsonArray(model["modalities"]["output"].arr)) })
            put("cost", buildJsonObject { listOf("input" to "input", "output" to "output", "cacheRead" to "cache_read", "cacheWrite" to "cache_write").forEach { (to, from) -> put(to, number(model["cost"][from])) } })
            put("limit", buildJsonObject { put("context", model["limit"]["context"].intOrNull ?: 0); put("output", model["limit"]["output"].intOrNull ?: 0) })
            put("knowledge", model["knowledge"].text); put("releaseDate", model["release_date"].text); put("lastUpdated", model["last_updated"].text)
        })
    }
}
