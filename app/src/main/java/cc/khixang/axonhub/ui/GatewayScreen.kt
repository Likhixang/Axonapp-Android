package cc.khixang.axonhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import cc.khixang.axonhub.gateway.GatewayBatchResult
import cc.khixang.axonhub.gateway.GatewayStatusFilter
import cc.khixang.axonhub.gateway.gatewayErrorMessage
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.gateway.ChannelTypes
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable fun GatewayScreen(app: AxonHubApplication) {
    val state by app.repository.snapshot.collectAsState()
    val instanceId by app.repository.selectedId.collectAsState()
    val projectId by app.repository.projectId.collectAsState()
    var models by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(GatewayStatusFilter.ALL) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var channelEditor by remember { mutableStateOf<JsonElement?>(null) }
    var modelEditor by remember { mutableStateOf<JsonElement?>(null) }
    var detail by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var batchFence by remember { mutableStateOf<TargetFence?>(null) }
    var confirmBatch by remember { mutableStateOf<Boolean?>(null) }
    var batchResult by remember { mutableStateOf<GatewayBatchResult?>(null) }
    var batchNames by remember { mutableStateOf(mapOf<String, String>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(instanceId, projectId) {
        selection = emptySet(); selecting = false; detail = null; channelEditor = null; modelEditor = null
        batchResult = null; confirmBatch = null; error = null
    }
    fun refresh() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { app.repository.refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = gatewayErrorMessage(failure) } finally { busy = false }
        }
    }
    fun select(id: String) { if (!busy) selection = if (id in selection) selection - id else selection + id }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IosPageHeader(if (models) "模型" else "渠道", "Gateway · 服务器实时配置") {
            GatewayTextButton(onClick = { selecting = !selecting; selection = emptySet() }, enabled = !busy) { Text(if (selecting) "完成" else "选择") }
            GatewayIconAction(Icons.Default.Add, "新增", !busy && !selecting) { if (models) modelEditor = JsonNull else channelEditor = JsonNull }
            GatewayIconAction(Icons.Default.Refresh, "刷新", !busy) { refresh() }
        }
        IosSegmentedControl(listOf("渠道", "模型"), if (models) 1 else 0, { index ->
            if (!busy) { models = index == 1; selection = emptySet(); batchResult = null; search = ""; status = GatewayStatusFilter.ALL }
        })
        IosSearchField(search, { if (!busy) search = it }, if (models) "搜索名称、ID 或厂商" else "搜索名称、类型或标签")
        IosSegmentedControl(GatewayStatusFilter.entries.map { it.label }, status.ordinal, { if (!busy) status = GatewayStatusFilter.entries[it] })
        if (busy) GatewayBusy("正在与服务器通信…")
        error?.let { GatewayNotice(it, true) }
        when (val current = state) {
            LoadState.Idle, LoadState.Loading -> GatewayEmpty("正在读取服务器数据", "列表将在读取完成后显示。", loading = true)
            is LoadState.Failed -> GatewayEmpty("无法读取配置", current.message, action = { refresh() })
            is LoadState.Ready -> {
                val channelRows = current.value.channels.filter { item -> status.matches(item.status) &&
                    (search.isBlank() || listOf(item.name, item.type).plus(item.tags).any { it.contains(search, true) }) }
                val modelRows = current.value.models.filter { item -> status.matches(item.status) &&
                    (search.isBlank() || listOf(item.name, item.modelId, item.developer).any { it.contains(search, true) }) }
                val visibleIds = if (models) modelRows.map { it.id } else channelRows.map { it.id }
                val names = if (models) current.value.models.associate { it.id to it.name } else current.value.channels.associate { it.id to it.name }
                val count = visibleIds.size
                IosSectionTitle(if (models) "模型列表" else "渠道列表", "显示 $count 项 · 已加载 ${names.size} 项")
                if (selecting) {
                    GatewayGroupedCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("已选择 ${selection.size} 项", Modifier.weight(1f))
                            GatewayTextButton(onClick = { selection = if (visibleIds.all { it in selection }) selection - visibleIds.toSet() else selection + visibleIds }, enabled = !busy && count > 0) { Text("选择当前结果") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GatewayButton(onClick = { batchFence = app.repository.currentFence(); batchNames = names; confirmBatch = true }, enabled = !busy && selection.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("批量启用") }
                            GatewayOutlineButton(onClick = { batchFence = app.repository.currentFence(); batchNames = names; confirmBatch = false }, enabled = !busy && selection.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("批量禁用") }
                        }
                        Text("筛选不会清除已选项；仅处理所选 ID，逐项写入并读回验证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (count == 0) GatewayEmpty(if (names.isEmpty()) "暂无${if (models) "模型" else "渠道"}" else "没有匹配结果", if (names.isEmpty()) "点击右上角 + 新增配置。" else "尝试其他关键词或状态筛选。")
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (models) items(modelRows, key = { it.id }) { item ->
                        GatewayListCard(item.name, item.icon?.takeIf { it.isNotBlank() } ?: item.developer,
                            "${item.developer} · ${item.type}", item.modelId, item.status,
                            selecting, item.id in selection, enabled = !busy,
                            onClick = { if (selecting) select(item.id) else detail = false to item.id })
                    } else items(channelRows, key = { it.id }) { item ->
                        GatewayListCard(item.name, item.type, item.type, item.baseUrl.orEmpty(), item.status,
                            selecting, item.id in selection, "支持模型：${item.supportedModels.size} 个", item.tags.joinToString(" · "), item.errorMessage != null, !busy,
                            onClick = { if (selecting) select(item.id) else detail = true to item.id })
                    }
                }
            }
        }
    }
    detail?.let { (channel, id) -> GatewayDetailDialog(app, channel, id, onDismiss = { detail = null }, onEdit = { value -> if (channel) channelEditor = value else modelEditor = value }) }
    channelEditor?.let { ChannelEditorDialog(app, it.takeUnless { value -> value is JsonNull }) { channelEditor = null } }
    modelEditor?.let { ModelEditorDialog(app, it.takeUnless { value -> value is JsonNull }) { modelEditor = null } }
    confirmBatch?.let { enabled ->
        GatewaySheet(onDismissRequest = { if (!busy) confirmBatch = null }, title = { Text(if (enabled) "批量启用" else "批量禁用") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将处理 ${selection.size} 项。不是原子操作：某项失败不会回滚已验证成功的项。")
                selection.sorted().forEach { Text(batchNames[it] ?: it) }
                if (busy) GatewayBusy("逐项写入并验证中…")
            }
        }, confirmButton = { GatewayButton(enabled = !busy, onClick = {
            busy = true; error = null
            val ids = selection.sorted(); val targetModels = models
            scope.launch {
                try {
                    app.repository.verify(batchFence ?: throw cc.khixang.axonhub.network.AxonException.TargetChanged)
                    val result = if (targetModels) app.gateway.setModelsEnabled(ids, enabled) else app.gateway.setChannelsEnabled(ids, enabled)
                    batchResult = result; selection = result.items.filterNot { it.verified }.map { it.id }.toSet(); confirmBatch = null
                    app.repository.refresh()
                } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = gatewayErrorMessage(failure); confirmBatch = null } finally { busy = false }
            }
        }) { Text("确认执行") } }, dismissButton = { GatewayTextButton(onClick = { confirmBatch = null }, enabled = !busy) { Text("取消") } })
    }
    batchResult?.let { result ->
        GatewaySheet(onDismissRequest = { batchResult = null }, title = { Text("批量操作结果") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("共 ${result.items.size} 项 · 已验证成功 ${result.succeeded} · 未验证 ${result.failed} · 未执行 ${result.notAttempted}")
                Text("未验证项可能已写入，请刷新确认。未成功项保留选择，可检查后重试。", style = MaterialTheme.typography.bodySmall)
                result.items.forEach { item -> GatewayGroupedCard {
                    Text(batchNames[item.id] ?: item.id, style = MaterialTheme.typography.titleMedium)
                    Text(if (item.verified) "已写入并读回验证" else if (!item.attempted) "未执行" else "未通过验证", color = if (item.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                } }
            }
        }, confirmButton = { GatewayTextButton(onClick = { batchResult = null }) { Text("完成") } })
    }
}

