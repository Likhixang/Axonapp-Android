package cc.khixang.axonhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.gateway.GatewayToolsService
import kotlinx.serialization.json.*

/** Override values are arbitrary secrets; never render a raw JSON or plaintext preview. */
@Composable internal fun ChannelTemplatesDialog(app: AxonHubApplication, id: String? = null, dismiss: () -> Unit) {
    val tools = remember { GatewayToolsService(app.repository, app.adminCatalog) }
    val schema = app.adminCatalog.schema
    var records by remember { mutableStateOf(listOf<JsonElement>()) }
    var authorized by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf("") }
    var input by remember { mutableStateOf<JsonElement>(schema.defaultValue("CreateChannelOverrideTemplateInput!")) }
    var selected by remember { mutableStateOf(id?.let { setOf(it) } ?: emptySet<String>()) }
    var replace by remember { mutableStateOf(false) }
    val state by app.repository.snapshot.collectAsState()
    val rows = (state as? LoadState.Ready)?.value?.channels.orEmpty()
    val readTitle = gt("授权读取模板", "Authorize template read")
    val saveTitle = gt("保存模板", "Save template")
    val deleteTitle = gt("删除模板", "Delete template")
    val applyTitle = gt("应用模板", "Apply template")
    val clearTitle = gt("清空覆盖项", "Clear overrides")
    val newTitle = gt("新增模板", "New template")
    ChannelToolSurface(app, gt("渠道覆盖模板", "Channel override templates"), dismiss) { runner ->
        Text(gt("模板可能包含凭据。授权后仅在受保护字段中编辑，离开页面即丢弃。", "Templates may contain credentials. After authorization they are edited only in protected fields and discarded on exit."), style = MaterialTheme.typography.bodySmall)
        GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(readTitle, confirm = true, recovery = true) {
            records = tools.channelTemplates(true); authorized = true
            selectedId = ""; input = schema.defaultValue("CreateChannelOverrideTemplateInput!"); null
        }) }) { Text(if (authorized) gt("重新读取模板", "Reload templates") else readTitle) }
        if (authorized) {
            val labels = listOf(newTitle) + records.map { "${it["name"].text} · ${it["id"].text}" }
            val index = records.indexOfFirst { it["id"].text == selectedId }
            GatewayEnum(gt("模板", "Template"), labels[index + 1], labels) { label ->
                val selectedIndex = labels.indexOf(label) - 1
                val record = records.getOrNull(selectedIndex)
                selectedId = record?.get("id")?.text.orEmpty()
                input = if (record == null) schema.defaultValue("CreateChannelOverrideTemplateInput!") else schema.project(record, "CreateChannelOverrideTemplateInput!")
            }
            key(selectedId) { SchemaValueEditor(schema, "CreateChannelOverrideTemplateInput!", input, { input = it }, gt("模板配置", "Template configuration"), sensitive = true) }
            GatewayButton(onClick = {
                val capturedId = selectedId.takeIf(String::isNotBlank); val captured = input.obj
                val baseline = records.firstOrNull { it["id"].text == capturedId } ?: JsonNull
                runner.submit(ChannelToolCommand(saveTitle, write = true) {
                    selectedId = tools.saveTemplate(capturedId, captured, baseline, true)
                    records = tools.channelTemplates(true); null
                })
            }, enabled = !runner.uncertain) { Text(saveTitle) }
            GatewayTextButton(onClick = {
                val captured = selectedId; val baseline = records.first { it["id"].text == captured }
                runner.submit(ChannelToolCommand(deleteTitle, write = true, destructive = true) {
                    tools.deleteTemplate(captured, baseline, true); records = tools.channelTemplates(true)
                    selectedId = ""; input = schema.defaultValue("CreateChannelOverrideTemplateInput!"); null
                })
            }, enabled = selectedId.isNotBlank() && !runner.uncertain, destructive = true) { Text(deleteTitle) }
            IosSectionTitle(gt("应用到渠道", "Apply to channels"))
            rows.forEach { row -> Row { Text(row.name, Modifier.weight(1f)); Checkbox(row.id in selected, { selected = if (it) selected + row.id else selected - row.id }) } }
            IosSegmentedControl(listOf(gt("合并", "Merge"), gt("替换", "Replace")), if (replace) 1 else 0, { replace = it == 1 })
            Text(gt("替换会清除现有覆盖项；合并保留不冲突的项。", "Replace removes existing overrides; merge retains non-conflicting operations."), style = MaterialTheme.typography.bodySmall)
            GatewayButton(onClick = {
                val template = records.first { it["id"].text == selectedId }; val ids = selected.sorted(); val capturedMode = replace
                runner.submit(ChannelToolCommand("$applyTitle · ${ids.joinToString()}", write = true, destructive = replace) { tools.applyTemplate(template, ids, capturedMode, true); null })
            }, enabled = selectedId.isNotBlank() && selected.isNotEmpty() && !runner.uncertain) { Text(applyTitle) }
            GatewayTextButton(onClick = {
                val ids = selected.sorted()
                runner.submit(ChannelToolCommand("$clearTitle · ${ids.joinToString()}", write = true, destructive = true) { tools.applyTemplate(null, ids, false, true); null })
            }, enabled = selected.isNotEmpty() && !runner.uncertain, destructive = true) { Text(clearTitle) }
        }
    }
}
