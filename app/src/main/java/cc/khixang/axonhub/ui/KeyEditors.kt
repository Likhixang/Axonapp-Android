package cc.khixang.axonhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.management.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

@Composable internal fun ManagementDetails(value: JsonElement, depth: Int = 0) {
    val strings = keyLocalization()
    when (value) {
        is JsonObject -> value.filterKeys { !SensitiveFields.matches(it) && it !in setOf("key", "errorMessage", "__typename") }.forEach { (key, item) ->
            if (item !is JsonNull) Column(Modifier.padding(start = (depth * 4).dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.label(key), style = MaterialTheme.typography.labelMedium)
                if (item is JsonPrimitive) Text(ManagementFormat.display(key, item.text), style = MaterialTheme.typography.bodySmall)
                else ManagementDetails(item, depth + 1)
            }
        }
        is JsonArray -> value.forEach { ManagementDetails(it, depth + 1) }
        is JsonPrimitive -> Text(value.text, style = MaterialTheme.typography.bodySmall)
        else -> Unit
    }
}

@Composable internal fun KeyInputFields(operation: AdminOperation, baseline: JsonElement, input: JsonObject, onChange: (JsonObject) -> Unit) {
    val strings = keyLocalization()
    val creating = operation.id == "createAPIKey"
    val type = if (creating) input["type"].text.ifBlank { "user" } else baseline["type"].text
    fun set(key: String, value: JsonElement) = onChange(JsonObject(input + (key to value)))
    IosSectionTitle(strings.text("Basic information"))
    OutlinedTextField(input["name"].text, { set("name", JsonPrimitive(it)) }, label = { Text(strings.text("Name")) }, modifier = Modifier.fillMaxWidth())
    if (creating) EnumDropdown(strings.text("Key type"), type, listOf("user", "service_account", "noauth", "personal"), label = { strings.option(it) }) { next ->
        onChange(KeyEditorPolicy.editableKeyInput(JsonObject(input + ("type" to JsonPrimitive(next))), next))
    }
    IosSectionTitle(strings.text("Access restrictions"))
    KeyStringList(strings.text("Allowed IPs / CIDRs"), input["allowedIps"].arr.map { it.text }) { set("allowedIps", JsonArray(it.map(::JsonPrimitive))) }
    if (KeyEditorPolicy.canEditScopes(type)) KeyStringList(strings.text("Permissions"), input["scopes"].arr.map { it.text }) { set("scopes", JsonArray(it.map(::JsonPrimitive))) }
    else Text(strings.text("Project keys use project permissions; only service-account keys have editable permissions."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun KeyStringList(title: String, values: List<String>, onChange: (List<String>) -> Unit) {
    val strings = keyLocalization()
    var text by remember(title) { mutableStateOf(values.joinToString("\n")) }
    LaunchedEffect(values) {
        if (text.lines().map(String::trim).filter(String::isNotBlank).distinct() != values) text = values.joinToString("\n")
    }
    OutlinedTextField(text, { next -> text = next; onChange(next.lines().map(String::trim).filter(String::isNotBlank).distinct()) },
        label = { Text(title) }, supportingText = { Text(strings.text("One item per line; leave blank for no restriction")) }, modifier = Modifier.fillMaxWidth())
}

@Composable internal fun KeyProfileFields(app: AxonHubApplication, value: JsonObject, isKey: Boolean, onChange: (JsonObject) -> Unit) {
    val strings = keyLocalization()
    val profiles = value["profiles"].arr
    var remove by remember { mutableStateOf<Int?>(null) }
    EnumDropdown(strings.text("Active Profile"), value["activeProfile"].text, profiles.map { it["name"].text }) { onChange(JsonObject(value + ("activeProfile" to JsonPrimitive(it)))) }
    profiles.forEachIndexed { index, profile ->
        key(index) {
            var expanded by remember { mutableStateOf(profiles.size == 1) }
            IosCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton({ expanded = !expanded }, Modifier.fillMaxWidth()) {
                        Text(profile["name"].text, Modifier.weight(1f))
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) strings.text("Collapse") else strings.text("Expand"))
                    }
                    if (expanded) {
                        fun set(field: String, next: JsonElement?) {
                            val fields = profile.obj.toMutableMap()
                            if (next == null) fields.remove(field) else fields[field] = next
                            val list = profiles.toMutableList(); list[index] = JsonObject(fields)
                            onChange(KeyEditorPolicy.replaceProfiles(value, list))
                        }
                        OutlinedTextField(profile["name"].text, { onChange(KeyEditorPolicy.renameProfile(value, index, it)) }, label = { Text(strings.text("Profile name")) }, modifier = Modifier.fillMaxWidth())
                        if (profile["templateName"].text.isNotBlank()) {
                            Text(strings.text("Template: %s", profile["templateName"].text))
                            TextButton({
                                val list = profiles.toMutableList(); list[index] = JsonObject(profile.obj.filterKeys { it !in setOf("templateID", "templateName") })
                                onChange(KeyEditorPolicy.replaceProfiles(value, list))
                            }) { Text(strings.text("Unlink template")) }
                        }
                        IosSectionTitle(strings.text("Channel restrictions"))
                        val snapshot by app.repository.snapshot.collectAsState()
                        val data = (snapshot as? LoadState.Ready)?.value
                        val selected = profile["channelIDs"].arr.mapNotNull { it.intOrNull }
                        data?.channels?.forEach { channel ->
                            val numeric = KeyEditorPolicy.channelNumericId(channel.id)
                            if (numeric != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(channel.name, Modifier.weight(1f)); Checkbox(numeric in selected, { checked -> set("channelIDs", JsonArray((if (checked) selected + numeric else selected - numeric).distinct().map(::JsonPrimitive))) })
                            }
                        }
                        if (data == null) Text(strings.text("Refresh the workspace to load channel choices."), style = MaterialTheme.typography.bodySmall)
                        KeyStringList(strings.text("Channel tags"), profile["channelTags"].arr.map { it.text }) { set("channelTags", JsonArray(it.map(::JsonPrimitive))) }
                        EnumDropdown(strings.text("Tag matching"), profile["channelTagsMatchMode"].text.ifBlank { "any" }, listOf("any", "all", "none"), label = { strings.option(it) }) { set("channelTagsMatchMode", JsonPrimitive(it)) }
                        if (isKey) {
                            IosSectionTitle(strings.text("Model permissions and mappings"))
                            var modelSearch by remember { mutableStateOf("") }
                            val selectedModels = profile["modelIDs"].arr.map { it.text }
                            val models = (data?.models?.map { it.modelId }.orEmpty() + data?.channels?.flatMap { it.supportedModels }.orEmpty() + selectedModels).distinct().sorted().filter { it.contains(modelSearch, true) }
                            IosSearchField(modelSearch, { modelSearch = it }, strings.text("Search models"))
                            Row {
                                TextButton({ set("modelIDs", JsonArray((selectedModels + models).distinct().map(::JsonPrimitive))) }) { Text(strings.text("Select all")) }
                                TextButton({ set("modelIDs", JsonArray((selectedModels - models.toSet()).map(::JsonPrimitive))) }) { Text(strings.text("Deselect all")) }
                            }
                            models.forEach { model -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(model, Modifier.weight(1f)); Checkbox(model in selectedModels, { checked -> set("modelIDs", JsonArray((if (checked) selectedModels + model else selectedModels - model).distinct().map(::JsonPrimitive))) })
                            } }
                            KeyStringList(strings.text("Allowed model IDs (manual)"), selectedModels) { set("modelIDs", JsonArray(it.map(::JsonPrimitive))) }
                            profile["modelMappings"].arr.forEachIndexed { mappingIndex, mapping ->
                                fun mappingSet(field: String, text: String) {
                                    val mappings = profile["modelMappings"].arr.toMutableList(); mappings[mappingIndex] = JsonObject(mapping.obj + (field to JsonPrimitive(text))); set("modelMappings", JsonArray(mappings))
                                }
                                OutlinedTextField(mapping["from"].text, { mappingSet("from", it) }, label = { Text(strings.text("Requested model")) }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(mapping["to"].text, { mappingSet("to", it) }, label = { Text(strings.text("Actual model")) }, modifier = Modifier.fillMaxWidth())
                                TextButton({ set("modelMappings", JsonArray(profile["modelMappings"].arr.filterIndexed { i, _ -> i != mappingIndex })) }) { Text(strings.text("Remove mapping")) }
                            }
                            TextButton({ set("modelMappings", JsonArray(profile["modelMappings"].arr + buildJsonObject { put("from", ""); put("to", "") })) }) { Text(strings.text("Add model mapping")) }
                            IosSectionTitle(strings.text("Routing"))
                            EnumDropdown(strings.text("Load balancing"), profile["loadBalanceStrategy"].text.ifBlank { "default" }, listOf("default", "adaptive", "failover", "circuit-breaker", "round-robin"), label = { strings.option(it) }) { set("loadBalanceStrategy", JsonPrimitive(it)) }
                            EnumDropdown(strings.text("Trace affinity"), profile["traceStickyMode"].text.ifBlank { "default" }, listOf("default", "disabled", "prefer_previous_channel"), label = { strings.option(it) }) { set("traceStickyMode", JsonPrimitive(it)) }
                            IosSectionTitle(strings.text("Quota limits"))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(strings.text("Enable quota limits"), Modifier.weight(1f)); Switch(profile["quota"] !is JsonNull, { enabled -> set("quota", if (enabled) buildJsonObject { put("period", buildJsonObject { put("type", "all_time") }) } else null) })
                            }
                            if (profile["quota"] !is JsonNull) KeyQuotaFields(profile["quota"].obj) { set("quota", it) }
                        }
                        if (profiles.size > 1) TextButton({ remove = index }) { Text(strings.text("Remove Profile"), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    TextButton({
        var index = profiles.size + 1
        while (profiles.any { it["name"].text == "Profile $index" }) index++
        val draft = buildJsonObject { put("name", "Profile $index"); put("channelIDs", JsonArray(emptyList())); put("channelTags", JsonArray(emptyList())); put("channelTagsMatchMode", "any") }
        onChange(KeyEditorPolicy.replaceProfiles(value, profiles + KeyEditorPolicy.normalizeProfile(draft, isKey)))
    }) { Icon(Icons.Default.Add, null); Text(strings.text("Add Profile")) }
    remove?.let { index -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(strings.text("Remove Profile?")) },
        confirmButton = { Button({ onChange(KeyEditorPolicy.replaceProfiles(value, profiles.filterIndexed { i, _ -> i != index })); remove = null }) { Text(strings.text("Remove")) } },
        dismissButton = { TextButton({ remove = null }) { Text(strings.text("Cancel")) } }) }
}

@Composable private fun KeyQuotaFields(quota: JsonObject, onChange: (JsonObject) -> Unit) {
    val strings = keyLocalization()
    fun set(field: String, value: JsonElement?) = onChange(JsonObject(quota.toMutableMap().apply { if (value == null) remove(field) else put(field, value) }))
    listOf("requests" to "Request limit", "totalTokens" to "Token limit", "cost" to "Cost limit (USD)").forEach { (field, title) ->
        var text by remember(field) { mutableStateOf(quota[field].text) }
        OutlinedTextField(text, { next ->
            text = next
            set(field, if (next.isEmpty()) null else if (field == "cost") JsonPrimitive(next) else next.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(next))
        }, label = { Text(strings.text(title)) }, keyboardOptions = KeyboardOptions(keyboardType = if (field == "cost") KeyboardType.Decimal else KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    }
    val period = quota["period"]
    EnumDropdown(strings.text("Quota period"), period["type"].text, listOf("all_time", "past_duration", "calendar_duration"), label = { strings.option(it) }) { type ->
        set("period", buildJsonObject {
            put("type", type)
            if (type == "past_duration") put("pastDuration", buildJsonObject { put("value", 1); put("unit", "day") })
            if (type == "calendar_duration") put("calendarDuration", buildJsonObject { put("unit", "month") })
        })
    }
    fun setPeriod(group: String, field: String, value: JsonElement) = set("period", JsonObject(period.obj + (group to JsonObject(period[group].obj + (field to value)))))
    if (period["type"].text == "past_duration") {
        OutlinedTextField(period["pastDuration"]["value"].text, { setPeriod("pastDuration", "value", it.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(it)) }, label = { Text(strings.text("Window length")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        EnumDropdown(strings.text("Unit"), period["pastDuration"]["unit"].text, listOf("minute", "hour", "day"), label = { strings.option(it) }) { setPeriod("pastDuration", "unit", JsonPrimitive(it)) }
    }
    if (period["type"].text == "calendar_duration") EnumDropdown(strings.text("Unit"), period["calendarDuration"]["unit"].text, listOf("day", "month"), label = { strings.option(it) }) { setPeriod("calendarDuration", "unit", JsonPrimitive(it)) }
}

@Composable internal fun KeyTemplateFields(app: AxonHubApplication, baseline: JsonElement, input: JsonObject, onChange: (JsonObject) -> Unit) {
    val strings = keyLocalization()
    var templates by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fence = remember { app.repository.currentFence() }
    LaunchedEffect(baseline["id"].text) {
        busy = true
        try {
            app.repository.verify(fence)
            val project = baseline["projectID"].text.ifBlank { fence.projectId.orEmpty() }
            require(project.isNotBlank()) { "Select the key's project first" }
            val records = mutableListOf<JsonObject>()
            var after: String? = null
            val cursors = mutableSetOf<String>()
            do {
                val page = app.admin.page("apiKeyProfileTemplates", buildJsonObject {
                    put("first", 100); put("where", buildJsonObject { put("projectID", project) })
                    after?.let { put("after", it) }
                })
                app.repository.verify(fence)
                records += page.items
                after = page.endCursor
                require(after == null || cursors.add(after)) { "Template pagination did not advance" }
            } while (after != null)
            templates = records.distinctBy { it["id"].text }
        } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = t.message } finally { busy = false }
    }
    IosSectionTitle(strings.text("Policy template"))
    Text(strings.text("Loading a template immediately updates server policy. Save other changes first."), style = MaterialTheme.typography.bodySmall)
    EnumDropdown(strings.text("Template"), input["templateID"].text, listOf("") + templates.map { it["id"].text }, label = { id -> templates.firstOrNull { it["id"].text == id }?.get("name")?.text ?: strings.text("Choose a template") }) {
        onChange(JsonObject(input + ("templateID" to JsonPrimitive(it))))
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!busy && error == null && templates.isEmpty()) Text(strings.text("No templates in this project."), style = MaterialTheme.typography.bodySmall)
    error?.let { Text(strings.text(it), color = MaterialTheme.colorScheme.error) }
}

@Composable internal fun KeyUsage(app: AxonHubApplication, id: String) {
    val strings = keyLocalization()
    var stats by remember(id) { mutableStateOf<JsonElement>(JsonNull) }
    var quotas by remember(id) { mutableStateOf<JsonElement>(JsonNull) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var revision by remember(id) { mutableStateOf(0) }
    val fence = remember(id) { app.repository.currentFence() }
    LaunchedEffect(id, revision) {
        try {
            app.repository.verify(fence)
            val response = app.admin.read("apiKeyTokenUsageStats", buildJsonObject { put("input", buildJsonObject { put("apiKeyIds", JsonArray(listOf(JsonPrimitive(id)))) }) })
            app.repository.verify(fence)
            stats = response.arr.firstOrNull { it["apiKeyId"].text == id } ?: JsonNull
            quotas = app.admin.read("apiKeyQuotaUsages", buildJsonObject { put("apiKeyId", id) })
            app.repository.verify(fence); error = null
        } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = t.message }
    }
    IosSectionTitle(strings.text("Token and quota usage"))
    if (stats is JsonNull && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    listOf("inputTokens", "outputTokens", "cachedTokens", "reasoningTokens").forEach { field ->
        if (stats[field] !is JsonNull) Text("${strings.label(field)}: ${ManagementFormat.compact(stats[field].text)}")
    }
    quotas.arr.forEach { record ->
        IosSectionTitle(record["profileName"].text)
        listOf("requests" to "requestCount", "totalTokens" to "totalTokens", "cost" to "totalCost").forEach { (limitKey, usedKey) ->
            val limit = record["quota"][limitKey]
            if (limit !is JsonNull) {
                val used = record["usage"][usedKey]
                val format: (String) -> String = when (limitKey) { "cost" -> { raw -> ManagementFormat.money(raw) }; "totalTokens" -> { raw -> ManagementFormat.compact(raw) }; else -> { raw -> ManagementFormat.number(raw) } }
                Text("${strings.label(limitKey)}: ${format(used.text)} / ${format(limit.text)}")
                val limitValue = ManagementFormat.parse(limit.text)
                val usedValue = ManagementFormat.parse(used.text)
                if (limitValue != null && usedValue != null && limitValue.signum() > 0) {
                    val progress = usedValue.divide(limitValue, 6, java.math.RoundingMode.HALF_EVEN).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    error?.let { Text(strings.text(it), color = MaterialTheme.colorScheme.error); TextButton({ revision++ }) { Text(strings.text("Retry")) } }
}
