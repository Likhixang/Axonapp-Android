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
import cc.khixang.axonhub.observability.*
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable fun ObservabilityScreen(app: AxonHubApplication, embedded: Boolean = false, back: () -> Unit) {
    val instanceId by app.repository.selectedId.collectAsState()
    val projectId by app.repository.projectId.collectAsState()
    val instances by app.repository.instances.collectAsState()
    val snapshot by app.repository.snapshot.collectAsState()
    val fence = remember(instanceId, projectId, instances, snapshot) { runCatching { app.repository.currentFence() }.getOrNull() }
    if (fence == null) Column(Modifier.padding(16.dp)) { if (!embedded) WorkspaceBack(back); Text(stringResource(R.string.ax_analytics_no_instance)) }
    else key(fence) { ObservabilityBoundScreen(app, fence, embedded, back) }
}

@Composable private fun ObservabilityBoundScreen(app: AxonHubApplication, fence: TargetFence, embedded: Boolean, back: () -> Unit) {
    var kind by remember { mutableStateOf(ObserveKind.REQUESTS) }; var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }; var cursor by remember { mutableStateOf<String?>(null) }; var total by remember { mutableStateOf<Int?>(null) }; var search by remember { mutableStateOf("") }; var status by remember { mutableStateOf("all") }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var detail by remember { mutableStateOf<JsonElement?>(null) }; val scope = rememberCoroutineScope()
    var filters by remember { mutableStateOf(AuditFilter()) }
    var filtersOpen by remember { mutableStateOf(false) }
    var ascending by remember { mutableStateOf(false) }
    var pageSize by remember { mutableIntStateOf(25) }
    var appliedWhere by remember { mutableStateOf<JsonObject>(buildJsonObject {}) }
    var appliedAscending by remember { mutableStateOf(false) }
    var appliedPageSize by remember { mutableIntStateOf(25) }
    val requestFailed = stringResource(R.string.ax_analytics_failed)
    val invalidFilter = stringResource(R.string.ax_analytics_invalid_filter)
    suspend fun load(append: Boolean = false) {
        if (busy) return
        busy = true
        try {
            app.repository.verify(fence)
            val where = if (append) appliedWhere else filters.copy(search = search, statuses = if (status == "all") emptySet() else setOf(status)).json(kind)
            val requestedPageSize = if (append) appliedPageSize else pageSize
            val requestedAscending = if (append) appliedAscending else ascending
            val page = app.observability.page(kind, requestedPageSize, if (append) cursor else null, where, requestedAscending, fence)
            app.repository.verify(fence)
            if (append && cursor != null && page.endCursor == cursor) throw IllegalStateException("Repeated cursor")
            rows = (if (append) rows + page.items else page.items).distinctBy { it["id"].text }
            appliedWhere = where; if (!append) { appliedAscending = requestedAscending; appliedPageSize = requestedPageSize }; cursor = page.endCursor; total = page.total; error = null
            filtersOpen = false
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { if (runCatching { app.repository.verify(fence) }.isSuccess) error = if (failure is IllegalArgumentException) invalidFilter else requestFailed } finally { busy = false }
    }
    LaunchedEffect(kind) { rows = emptyList(); cursor = null; total = null; detail = null; status = "all"; load() }
    if (!embedded) BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!embedded) WorkspaceBack(back)
        IosPageHeader(stringResource(R.string.ws_observability), stringResource(R.string.ws_observability_help)) {
            IconButton(onClick = { scope.launch { load() } }, enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.ws_refresh)) }
        }
        IosCard(Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EnumDropdown(stringResource(R.string.ws_record_type), kind, ObserveKind.entries, { it.name.lowercase() }) { if (!busy) kind = it }
                IosSearchField(search, { if (!busy) search = it }, stringResource(R.string.ws_search_records))
                if (kind != ObserveKind.USAGE) EnumDropdown(stringResource(R.string.ws_status), status, listOf("all") + auditStatuses(kind)) { status = it }
                TextButton({ filtersOpen = !filtersOpen }) { Text(stringResource(R.string.ax_analytics_filters)) }
                if (filtersOpen) {
                    OutlinedTextField(filters.projects, { filters = filters.copy(projects = it) }, label = { Text(stringResource(R.string.ax_analytics_project)) }, modifier = Modifier.fillMaxWidth())
                    if (kind == ObserveKind.REQUESTS || kind == ObserveKind.USAGE) {
                        OutlinedTextField(filters.channels, { filters = filters.copy(channels = it) }, label = { Text(stringResource(R.string.ax_analytics_channel)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(filters.apiKeys, { filters = filters.copy(apiKeys = it) }, label = { Text(stringResource(R.string.ax_analytics_key)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(filters.format, { filters = filters.copy(format = it) }, label = { Text("format") }, modifier = Modifier.fillMaxWidth())
                        EnumDropdown("source", filters.sources.firstOrNull() ?: "all", listOf("all", "api", "playground", "test")) { filters = filters.copy(sources = if (it == "all") emptySet() else setOf(it)) }
                    }
                    if (kind == ObserveKind.REQUESTS) {
                        OutlinedTextField(filters.clientIP, { filters = filters.copy(clientIP = it) }, label = { Text("clientIP") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(filters.externalID, { filters = filters.copy(externalID = it) }, label = { Text("externalID") }, modifier = Modifier.fillMaxWidth())
                        EnumDropdown("stream", filters.stream, listOf("all", "yes", "no")) { filters = filters.copy(stream = it) }
                    }
                    OutlinedTextField(filters.start.orEmpty(), { filters = filters.copy(start = it.takeIf(String::isNotBlank)) }, label = { Text(stringResource(R.string.ax_analytics_start).substringBefore(" YYYY") + " · ISO 8601") }, placeholder = { Text("YYYY-MM-DDThh:mm:ss±hh:mm") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(filters.end.orEmpty(), { filters = filters.copy(end = it.takeIf(String::isNotBlank)) }, label = { Text(stringResource(R.string.ax_analytics_end).substringBefore(" YYYY") + " · ISO 8601") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("CREATED_AT ↑"); Switch(ascending, { ascending = it }) }
                    EnumDropdown(stringResource(R.string.ws_next_page), pageSize, listOf(10, 25, 50, 100), { it.toString() }) { pageSize = it }
                }
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
                            Text(listOf("modelID", "traceID", "threadID", "requestID").firstNotNullOfOrNull { row[it].text.takeIf(String::isNotBlank) } ?: DisplayFormat.date(row["createdAt"].text).ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(listOf("status", "createdAt").mapNotNull { row[it].text.takeIf(String::isNotBlank)?.let { value -> if (it == "createdAt") DisplayFormat.date(value) else value } }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            row["firstUserQuery"].text.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodyMedium) }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (cursor != null) item { OutlinedButton(onClick = { scope.launch { load(true) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ws_next_page)) } }
        }
    }
    detail?.let { ObservabilityDetailDialog(app, kind, it["id"].text, fence, { detail = null }) }
}

@Composable private fun ObservabilityDetailDialog(app: AxonHubApplication, kind: ObserveKind, id: String, fence: TargetFence, dismiss: () -> Unit) {
    var record by remember { mutableStateOf<JsonElement>(JsonNull) }; var content by remember { mutableStateOf<JsonElement>(JsonNull) }; var relation by remember { mutableStateOf<String?>(null) }; var related by remember { mutableStateOf<List<JsonObject>>(emptyList()) }; var relationCursor by remember { mutableStateOf<String?>(null) }; var relatedDetail by remember { mutableStateOf<JsonElement>(JsonNull) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var confirm by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    val requestFailed = stringResource(R.string.ax_analytics_failed)
    suspend fun load() { if (busy) return; busy = true; try { record = app.observability.detail(kind, id, fence); error = null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = requestFailed } finally { busy = false } }
    suspend fun loadRelation(name: String, append: Boolean = false) { if (busy) return; busy = true; if (!append) { related = emptyList(); relationCursor = null; relatedDetail = JsonNull }; try { val page = app.observability.related(kind, id, name, 25, if (append) relationCursor else null, fence); relation = name; if (append && relationCursor != null && page.endCursor == relationCursor) throw IllegalStateException("Repeated cursor"); related = (if (append) related + page.items else page.items).distinctBy { it["id"].text }; relationCursor = page.endCursor; relatedDetail = JsonNull; error = null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = requestFailed } finally { busy = false } }
    LaunchedEffect(id) { load() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(kind.name.lowercase().replaceFirstChar(Char::uppercase)) }, text = { LazyColumn(Modifier.heightIn(max = 650.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }; error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }; if (record !is JsonNull) item { AnalyticsValue(record) }
        if (kind == ObserveKind.REQUESTS || kind == ObserveKind.TRACES) item { OutlinedButton({ scope.launch { if (busy) return@launch; busy = true; try { content = app.observability.content(kind, id, fence) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = requestFailed } finally { busy = false } } }, Modifier.fillMaxWidth()) { Text("Read redacted body and diagnostic content") } }
        if (content !is JsonNull) { item { TextButton({ content = JsonNull }) { Text(stringResource(R.string.ws_hide_values)) } }; item { Text("Conversation inspector", style = MaterialTheme.typography.titleMedium); ConversationInspector(content) }; item { Text("Sanitized payload", style = MaterialTheme.typography.titleMedium); AnalyticsValue(content) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { when (kind) { ObserveKind.REQUESTS -> { OutlinedButton({ scope.launch { loadRelation("executions") } }) { Text("Executions") }; OutlinedButton({ scope.launch { loadRelation("usageLogs") } }) { Text("Usage") } }; ObserveKind.TRACES -> OutlinedButton({ scope.launch { loadRelation("requests") } }) { Text("Requests") }; ObserveKind.THREADS -> OutlinedButton({ scope.launch { loadRelation("traces") } }) { Text("Traces") }; ObserveKind.USAGE -> Unit } } }
        if (relation != null) { item { Text(relation!!.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleMedium) }; if (related.isEmpty()) item { Text("No related records") } else items(related, key = { it["id"].text }) { row -> OutlinedCard(onClick = { if (kind == ObserveKind.REQUESTS && relation == "executions") scope.launch { if (busy) return@launch; busy = true; try { relatedDetail = app.observability.executionContent(row["id"].text, fence) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = requestFailed } finally { busy = false } } else relatedDetail = row }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(row["id"].text, style = MaterialTheme.typography.labelLarge); Text(listOf("modelID", "status", "createdAt").mapNotNull { key -> row[key].text.takeIf(String::isNotBlank) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall) } } }; if (relationCursor != null) item { OutlinedButton({ scope.launch { loadRelation(relation!!, true) } }, Modifier.fillMaxWidth()) { Text("Load next related page") } } }
        if (relatedDetail !is JsonNull) item { Text("Related record", style = MaterialTheme.typography.titleMedium); AnalyticsValue(relatedDetail) }
        if ((kind == ObserveKind.TRACES || kind == ObserveKind.THREADS) && record !is JsonNull && !busy) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ confirm = if (record["status"].text == "archived") "unarchive" else "archive" }) { Text(if (record["status"].text == "archived") "Unarchive" else "Archive") }; OutlinedButton({ confirm = if (record["status"].text == "retained") "unretain" else "retain" }) { Text(if (record["status"].text == "retained") "Unretain" else "Retain") } } }
    } }, confirmButton = { TextButton(dismiss) { Text("Close") } })
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Confirm $action") }, text = { Text(if (kind == ObserveKind.THREADS) "Thread operations can cascade to related traces." else "This changes the live record state.") }, confirmButton = { Button(onClick = { scope.launch { if (busy) return@launch; busy = true; try { record = app.observability.changeRetention(kind, id, action, fence) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = requestFailed } finally { busy = false }; confirm = null } }) { Text("Confirm") } }, dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }) }
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