@Composable private fun GatewayListCard(name: String, brand: String, subtitle: String, detail: String, status: String,
    selecting: Boolean, selected: Boolean, footnote: String = "", tags: String = "", serverError: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    GatewayGroupedCard(onClick = if (enabled) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selecting) Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, if (selected) "已选择" else "未选择", tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            ProviderBrand(brand)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (!selecting) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(footnote, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusDot(status)
        }
        if (tags.isNotBlank()) Text(tags, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (serverError) GatewayNotice("渠道存在服务端错误（详情已隐藏）", true)
    }
}

@Composable private fun StatusDot(status: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(when (status) { "enabled" -> Icons.Default.CheckCircle; "disabled" -> Icons.Default.PauseCircle; else -> Icons.Default.Info }, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when (status) { "enabled" -> "启用中"; "disabled" -> "已禁用"; "archived" -> "已归档"; else -> status.ifBlank { "未知状态" } }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ProviderBrand(raw: String) {
    val key = raw.lowercase().let { value -> when { "anthropic" in value || "claude" in value -> "anthropic"; "gemini" in value || "google" in value -> "gemini"; "deepseek" in value -> "deepseek"; "openrouter" in value -> "openrouter"; "xai" in value || "grok" in value -> "xai"; "github" in value || "copilot" in value -> "github"; "ollama" in value -> "ollama"; "openai" in value || "codex" in value -> "openai"; else -> "" } }
    if (key.isNotEmpty()) AsyncImage(model = "file:///android_asset/brand-icons/$key.png", contentDescription = null, modifier = Modifier.size(36.dp))
    else Icon(Icons.Default.Hub, null, modifier = Modifier.size(36.dp))
}

@Composable private fun GatewayDetailDialog(app: AxonHubApplication, channel: Boolean, id: String, onDismiss: () -> Unit, onEdit: (JsonElement) -> Unit) {
    var value by remember(id) { mutableStateOf<JsonElement>(JsonNull) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fence = remember { app.repository.currentFence() }
    suspend fun read() {
        app.repository.verify(fence)
        value = if (channel) app.gateway.channelDetail(id) else app.gateway.modelDetail(id)
        if (value["id"].text != id) throw cc.khixang.axonhub.network.AxonException.InvalidResponse
    }
    fun run(operation: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { app.repository.verify(fence); operation() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = gatewayErrorMessage(failure) } finally { busy = false }
        }
    }
    LaunchedEffect(id) {
        try { read() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = gatewayErrorMessage(failure) } finally { busy = false }
    }
    GatewaySheet(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(value["name"].text.ifBlank { if (channel) "渠道详情" else "模型详情" }) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (busy) item { GatewayBusy("正在读取并验证…") }
            error?.let { item { GatewayNotice(it, true); GatewayTextButton(onClick = { run { read() } }, enabled = !busy) { Text("重新读取") } } }
            if (value !is JsonNull) {
                item { GatewayGroupedCard { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderBrand(if (channel) value["type"].text else value["developer"].text)
                    Text(value["name"].text, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    StatusDot(value["status"].text)
                } } }
                item { IosSectionTitle("基本信息"); GatewayGroupedCard { DetailFields(value, listOf("id", "name", "type", "status", "baseURL", "modelID", "developer", "group", "defaultTestModel", "orderingWeight", "supportedModels", "tags", "remark")) } }
                if (value["errorMessage"].text.isNotBlank()) item { GatewayNotice("渠道存在服务端错误（原始错误已隐藏以保护凭据）。", true) }
                item { GatewayGroupedCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GatewayButton(onClick = { onEdit(value); onDismiss() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("编辑") }
                        GatewayOutlineButton(onClick = { run {
                            val enable = value["status"].text != "enabled"
                            if (channel) app.gateway.setChannelEnabled(id, enable) else app.gateway.setModelEnabled(id, enable)
                            read(); app.repository.refresh()
                        } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (value["status"].text == "enabled") "禁用" else "启用") }
                    }
                    if (channel) {
                        GatewayOutlineButton(onClick = { run {
                            val result = app.gateway.testChannel(id, value["defaultTestModel"].text)
                            test = if (result.first) "连接成功 · ${result.second} ms" else "连接测试失败（上游错误已隐藏）。"
                        } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("测试连通性") }
                        GatewayOutlineButton(onClick = { run {
                            val list = app.gateway.syncModels(id, value["autoSyncModelPattern"].text.takeIf(String::isNotBlank))
                            test = "已同步并验证 ${list.size} 个模型"; read(); app.repository.refresh()
                        } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("同步模型") }
                        test?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    GatewayTextButton(onClick = { confirmDelete = true }, enabled = !busy, destructive = true) { Text("永久删除") }
                } }
                item { IosSectionTitle("高级配置", "凭据与未授权秘密不会显示"); GatewayGroupedCard {
                    DetailFields(value, value.obj.keys.filterNot { it in setOf("id", "name", "type", "status", "baseURL", "modelID", "developer", "group", "remark", "errorMessage") || SensitiveFields.matches(it) }.sorted())
                } }
            }
        }
    }, confirmButton = { GatewayTextButton(onClick = onDismiss, enabled = !busy) { Text("完成") } })
    if (confirmDelete) GatewaySheet(onDismissRequest = { if (!busy) confirmDelete = false }, title = { Text("确认永久删除") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(value["name"].text)
            Text("这会永久删除服务端${if (channel) "渠道" else "模型"}，可能中断路由。无法撤销。")
            if (busy) GatewayBusy("正在删除并确认…")
            error?.let { GatewayNotice(it, true) }
        }
    }, confirmButton = { GatewayButton(onClick = { run {
        if (channel) app.gateway.deleteChannel(id) else app.gateway.deleteModel(id)
        confirmDelete = false; app.repository.refresh(); onDismiss()
    } }, enabled = !busy, destructive = true) { Text("永久删除") } }, dismissButton = { GatewayTextButton(onClick = { confirmDelete = false }, enabled = !busy) { Text("取消") } })
}

@Composable private fun ChannelEditorDialog(app: AxonHubApplication, initial: JsonElement?, onDismiss: () -> Unit) {
    val fence = remember { app.repository.currentFence() }; val schema = app.adminCatalog.schema; var name by remember { mutableStateOf(initial["name"].text) }; var type by remember { mutableStateOf(initial["type"].text.ifBlank { "openai" }) }; var baseUrl by remember { mutableStateOf(initial["baseURL"].text) }; var models by remember { mutableStateOf(initial["supportedModels"].arr.joinToString("\n") { it.text }) }; var defaultModel by remember { mutableStateOf(initial["defaultTestModel"].text) }; var tags by remember { mutableStateOf(initial["tags"].arr.joinToString("\n") { it.text }) }; var weight by remember { mutableStateOf((initial["orderingWeight"].intOrNull ?: 0).toString()) }; var remark by remember { mutableStateOf(initial["remark"].text) }
    var settings by remember { mutableStateOf(initial["settings"].takeUnless { it is JsonNull } ?: buildJsonObject {}) }; var policies by remember { mutableStateOf(initial["policies"].takeUnless { it is JsonNull } ?: buildJsonObject { put("stream", "unlimited") }) }; var credentials by remember { mutableStateOf<JsonElement>(buildJsonObject {}) }; var apiKey by remember { mutableStateOf("") }; var authorized by remember { mutableStateOf(false) }; var oauth by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    GatewaySheet(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (initial == null) "新增渠道" else "编辑渠道") }, text = { CompositionLocalProvider(LocalGatewayEnabled provides !busy) { Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        GatewayField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth()); GatewayEnum("提供商", type, ChannelTypes.all) { if (!busy) type = it }; GatewayField(baseUrl, { baseUrl = it }, label = { Text("接口地址") }, modifier = Modifier.fillMaxWidth()); GatewayField(models, { models = it }, label = { Text("支持模型（每行一个）") }, minLines = 3, modifier = Modifier.fillMaxWidth()); GatewayField(defaultModel, { defaultModel = it }, label = { Text("默认测试模型") }, modifier = Modifier.fillMaxWidth()); GatewayField(tags, { tags = it }, label = { Text("标签（每行一个）") }, modifier = Modifier.fillMaxWidth()); GatewayField(weight, { weight = it }, label = { Text("排序权重") }, modifier = Modifier.fillMaxWidth()); GatewayField(remark, { remark = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
        Text("策略", style = MaterialTheme.typography.titleMedium); SchemaValueEditor(schema, "ChannelPoliciesInput", policies, { if (!busy) policies = it }, "策略")
        Text("设置", style = MaterialTheme.typography.titleMedium); if (initial == null || authorized) SchemaValueEditor(schema, "ChannelSettingsInput", settings, { if (!busy) settings = it }, "设置")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GatewayOutlineButton({ busy = true; scope.launch { error = null; try { app.repository.verify(fence); val input = buildJsonObject { put("channelType", type); put("baseURL", baseUrl.trim()); apiKey.takeIf(String::isNotBlank)?.let { put("apiKey", it) }; initial?.get("id")?.text?.takeIf(String::isNotBlank)?.let { put("channelID", it) } }; val found = app.gateway.fetchUpstreamModels(input); models = found.joinToString("\n") } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = gatewayErrorMessage(t) } finally { busy = false } } }, enabled = !busy && baseUrl.isNotBlank()) { Text("获取上游模型") }; if (type in setOf("codex", "claudecode", "antigravity", "xai_subscription", "github_copilot")) GatewayOutlineButton({ oauth = true }, enabled = !busy && (initial == null || authorized)) { Text("OAuth") } }
        if (initial != null && !authorized) Text("修改凭据、完整设置或 OAuth 前，必须明确授权读取原配置。", style = MaterialTheme.typography.bodySmall)
        if (initial != null && !authorized) GatewayOutlineButton({ busy = true; scope.launch { try { app.repository.verify(fence); val secret = app.gateway.channelSecrets(initial["id"].text); credentials = secret["credentials"]; apiKey = secret["credentials"]["apiKey"].text; settings = secret["settings"]; authorized = true } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = gatewayErrorMessage(t) } finally { busy = false } } }) { Text("授权读取完整配置与凭据") }
        GatewayField(apiKey, { apiKey = it }, label = { Text(if (initial == null) "API key" else "API key（需先授权读取）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy) GatewayBusy("正在处理…")
    } } }, confirmButton = { GatewayTextButton(enabled = !busy, onClick = { busy = true; scope.launch { error = null; try { app.repository.verify(fence); require(name.isNotBlank() && weight.toIntOrNull() != null); val line = { s: String -> s.lines().map(String::trim).filter(String::isNotEmpty).distinct() }; val draft = buildJsonObject { put("name", name.trim()); if (baseUrl.isNotBlank()) put("baseURL", baseUrl.trim()); put("type", type); put("supportedModels", JsonArray(line(models).map(::JsonPrimitive))); put("defaultTestModel", defaultModel.trim()); put("tags", JsonArray(line(tags).map(::JsonPrimitive))); put("orderingWeight", weight.toIntOrNull() ?: 0); put("remark", remark); put("policies", policies); put("settings", settings); if (apiKey.isNotBlank() || credentials.obj.isNotEmpty()) put("credentials", JsonObject(credentials.obj.toMutableMap().also { if (apiKey.isNotBlank()) it["apiKey"] = JsonPrimitive(apiKey.trim()) })) }; app.gateway.saveChannel(initial["id"].text.takeIf(String::isNotBlank), draft, initial ?: JsonNull, authorized || initial == null); app.repository.refresh(); onDismiss() } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = gatewayErrorMessage(t) } finally { busy = false } } }) { Text("完成") } }, dismissButton = { GatewayTextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
    if (oauth) OAuthDialog(app, type, settings["proxy"], { credential -> apiKey = credential; credentials = buildJsonObject {}; authorized = true; oauth = false }, { oauth = false })
}

