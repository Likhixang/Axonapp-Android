package cc.khixang.axonhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.gateway.*
import kotlinx.serialization.json.*

@Composable internal fun ChannelDetailToolsDialog(app: AxonHubApplication, initial: JsonElement, dismiss: () -> Unit) {
    val id = initial["id"].text
    val tools = remember { GatewayToolsService(app.repository, app.adminCatalog) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var prices by remember { mutableStateOf<JsonElement>(JsonArray(emptyList())) }
    var pricesLoaded by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<JsonElement>(JsonNull) }
    var history by remember { mutableStateOf(listOf<JsonElement>()) }
    var next by remember { mutableStateOf<String?>(null) }
    var historyLoaded by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf(listOf<String>()) }
    var duplicate by remember { mutableStateOf<JsonElement>(JsonNull) }
    var duplicateAuthorized by remember { mutableStateOf(false) }
    val pricesTitle = gt("载入价格", "Load prices")
    val saveTitle = gt("保存模型价格", "Save model prices")
    val testTitle = gt("测试所选模型", "Test selected models")
    val readTitle = gt("读取渠道诊断", "Load channel diagnostics")
    val resetTitle = gt("重置配额", "Reset quota")
    val clearTitle = gt("清除错误", "Clear error")
    val historyTitle = gt("载入测试记录", "Load test history")
    val moreTitle = gt("加载更多", "Load more")
    val duplicateTitle = gt("授权读取复制配置", "Authorize duplication configuration")
    val createTitle = gt("复制渠道", "Duplicate channel")
    val submittedText = gt("重置已提交；配额采集需等待上游更新。", "Reset submitted; quota collection awaits upstream updates.")
    ChannelToolSurface(app, gt("模型、价格与诊断", "Models, prices and diagnostics"), dismiss) { runner ->
        IosSectionTitle(gt("模型测试", "Model tests"))
        Text(gt("测试会消耗上游额度。", "Tests consume upstream quota."), style = MaterialTheme.typography.bodySmall)
        initial["supportedModels"].arr.map { it.text }.distinct().forEach { model ->
            Row { Text(model, Modifier.weight(1f)); Checkbox(model in selected, { selected = if (it) selected + model else selected - model }) }
        }
        GatewayTextButton(onClick = { selected = initial["supportedModels"].arr.map { it.text }.toSet() }) { Text(gt("全选模型", "Select all models")) }
        GatewayOutlineButton(onClick = {
            val models = selected.sorted()
            runner.submit(ChannelToolCommand(testTitle, confirm = true) {
                val results = mutableListOf<String>()
                val fence = app.repository.currentFence()
                models.forEach { model ->
                    app.repository.verify(fence)
                    val result = app.gateway.testChannel(id, model)
                    app.repository.verify(fence)
                    results += "$model · ${if (result.first) "OK" else "Failed"} · ${result.second} ms"
                    testResults = results.toList()
                }; null
            })
        }, enabled = selected.isNotEmpty() && !runner.uncertain) { Text(testTitle) }
        testResults.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        IosSectionTitle(gt("模型价格", "Model prices"))
        GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(pricesTitle, recovery = true) { prices = tools.channelPrices(id); pricesLoaded = true; null }) }) { Text(pricesTitle) }
        if (pricesLoaded) {
            SchemaValueEditor(app.adminCatalog.schema, "[SaveChannelModelPriceInput!]!", prices, { prices = it }, gt("价格配置", "Price configuration"))
            GatewayButton(onClick = { val captured = prices; runner.submit(ChannelToolCommand(saveTitle, write = true) { prices = tools.savePrices(id, captured as? JsonArray ?: throw IllegalArgumentException()); null }) }, enabled = !runner.uncertain) { Text(saveTitle) }
        }
        IosSectionTitle(gt("渠道诊断", "Channel diagnostics"))
        GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(readTitle, recovery = true) { diagnostics = tools.diagnostics(id); null }) }) { Text(readTitle) }
        if (diagnostics !is JsonNull) {
            GatewayGroupedCard {
                Text(gt("并发限制", "Concurrency limits"), style = MaterialTheme.typography.titleMedium)
                val limiter = diagnostics["liveLimiterStats"]
                Text("${gt("执行中", "In flight")}: ${limiter["inFlight"].text} · ${gt("等待", "Waiting")}: ${limiter["waiting"].text}")
                Text("${gt("容量", "Capacity")}: ${limiter["capacity"].text} · ${gt("队列", "Queue")}: ${limiter["queueSize"].text}")
            }
            GatewayGroupedCard {
                Text(gt("供应商配额", "Provider quota"), style = MaterialTheme.typography.titleMedium)
                val quota = diagnostics["providerQuotaStatus"]
                Text("${quota["providerType"].text} · ${quota["status"].text}")
                Text("${gt("下次重置", "Next reset")}: ${quota["nextResetAt"].text}")
                Text("${gt("下次检查", "Next check")}: ${quota["nextCheckAt"].text}")
                // Arbitrary quotaData is not shown: redaction cannot identify opaque secrets.
                GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(resetTitle, write = true, destructive = true) { diagnostics = tools.resetQuota(id); submittedText }) }, enabled = !runner.uncertain) { Text(resetTitle) }
            }
            GatewayGroupedCard {
                Text(gt("模型映射", "Model mappings"), style = MaterialTheme.typography.titleMedium)
                diagnostics["allModelEntries"].arr.forEach { Text("${it["requestModel"].text} → ${it["actualModel"].text} · ${it["source"].text}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(clearTitle, write = true) { tools.clearError(id); null }) }, enabled = !runner.uncertain) { Text(clearTitle) }
        IosSectionTitle(gt("测试记录", "Test history"))
        fun loadHistory(reset: Boolean) {
            val cursor = if (reset) null else next
            runner.submit(ChannelToolCommand(if (reset) historyTitle else moreTitle, recovery = reset) {
                val page = tools.testHistory(id, cursor)
                val rows = page["edges"].arr.map { it["node"] }
                if (!reset && rows.any { row -> history.any { it["id"] == row["id"] } }) throw cc.khixang.axonhub.network.AxonException.InvalidResponse
                history = if (reset) rows else history + rows
                next = if (page["pageInfo"]["hasNextPage"].boolOrNull == true) page["pageInfo"]["endCursor"].text else null
                historyLoaded = true; null
            })
        }
        GatewayOutlineButton(onClick = { loadHistory(true) }) { Text(historyTitle) }
        history.forEach { row -> GatewayGroupedCard { Text("${row["modelID"].text} · ${row["status"].text}"); Text("${row["createdAt"].text} · ${row["metricsLatencyMs"].text} ms", style = MaterialTheme.typography.bodySmall) } }
        if (historyLoaded && next != null) GatewayOutlineButton(onClick = { loadHistory(false) }) { Text(moreTitle) }
        IosSectionTitle(gt("复制渠道", "Duplicate channel"))
        GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(duplicateTitle, confirm = true, recovery = true) {
            val secrets = tools.channelKeys(id, true)
            if (secrets["updatedAt"].text.isBlank() || secrets["updatedAt"] != initial["updatedAt"]) throw cc.khixang.axonhub.network.AxonException.TargetChanged
            val complete = JsonObject(initial.obj + secrets.obj)
            duplicate = app.adminCatalog.schema.project(complete, "CreateChannelInput!").obj.let { JsonObject(it + ("name" to JsonPrimitive(initial["name"].text + " copy"))) }
            duplicateAuthorized = true; null
        }) }) { Text(duplicateTitle) }
        if (duplicateAuthorized) {
            // Full configuration includes arbitrary header/body values: keep every text field protected.
            SchemaValueEditor(app.adminCatalog.schema, "CreateChannelInput!", duplicate, { duplicate = it }, gt("复制配置", "Duplication configuration"), sensitive = true)
            GatewayButton(onClick = { val input = duplicate.obj; runner.submit(ChannelToolCommand(createTitle, write = true) { tools.duplicateChannel(id, input, true); app.repository.refresh(); null }) }, enabled = !runner.uncertain) { Text(createTitle) }
        }
    }
}
