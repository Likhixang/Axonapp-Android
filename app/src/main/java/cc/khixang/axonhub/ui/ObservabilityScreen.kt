package cc.khixang.axonhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import cc.khixang.axonhub.R
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.observability.ObserveKind
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable fun ObservabilityScreen(app: AxonHubApplication, back: () -> Unit) {
    var kind by remember { mutableStateOf(ObserveKind.REQUESTS) }; var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }; var cursor by remember { mutableStateOf<String?>(null) }; var total by remember { mutableStateOf<Int?>(null) }; var search by remember { mutableStateOf("") }; var status by remember { mutableStateOf("all") }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var detail by remember { mutableStateOf<JsonElement?>(null) }; val scope = rememberCoroutineScope()
    suspend fun load(append: Boolean = false) { busy = true; try { val where = buildJsonObject { if (search.isNotBlank()) put(if (kind == ObserveKind.REQUESTS || kind == ObserveKind.USAGE) "modelIDContainsFold" else if (kind == ObserveKind.TRACES) "traceIDContainsFold" else "threadIDContainsFold", search); if (status != "all" && kind != ObserveKind.USAGE) put("statusIn", buildJsonArray { add(status) }) }; val page = app.observability.page(kind, 25, if (append) cursor else null, where); rows = if (append) rows + page.items else page.items; cursor = page.endCursor; total = page.total; error = null } catch (t: Throwable) { error = t.message } finally { busy = false } }
    LaunchedEffect(kind) { rows = emptyList(); cursor = null; load() }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspaceBack(back)
        IosPageHeader(stringResource(R.string.ws_observability), stringResource(R.string.ws_observability_help)) {
            IconButton(onClick = { scope.launch { load() } }, enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.ws_refresh)) }
        }
        IosCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EnumDropdown(stringResource(R.string.ws_record_type), kind, ObserveKind.entries, { it.name.lowercase() }) { kind = it }
                IosSearchField(search, { search = it }, stringResource(R.string.ws_search_records))
                if (kind != ObserveKind.USAGE) EnumDropdown(stringResource(R.string.ws_status), status, listOf("all", "pending", "processing", "completed", "failed", "canceled", "active", "archived", "retained")) { status = it }
                Button(onClick = { scope.launch { load() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ws_apply)) }
            }
        }
        total?.let { Text(stringResource(R.string.ws_count, rows.size, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorState(it) { scope.launch { load() } } }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!busy && error == null && rows.isEmpty()) item { WorkspaceEmpty(stringResource(R.string.ws_no_records), stringResource(R.string.ws_no_records_help)) }
            items(rows, key = { it["id"].text }) { row ->
                IosCard(Modifier.fillMaxWidth(), onClick = { detail = row }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(listOf("modelID", "traceID", "threadID", "requestID").firstNotNullOfOrNull { row[it].text.takeIf(String::isNotBlank) } ?: row["createdAt"].text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(listOf("status", "createdAt").mapNotNull { row[it].text.takeIf(String::isNotBlank) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            row["firstUserQuery"].text.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodyMedium) }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (cursor != null) item { OutlinedButton(onClick = { scope.launch { load(true) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ws_next_page)) } }
        }
    }
    detail?.let { ObservabilityDetailDialog(app, kind, it["id"].text, { detail = null }) }
}

@Composable private fun ObservabilityDetailDialog(app: AxonHubApplication, kind: ObserveKind, id: String, dismiss: () -> Unit) {
    var record by remember { mutableStateOf<JsonElement>(JsonNull) }; var content by remember { mutableStateOf<JsonElement>(JsonNull) }; var relation by remember { mutableStateOf<String?>(null) }; var related by remember { mutableStateOf<List<JsonObject>>(emptyList()) }; var relationCursor by remember { mutableStateOf<String?>(null) }; var relatedDetail by remember { mutableStateOf<JsonElement>(JsonNull) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var confirm by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    suspend fun load() { busy = true; try { record = app.observability.detail(kind, id); error = null } catch (t: Throwable) { error = t.message } finally { busy = false } }
    suspend fun loadRelation(name: String, append: Boolean = false) { busy = true; try { val page = app.observability.related(kind, id, name, 25, if (append) relationCursor else null); relation = name; related = if (append) related + page.items else page.items; relationCursor = page.endCursor; relatedDetail = JsonNull; error = null } catch (t: Throwable) { error = t.message } finally { busy = false } }
    LaunchedEffect(id) { load() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(kind.name.lowercase().replaceFirstChar(Char::uppercase)) }, text = { LazyColumn(Modifier.heightIn(max = 650.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }; error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }; if (record !is JsonNull) item { DetailFields(record, record.obj.keys.sorted()) }
        if (kind == ObserveKind.REQUESTS || kind == ObserveKind.TRACES) item { OutlinedButton({ scope.launch { busy = true; try { content = app.observability.content(kind, id) } catch (t: Throwable) { error = t.message } finally { busy = false } } }, Modifier.fillMaxWidth()) { Text("Read redacted body and diagnostic content") } }
        if (content !is JsonNull) { item { Text("Conversation inspector", style = MaterialTheme.typography.titleMedium); ConversationInspector(content) }; item { Text("Sanitized payload", style = MaterialTheme.typography.titleMedium); DetailFields(content, content.obj.keys.sorted()) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { when (kind) { ObserveKind.REQUESTS -> { OutlinedButton({ scope.launch { loadRelation("executions") } }) { Text("Executions") }; OutlinedButton({ scope.launch { loadRelation("usageLogs") } }) { Text("Usage") } }; ObserveKind.TRACES -> OutlinedButton({ scope.launch { loadRelation("requests") } }) { Text("Requests") }; ObserveKind.THREADS -> OutlinedButton({ scope.launch { loadRelation("traces") } }) { Text("Traces") }; ObserveKind.USAGE -> Unit } } }
        if (relation != null) { item { Text(relation!!.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleMedium) }; if (related.isEmpty()) item { Text("No related records") } else items(related, key = { it["id"].text }) { row -> OutlinedCard(onClick = { if (kind == ObserveKind.REQUESTS && relation == "executions") scope.launch { busy = true; try { relatedDetail = app.observability.executionContent(row["id"].text) } catch (t: Throwable) { error = t.message } finally { busy = false } } else relatedDetail = row }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(row["id"].text, style = MaterialTheme.typography.labelLarge); Text(listOf("modelID", "status", "createdAt").mapNotNull { key -> row[key].text.takeIf(String::isNotBlank) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall) } } }; if (relationCursor != null) item { OutlinedButton({ scope.launch { loadRelation(relation!!, true) } }, Modifier.fillMaxWidth()) { Text("Load next related page") } } }
        if (relatedDetail !is JsonNull) item { Text("Related record", style = MaterialTheme.typography.titleMedium); DetailFields(relatedDetail, relatedDetail.obj.keys.sorted()) }
        if (kind == ObserveKind.TRACES || kind == ObserveKind.THREADS) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ confirm = if (record["status"].text == "archived") "unarchive" else "archive" }) { Text(if (record["status"].text == "archived") "Unarchive" else "Archive") }; OutlinedButton({ confirm = if (record["status"].text == "retained") "unretain" else "retain" }) { Text(if (record["status"].text == "retained") "Unretain" else "Retain") } } }
    } }, confirmButton = { TextButton(dismiss) { Text("Close") } })
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Confirm $action") }, text = { Text(if (kind == ObserveKind.THREADS) "Thread operations can cascade to related traces." else "This changes the live record state.") }, confirmButton = { Button(onClick = { scope.launch { try { record = app.observability.changeRetention(kind, id, action) } catch (t: Throwable) { error = t.message }; confirm = null } }) { Text("Confirm") } }, dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }) }
}

@Composable private fun ConversationInspector(content: JsonElement) {
    val messages = buildList<Pair<String, JsonElement>> {
        val request = content["requestBody"]
        listOf("instructions", "system", "systemInstruction").forEach { key -> if (request[key] !is JsonNull) add("system" to request[key]) }
        listOf("messages", "contents", "input").forEach { key -> request[key].arr.forEach { add(it["role"].text.ifBlank { it["type"].text.ifBlank { "user" } } to it) } }
        if (request["prompt"] !is JsonNull) add("user" to request["prompt"])
        val response = content["responseBody"]
        response["choices"].arr.forEach { choice -> add("assistant" to (choice["message"].takeUnless { it is JsonNull } ?: choice["text"])) }
        response["output"].arr.forEach { add(it["role"].text.ifBlank { "assistant" } to it) }
        if (response["content"] !is JsonNull) add("assistant" to response["content"])
        response["candidates"].arr.forEach { add("assistant" to it["content"]) }
    }
    if (messages.isEmpty()) Text("No saved conversation content") else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { messages.forEach { (role, value) -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(role, style = MaterialTheme.typography.labelLarge); Text(if (value is JsonPrimitive && value.isString) value.text else value.toString(), style = MaterialTheme.typography.bodySmall) } } } }
}