@Composable private fun OAuthDialog(app: AxonHubApplication, channelType: String, proxy: JsonElement, imported: (String) -> Unit, dismiss: () -> Unit) {
    val fence = remember { app.repository.currentFence() }
    val provider = when (channelType) { "github_copilot" -> "copilot"; "xai_subscription" -> "xai"; else -> channelType }
    var session by remember { mutableStateOf("") }; var authUrl by remember { mutableStateOf("") }; var userCode by remember { mutableStateOf("") }; var callback by remember { mutableStateOf("") }; var importValue by remember { mutableStateOf("") }; var projectId by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope(); val uriHandler = LocalUriHandler.current
    fun run(action: String, body: JsonObject) { if (busy) return; busy = true; scope.launch { message = null; try { app.repository.verify(fence); val result = app.gateway.oauth(provider, action, body); if (action == "oauth/start") { session = result["session_id"].text; authUrl = if (provider == "copilot") result["verification_uri"].text else result["auth_url"].text; userCode = result["user_code"].text } else if (provider == "copilot" && result["access_token"].text.isNotBlank()) imported(buildJsonObject { put("access_token", result["access_token"].text); put("token_type", "bearer") }.toString()) else if (result["credentials"].text.isNotBlank()) imported(result["credentials"].text) else message = "Authorization is incomplete or expired." } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { message = gatewayErrorMessage(t) } finally { busy = false } } }
    GatewaySheet(onDismissRequest = { if (!busy) dismiss() }, title = { Text("提供商授权") }, text = { CompositionLocalProvider(LocalGatewayEnabled provides !busy) { Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Authorize on the provider page. Credentials are kept in memory and are only sent when you save the channel.", style = MaterialTheme.typography.bodySmall)
        if (provider == "antigravity") GatewayField(projectId, { projectId = it }, label = { Text("Google Cloud project (optional)") }, modifier = Modifier.fillMaxWidth())
        GatewayButton(enabled = !busy, onClick = { run("oauth/start", buildJsonObject { if (provider == "antigravity" && projectId.isNotBlank()) put("project_id", projectId.trim()) }) }, modifier = Modifier.fillMaxWidth()) { Text("Start authorization") }
        if (authUrl.isNotBlank()) GatewayOutlineButton({ runCatching { java.net.URI(authUrl) }.getOrNull()?.takeIf { it.scheme == "https" && it.userInfo == null && !it.host.isNullOrBlank() }?.let { runCatching { uriHandler.openUri(it.toString()) }.onFailure { message = "无法打开授权页面，请检查是否安装浏览器。" } } ?: run { message = "The provider returned an unsafe authorization URL." } }, Modifier.fillMaxWidth()) { Text("Open provider page") }
        if (userCode.isNotBlank()) Text(userCode, style = MaterialTheme.typography.titleMedium)
        if (provider == "copilot") GatewayOutlineButton(enabled = session.isNotBlank() && !busy, onClick = { run("oauth/poll", buildJsonObject { put("session_id", session) }) }, modifier = Modifier.fillMaxWidth()) { Text("Check authorization status") }
        else { GatewayField(callback, { callback = it }, label = { Text("Authorization callback URL") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()); GatewayOutlineButton(enabled = session.isNotBlank() && callback.isNotBlank() && !busy, onClick = { run("oauth/exchange", buildJsonObject { put("session_id", session); put("callback_url", callback); if (proxy["type"].text == "URL") put("proxy", JsonObject(proxy.obj.toMutableMap().also { it["type"] = JsonPrimitive("url") })) }) }, modifier = Modifier.fillMaxWidth()) { Text("Import authorized credentials") } }
        if (provider == "codex" || provider == "xai") { GatewayField(importValue, { importValue = it }, label = { Text(if (provider == "codex") "Codex auth.json" else "xAI SSO token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()); GatewayOutlineButton(enabled = importValue.isNotBlank() && !busy, onClick = { run(if (provider == "codex") "auth/decode" else "oauth/sso", buildJsonObject { put(if (provider == "codex") "auth_json" else "sso_token", importValue) }) }, modifier = Modifier.fillMaxWidth()) { Text("Parse and import") } }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy) GatewayBusy("正在处理…")
    } } }, confirmButton = {}, dismissButton = { GatewayTextButton(enabled = !busy, onClick = dismiss) { Text("返回") } })
}

@Composable private fun ModelEditorDialog(app: AxonHubApplication, initial: JsonElement?, onDismiss: () -> Unit) {
    val fence = remember { app.repository.currentFence() }; val schema = app.adminCatalog.schema; var name by remember { mutableStateOf(initial["name"].text) }; var modelId by remember { mutableStateOf(initial["modelID"].text) }; var developer by remember { mutableStateOf(initial["developer"].text) }; var type by remember { mutableStateOf(initial["type"].text.ifBlank { "chat" }) }; var group by remember { mutableStateOf(initial["group"].text) }; var icon by remember { mutableStateOf(initial["icon"].text) }; var remark by remember { mutableStateOf(initial["remark"].text) }; var card by remember { mutableStateOf(initial["modelCard"].takeUnless { it is JsonNull } ?: schema.defaultValue("ModelCardInput!")) }; var settings by remember { mutableStateOf(initial["settings"].takeUnless { it is JsonNull } ?: schema.defaultValue("ModelSettingsInput!")) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    GatewaySheet(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (initial == null) "新增模型" else "编辑模型") }, text = { CompositionLocalProvider(LocalGatewayEnabled provides !busy) { Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { GatewayField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth()); GatewayField(modelId, { modelId = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth()); GatewayField(developer, { developer = it }, label = { Text("厂商") }, modifier = Modifier.fillMaxWidth()); GatewayEnum("类型", type, listOf("chat", "embedding", "rerank", "image_generation", "video_generation")) { if (!busy) type = it }; GatewayField(group, { group = it }, label = { Text("分组") }, modifier = Modifier.fillMaxWidth()); GatewayField(icon, { icon = it }, label = { Text("图标名称") }, modifier = Modifier.fillMaxWidth()); GatewayField(remark, { remark = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth()); Text("模型能力与价格", style = MaterialTheme.typography.titleMedium); SchemaValueEditor(schema, "ModelCardInput!", card, { if (!busy) card = it }, "能力与价格"); Text("路由", style = MaterialTheme.typography.titleMedium); SchemaValueEditor(schema, "ModelSettingsInput!", settings, { if (!busy) settings = it }, "关联规则"); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy) GatewayBusy("正在处理…") } } }, confirmButton = { GatewayTextButton(enabled = !busy, onClick = { busy = true; scope.launch { try { app.repository.verify(fence); error = null; require(name.isNotBlank() && modelId.isNotBlank()); val draft = buildJsonObject { put("name", name.trim()); put("modelID", modelId.trim()); put("developer", developer.trim()); put("type", type); put("group", group.trim()); put("icon", icon.trim()); put("remark", remark); put("modelCard", card); put("settings", settings) }; app.gateway.saveModel(initial["id"].text.takeIf(String::isNotBlank), draft, initial ?: JsonNull); app.repository.refresh(); onDismiss() } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = gatewayErrorMessage(t) } finally { busy = false } } }) { Text("完成") } }, dismissButton = { GatewayTextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable fun DetailFields(value: JsonElement, keys: List<String>) { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { keys.filterNot { SensitiveFields.matches(it) || it == "errorMessage" }.forEach { key -> val item = value[key]; if (item !is JsonNull && item.toString().isNotBlank()) { Text(key, style = MaterialTheme.typography.labelMedium); Text(if (item is JsonPrimitive && item.isString) item.text else item.redacted().toString(), style = MaterialTheme.typography.bodySmall) } } } }

private val LocalGatewayEnabled = staticCompositionLocalOf { true }

@Composable private fun GatewayButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val active = enabled && LocalGatewayEnabled.current
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(13.dp)).background(color.copy(alpha = if (active) 1f else .35f))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = Role.Button, enabled = active, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) { content() }
    }
}

@Composable private fun GatewayOutlineButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val active = enabled && LocalGatewayEnabled.current
    Row(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = Role.Button, enabled = active, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary.copy(alpha = if (active) 1f else .35f)) { content() }
    }
}

@Composable private fun GatewayTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val active = enabled && LocalGatewayEnabled.current
    Row(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = Role.Button, enabled = active, onClick = onClick).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).copy(alpha = if (active) 1f else .35f)) { content() }
    }
}

@Composable private fun GatewayIconAction(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = Role.Button, enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .35f))
    }
}

@Composable private fun GatewayField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit = {}, minLines: Int = 1, visualTransformation: VisualTransformation = VisualTransformation.None) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelMedium, LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { label() }
        BasicTextField(value, onValueChange, enabled = LocalGatewayEnabled.current, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(13.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), minLines = minLines,
            singleLine = minLines == 1, visualTransformation = visualTransformation, cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary))
    }
}

@Composable private fun GatewaySheet(onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit = {}) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                dismissButton()
                Spacer(Modifier.weight(1f))
                confirmButton()
            }
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.headlineMedium) { Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { title() } }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) { text() }
        }
    }
}

@Composable private fun GatewayBusy(message: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.HourglassEmpty, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun GatewayNotice(message: String, error: Boolean = false) {
    Text(message, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun GatewayEmpty(title: String, subtitle: String, loading: Boolean = false, action: (() -> Unit)? = null) {
    GatewayGroupedCard {
        Icon(if (loading) Icons.Default.HourglassEmpty else Icons.Default.Hub, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { GatewayTextButton(onClick = it) { Text("重试") } }
    }
}

@Composable private fun GatewayEnum(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GatewayOutlineButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(value); Spacer(Modifier.weight(1f)); Icon(Icons.Default.UnfoldMore, null) }
    }
    if (expanded) GatewaySheet(onDismissRequest = { expanded = false }, title = { Text(label) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { option ->
                GatewayTextButton(onClick = { onSelect(option); expanded = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(option, Modifier.weight(1f)); if (value == option) Icon(Icons.Default.Check, "已选")
                }
            }
        }
    }, confirmButton = { GatewayTextButton(onClick = { expanded = false }) { Text("完成") } })
}

@Composable private fun GatewayGroupedCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    IosCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
