package cc.khixang.axonhub.ui

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import cc.khixang.axonhub.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.management.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

private data class AdminModule(val id: String, val title: String, val list: String, val entity: String, val create: String?, val project: Boolean = false)
private val modules = listOf(
    AdminModule("apiKeys", "API keys", "apiKeys", "APIKey", "createAPIKey", true), AdminModule("templates", "Key policy templates", "apiKeyProfileTemplates", "APIKeyProfileTemplate", "createApiKeyProfileTemplate", true),
    AdminModule("projects", "Projects", "projects", "Project", "createProject"), AdminModule("users", "Users", "users", "User", "createUser"), AdminModule("roles", "System roles", "roles", "Role", "createRole"),
    AdminModule("projectUsers", "Project members", "projectUsers", "ProjectUser", "addUserToProject", true), AdminModule("projectRoles", "Project roles", "roles", "Role", "createRole", true),
    AdminModule("prompts", "Prompts", "prompts", "Prompt", "createPrompt", true), AdminModule("protection", "Prompt protection", "promptProtectionRules", "PromptProtectionRule", "createPromptProtectionRule"),
    AdminModule("storage", "Data storage", "dataStorages", "DataStorage", "createDataStorage"),
)

@Composable fun ManagementScreen(app: AxonHubApplication, initialModule: String? = null) {
    var module by remember { mutableStateOf(initialModule?.let { id -> modules.firstOrNull { it.id == id } }) }
    var system by remember { mutableStateOf(false) }; var observability by remember { mutableStateOf(false) }; var analytics by remember { mutableStateOf(false) }; var advanced by remember { mutableStateOf(false) }
    BackHandler(module != null || system || advanced) { module = null; system = false; advanced = false }
    when {
        analytics -> AnalyticsScreen(app) { analytics = false }
        observability -> ObservabilityScreen(app) { observability = false }
        system -> SystemOperationsScreen(app) { system = false }
        advanced -> AdvancedOperationsScreen(app) { advanced = false }
        module != null -> AdminModuleScreen(app, module!!) { module = null }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { IosPageHeader(stringResource(R.string.ws_management), stringResource(R.string.ws_workspace_help)) }
            item { IosSectionTitle(stringResource(R.string.ws_workspace)) }
            item { IosCard(Modifier.fillMaxWidth()) {
                listOf("projects", "apiKeys", "prompts").forEachIndexed { index, id -> val entry = modules.first { it.id == id }; ManagementMenuRow(moduleTitle(entry), moduleIcon(entry)) { module = entry }; if (index < 2) MenuDivider() }
            } }
            item { IosSectionTitle(stringResource(R.string.ws_audit)) }
            item { IosCard(Modifier.fillMaxWidth()) {
                ManagementMenuRow(stringResource(R.string.ws_observability), Icons.Default.Timeline, stringResource(R.string.ws_observability_help)) { observability = true }
                MenuDivider()
                ManagementMenuRow(stringResource(R.string.ws_analytics), Icons.Default.QueryStats, stringResource(R.string.ws_analytics_help)) { analytics = true }
            } }
            item { IosSectionTitle(stringResource(R.string.ws_security)) }
            item { IosCard(Modifier.fillMaxWidth()) {
                listOf("users", "roles", "projectUsers", "projectRoles", "templates", "protection").forEachIndexed { index, id -> val entry = modules.first { it.id == id }; ManagementMenuRow(moduleTitle(entry), moduleIcon(entry)) { module = entry }; if (index < 5) MenuDivider() }
            } }
            item { IosSectionTitle(stringResource(R.string.ws_operations)) }
            item { IosCard(Modifier.fillMaxWidth()) {
                val entry = modules.first { it.id == "storage" }
                ManagementMenuRow(moduleTitle(entry), moduleIcon(entry)) { module = entry }; MenuDivider()
                ManagementMenuRow(stringResource(R.string.ws_system), Icons.Default.Settings, stringResource(R.string.ws_system_help)) { system = true }; MenuDivider()
                ManagementMenuRow(stringResource(R.string.ws_advanced), Icons.Default.DataObject, stringResource(R.string.ws_advanced_help)) { advanced = true }
            } }
        }
    }
}

@Composable private fun moduleTitle(module: AdminModule): String = stringResource(when (module.id) {
    "apiKeys" -> R.string.ws_api_keys; "templates" -> R.string.ws_templates; "projects" -> R.string.ws_projects; "users" -> R.string.ws_users; "roles" -> R.string.ws_roles; "projectUsers" -> R.string.ws_project_users; "projectRoles" -> R.string.ws_project_roles; "prompts" -> R.string.ws_prompts; "protection" -> R.string.ws_protection; else -> R.string.ws_storage
})
private fun moduleIcon(module: AdminModule): androidx.compose.ui.graphics.vector.ImageVector = when (module.id) {
    "apiKeys" -> Icons.Default.Key; "templates" -> Icons.Default.Policy; "projects" -> Icons.Default.Folder; "users", "projectUsers" -> Icons.Default.People; "roles", "projectRoles" -> Icons.Default.AdminPanelSettings; "prompts" -> Icons.Default.ChatBubbleOutline; "protection" -> Icons.Default.Shield; else -> Icons.Default.Storage
}
@Composable private fun MenuDivider() { HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)) }
@Composable private fun ManagementMenuRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, subtitle: String? = null, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = action).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium); subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun AdminModuleScreen(app: AxonHubApplication, module: AdminModule, back: () -> Unit) {
    var rows by remember(module.id) { mutableStateOf<List<JsonObject>>(emptyList()) }; var cursor by remember(module.id) { mutableStateOf<String?>(null) }; var total by remember { mutableStateOf<Int?>(null) }; var search by remember { mutableStateOf("") }; var project by remember { mutableStateOf<String?>(null) }; var projects by remember { mutableStateOf<List<JsonObject>>(emptyList()) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var selected by remember { mutableStateOf<JsonElement?>(null) }; var operation by remember { mutableStateOf<Pair<AdminOperation, JsonElement>?>(null) }; var invitation by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    var selecting by remember(module.id) { mutableStateOf(false) }
    var selection by remember(module.id) { mutableStateOf<Set<String>>(emptySet()) }
    var bulkAction by remember(module.id) { mutableStateOf<String?>(null) }
    var bulkUncertain by remember(module.id) { mutableStateOf(false) }
    suspend fun load(append: Boolean = false) {
        if (module.project && project.isNullOrBlank()) return
        if (!append) { rows = emptyList(); cursor = null; total = null; selected = null; selection = emptySet(); selecting = false }
        busy = true; error = null
        try {
            app.repository.selectProject(project)
            if (module.id == "projectUsers") {
                val root = app.admin.read(module.list, buildJsonObject { put("projectId", project!!) })
                rows = root["projectUsers"].arr.map { member -> JsonObject(member["user"].obj + ("membership" to member)) }; total = rows.size; cursor = null
            } else {
                val where = buildJsonObject {
                    if (module.project) put("projectID", project!!)
                    if (module.id == "roles") put("level", "system")
                    if (module.id == "projectRoles") put("level", "project")
                    if (search.isNotBlank()) put(if (module.id == "users") "emailContainsFold" else "nameContainsFold", search.trim())
                }
                val vars = buildJsonObject { put("first", 25); if (where.isNotEmpty()) put("where", where); if (append) cursor?.let { put("after", it) } }
                val page = app.admin.page(module.list, vars); rows = if (append) (rows + page.items).distinctBy { it["id"].text } else page.items; cursor = page.endCursor; total = page.total
                if (!append) bulkUncertain = false
            }
        } catch (t: Throwable) { error = t.message } finally { busy = false }
    }
    suspend fun loadProjects() {
        if (busy) return
        busy = true; error = null
        try {
            projects = app.admin.read("myProjects").arr.map(JsonElement::obj)
            val current = app.repository.projectId.value
            project = current?.takeIf { id -> projects.any { it["id"].text == id } } ?: projects.firstOrNull()?.get("id")?.text
            app.repository.selectProject(project)
        } catch (t: Throwable) { error = t.message ?: "Unable to load projects" } finally { busy = false }
    }
    LaunchedEffect(module.id) {
        if (module.project) loadProjects()
        load()
    }
    val selectProjectLabel = stringResource(R.string.ws_select_project)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspaceBack(back)
        IosPageHeader(moduleTitle(module)) {
            if (module.id == "apiKeys") IconButton(enabled = !busy, onClick = { selecting = !selecting; selection = emptySet() }) { Icon(if (selecting) Icons.Default.Close else Icons.Default.Checklist, if (selecting) "Cancel selection" else "Select keys") }
            if (module.id == "projectUsers") IconButton(enabled = !project.isNullOrBlank() && !busy, onClick = { invitation = true }) { Icon(Icons.Default.Link, stringResource(R.string.ws_invitation)) }
            module.create?.let { create -> IconButton(enabled = !busy && (!module.project || !project.isNullOrBlank()), onClick = { operation = app.adminCatalog.operation(create) to JsonNull }) { Icon(Icons.Default.Add, stringResource(R.string.ws_create)) } }
            IconButton(onClick = { scope.launch { load() } }, enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.ws_refresh)) }
        }
        if (module.project) EnumDropdown(stringResource(R.string.ws_projects), project.orEmpty(), listOf("") + projects.map { it["id"].text }, label = { id -> projects.firstOrNull { it["id"].text == id }?.get("name")?.text ?: selectProjectLabel }) { project = it.takeIf(String::isNotBlank); rows = emptyList(); cursor = null; total = null; scope.launch { load() } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IosSearchField(search, { search = it }, stringResource(R.string.ws_search_records), Modifier.weight(1f))
            IconButton(onClick = { scope.launch { load() } }, enabled = !busy) { Icon(Icons.Default.Search, stringResource(R.string.ws_search)) }
        }
        total?.let { Text(stringResource(R.string.ws_count, rows.size, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (selecting && module.id == "apiKeys") {
            Text("${selection.size} selected", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("bulkEnableAPIKeys" to "Enable", "bulkDisableAPIKeys" to "Disable", "bulkArchiveAPIKeys" to "Archive").forEach { (id, label) ->
                    OutlinedButton({ bulkAction = id }, Modifier.weight(1f), enabled = selection.isNotEmpty() && !busy && !bulkUncertain) { Text(label) }
                }
            }
        }
        if (bulkUncertain) Text("The bulk write may have completed. Refresh to verify before retrying.", color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorState(it) { scope.launch { if (module.project && project == null) loadProjects(); load() } } }
        if (!busy && error == null && rows.isEmpty()) WorkspaceEmpty(
            stringResource(if (module.project && project == null) R.string.ws_select_project else R.string.ws_no_records),
            stringResource(if (module.project && project == null) R.string.ws_project_help else R.string.ws_no_records_help))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it["id"].text.ifBlank { it.toString() } }) { row ->
                IosCard(Modifier.fillMaxWidth(), onClick = {
                    if (!busy && !bulkUncertain) {
                        if (selecting) { val id = row["id"].text; selection = if (id in selection) selection - id else selection + id } else selected = row
                    }
                }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) {
                            val id = row["id"].text
                            Checkbox(id in selection, { checked -> selection = if (checked) selection + id else selection - id }, enabled = !busy && !bulkUncertain)
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(recordLabel(row), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            val metadata = listOf("status", "type").mapNotNull { row[it].text.takeIf(String::isNotBlank) }
                            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            row["description"].text.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (module.id != "apiKeys") Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (cursor != null) item { OutlinedButton(onClick = { scope.launch { load(true) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ws_next_page)) } }
        }
    }
    selected?.let { summary -> AdminDetailDialog(app, module, summary, { selected = null }, { op, baseline -> operation = op to baseline; selected = null }, { scope.launch { load() } }) }
    operation?.let { (op, baseline) -> OperationDialog(app, op, baseline, project, { operation = null }, { scope.launch { load() } }) }
    if (invitation && project != null) InvitationDialog(app, project!!, { invitation = false })
    bulkAction?.let { action ->
        val ids = remember(action) { selection.toList() }
        val fence = remember(action) { app.repository.currentFence() }
        AlertDialog(onDismissRequest = { if (!busy) bulkAction = null }, title = { Text(managementLabel(action)) }, text = { Text("${ids.size} selected keys") },
            confirmButton = { Button(enabled = !busy && !bulkUncertain, onClick = {
                scope.launch {
                    busy = true; error = null
                    try {
                        app.repository.verify(fence); bulkUncertain = true
                        app.admin.execute(action, buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) }, expectedFence = fence)
                        app.repository.verify(fence); bulkUncertain = false; load()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (t: Exception) { error = t.message } finally { busy = false; bulkAction = null }
                }
            }) { Text("Confirm") } }, dismissButton = { TextButton({ bulkAction = null }, enabled = !busy) { Text("Cancel") } })
    }
}

private fun recordLabel(value: JsonElement): String = listOf("name", "email", "title", "modelID", "id").firstNotNullOfOrNull { value[it].text.takeIf(String::isNotBlank) } ?: "Record"

@Composable private fun AdminDetailDialog(app: AxonHubApplication, module: AdminModule, summary: JsonElement, dismiss: () -> Unit, edit: (AdminOperation, JsonElement) -> Unit, changed: () -> Unit) {
    var value by remember { mutableStateOf<JsonElement>(JsonNull) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var uncertain by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<AdminOperation?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val id = summary["id"].text
    val fence = remember(id) { app.repository.currentFence() }
    val selectedInstance by app.repository.selectedId.collectAsState()
    val selectedProject by app.repository.projectId.collectAsState()
    LaunchedEffect(selectedInstance, selectedProject) {
        if (selectedInstance != fence.instanceId || selectedProject != fence.projectId) { loaded = false; value = JsonNull; error = "The target changed. Close this editor and reopen it." }
    }
    suspend fun load() {
        busy = true; error = null
        try {
            app.repository.verify(fence)
            value = if (module.entity == "ProjectUser") summary else app.admin.detail(module.entity, id)
            app.repository.verify(fence)
            require(value !is JsonNull && value["id"].text == id) { "The record is no longer available" }
            loaded = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (t: Exception) { error = t.message; loaded = false } finally { busy = false }
    }
    LaunchedEffect(id) { load() }
    val actions = app.adminCatalog.schema.operations.filter { it.entity == module.entity && it.mutation && !it.root.startsWith("create") && !it.root.startsWith("bulk") }
    val primary = if (module.id == "apiKeys") actions.filter { it.id in setOf("updateAPIKey", "rotateAPIKey") } else actions.filter { it.id == "update${module.entity}" }
    fun action(op: AdminOperation) {
        if (op.destructive || op.variables.none { it.name in setOf("input", "status", "profile") }) confirm = op else edit(op, value)
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(recordLabel(if (loaded) value else summary)) }, text = {
        LazyColumn(Modifier.heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ scope.launch { load() } }, enabled = !busy) { Text("Reload") } } }
            if (uncertain) item { Text("The write may have completed. Close and refresh before making another change.", color = MaterialTheme.colorScheme.error) }
            if (loaded) {
                item { Text(listOf("status", "type").mapNotNull { value[it].text.takeIf(String::isNotBlank) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                if (module.id == "apiKeys") {
                    item { IosSectionTitle("API key"); KeyValueRow(enabled = !busy && !uncertain) { app.repository.verify(fence); app.admin.revealKey(id).also { app.repository.verify(fence) } } }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable key", Modifier.weight(1f))
                            Switch(value["status"].text == "enabled", { enabled ->
                                scope.launch {
                                    busy = true; error = null
                                    try {
                                        app.repository.verify(fence); uncertain = true
                                        app.admin.execute("updateAPIKeyStatus", buildJsonObject { put("id", id); put("status", if (enabled) "enabled" else "disabled") }, value, expectedFence = fence)
                                        app.repository.verify(fence); uncertain = false; load(); changed()
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (t: Exception) { error = t.message } finally { busy = false }
                                }
                            }, enabled = !busy && !uncertain && value["status"].text != "archived")
                        }
                    }
                }
                if (module.id in setOf("apiKeys", "projects")) item {
                    OutlinedButton({ edit(app.adminCatalog.operation(if (module.id == "apiKeys") "updateAPIKeyProfiles" else "updateProjectProfiles"), value) }, Modifier.fillMaxWidth(), enabled = !busy && !uncertain) { Text("Configure Profiles") }
                    if (module.id == "apiKeys") {
                        OutlinedButton({ edit(app.adminCatalog.operation("loadApiKeyProfileTemplate"), value) }, Modifier.fillMaxWidth(), enabled = !busy && !uncertain) { Text("Load policy template") }
                        TextButton({ usage = !usage }, enabled = !busy) { Text("Token and quota usage") }
                        if (usage) KeyUsage(app, id)
                    }
                }
                items(primary) { op -> OutlinedButton({ action(op) }, Modifier.fillMaxWidth(), enabled = !busy && !uncertain) { Text(managementLabel(op.id)) } }
                item {
                    TextButton({ details = !details }) { Text("Details"); Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
                    if (details) ManagementDetails(value)
                }
                item { TextButton({ advanced = !advanced }) { Text("Advanced actions"); Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) } }
                if (advanced) items(actions.filter { it !in primary }) { op -> OutlinedButton({ action(op) }, Modifier.fillMaxWidth(), enabled = !busy && !uncertain) { Text(managementLabel(op.id)) } }
            }
        }
    }, confirmButton = { TextButton(dismiss, enabled = !busy) { Text("Close") } })
    confirm?.let { op -> AlertDialog(onDismissRequest = { if (!busy) confirm = null }, title = { Text(managementLabel(op.id)) }, text = { Text("Target: ${recordLabel(value)}") },
        confirmButton = { Button(enabled = !busy && !uncertain, onClick = {
            scope.launch {
                busy = true; error = null
                try {
                    app.repository.verify(fence)
                    val vars = operationVariables(app.adminCatalog.schema, op, value, fence.projectId)
                    uncertain = true
                    app.admin.execute(op.id, vars, value, expectedFence = fence)
                    app.repository.verify(fence); uncertain = false; changed(); dismiss()
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (t: Exception) { error = t.message } finally { busy = false; confirm = null }
            }
        }, colors = if (op.destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { Text("Confirm") } },
        dismissButton = { TextButton({ confirm = null }, enabled = !busy) { Text("Cancel") } }) }
}

@Composable private fun OperationDialog(app: AxonHubApplication, operation: AdminOperation, baseline: JsonElement, project: String?, dismiss: () -> Unit, completed: () -> Unit) {
    val schema = app.adminCatalog.schema
    var effectiveBaseline by remember(operation.id, baseline) { mutableStateOf(baseline) }
    var variables by remember(operation.id, baseline) { mutableStateOf(operationVariables(schema, operation, baseline, project)) }
    var result by remember { mutableStateOf<JsonElement>(JsonNull) }
    var busy by remember { mutableStateOf(false) }
    var loadingBaseline by remember { mutableStateOf(false) }
    var baselineFailed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var uncertain by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val openedFence = remember(operation.id) { app.repository.currentFence() }
    val keyInput = operation.id in setOf("createAPIKey", "updateAPIKey")
    val profilesInput = operation.id in setOf("updateAPIKeyProfiles", "updateProjectProfiles")
    val templateInput = operation.id == "loadApiKeyProfileTemplate"
    val selectedInstance by app.repository.selectedId.collectAsState()
    val selectedProject by app.repository.projectId.collectAsState()
    LaunchedEffect(selectedInstance, selectedProject) {
        if (selectedInstance != openedFence.instanceId || selectedProject != openedFence.projectId) {
            variables = buildJsonObject {}; result = JsonNull; effectiveBaseline = JsonNull; baselineFailed = true
            error = "The target changed. Close this editor and reopen it."
        }
    }
    LaunchedEffect(operation.id) {
        val entityId = variables["id"].text.ifBlank { if (templateInput) variables["input"]["apiKeyID"].text else "" }
        if (operation.mutation && !operation.root.startsWith("create") && operation.entity.isNotBlank() && operation.entity != "ProjectUser" && entityId.isNotBlank() || operation.replacement && effectiveBaseline is JsonNull && operation.verification.isNotBlank()) {
            loadingBaseline = true
            try {
                app.repository.verify(openedFence)
                effectiveBaseline = if (entityId.isNotBlank()) app.admin.detail(operation.entity, entityId) else app.admin.read(operation.verification)
                app.repository.verify(openedFence)
                require(effectiveBaseline !is JsonNull) { "Reload the server record before editing" }
                variables = operationVariables(schema, operation, effectiveBaseline, project)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (t: Exception) { error = t.message; baselineFailed = true } finally { loadingBaseline = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = result["data"].text
        if (uri != null && data.isNotEmpty()) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "w")?.use { it.write(data.toByteArray()) } ?: throw IllegalStateException("Unable to open destination") } }
                .onFailure { error = "Could not save the backup file." }
        }
    }
    suspend fun submit() {
        if (busy || uncertain || baselineFailed || loadingBaseline) return
        busy = true; error = null
        try {
            app.repository.verify(openedFence)
            schema.validate(variables, operation.variables, operation.mutation)
            if (profilesInput) KeyEditorPolicy.validateProfiles(variables["input"] ?: JsonNull, operation.entity == "APIKey")
            if (operation.mutation) uncertain = true
            val response = if (operation.mutation) app.admin.execute(operation.id, variables, effectiveBaseline, expectedFence = openedFence) else app.admin.read(operation.id, variables)
            app.repository.verify(openedFence)
            result = response; uncertain = false; completed()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (t: Exception) { error = t.message } finally { busy = false }
    }
    fun setInput(value: JsonObject) { if (!busy && !uncertain && !baselineFailed) variables = JsonObject(variables + ("input" to value)) }
    DisposableEffect(Unit) { onDispose { result = JsonNull; variables = buildJsonObject {}; effectiveBaseline = JsonNull } }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(managementLabel(operation.id)) }, text = {
        Column(Modifier.heightIn(max = 650.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${openedFence.instanceId}${openedFence.projectId?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            if (busy || loadingBaseline) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (result !is JsonNull) {
                Text(if (operation.asyncEffect) "Server accepted the operation; asynchronous effects may still be pending" else "Saved and verified", style = MaterialTheme.typography.titleMedium)
                if (operation.id == "createAPIKey" && result["key"].text.isNotBlank()) {
                    KeyValueRow(initialValue = result["key"].text, initiallyVisible = true) { result["key"].text }
                } else if (result is JsonPrimitive) Text(result.text.ifBlank { result.toString() })
                else ManagementDetails(if (operation.root == "backup") JsonObject(result.obj.filterKeys { it != "data" }) else result)
                if (operation.root == "backup" && result["data"].text.isNotBlank()) {
                    OutlinedButton({ export.launch("axonhub-backup-${System.currentTimeMillis()}.json") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Text("Export backup JSON") }
                }
            } else if (!loadingBaseline) {
                when {
                    keyInput -> KeyInputFields(operation, effectiveBaseline, variables["input"].obj, ::setInput)
                    profilesInput -> KeyProfileFields(app, variables["input"].obj, operation.entity == "APIKey", ::setInput)
                    templateInput -> KeyTemplateFields(app, effectiveBaseline, variables["input"].obj, ::setInput)
                    else -> operation.variables.forEach { field ->
                        val boundId = effectiveBaseline["id"].text.takeIf { field.name == "id" && it.isNotBlank() }
                        if (boundId != null) { Text(managementLabel(field.name), style = MaterialTheme.typography.labelMedium); Text(boundId) }
                        else SchemaValueEditor(schema, field.type, variables[field.name] ?: field.default ?: schema.defaultValue(field.type), { next -> if (!busy && !uncertain && !baselineFailed) variables = JsonObject(variables + (field.name to next)) }, managementLabel(field.name), SensitiveFields.matches(field.name))
                    }
                }
                if (keyInput || profilesInput || templateInput) {
                    TextButton({ advanced = !advanced }) { Text("Advanced configuration"); Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
                    if (advanced) {
                        val field = operation.variables.first { it.name == "input" }
                        val inputSchema = if (keyInput && !KeyEditorPolicy.canEditScopes(if (operation.id == "createAPIKey") variables["input"]["type"].text else effectiveBaseline["type"].text)) {
                            val type = schema.base(field.type)
                            schema.copy(types = schema.types + (type to schema.types.getValue(type).copy(fields = schema.types.getValue(type).fields.filterNot { it.name.contains("scopes", true) })))
                        } else schema
                        SchemaValueEditor(inputSchema, field.type, variables["input"] ?: JsonNull, { setInput(it.obj) }, "Advanced configuration")
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (uncertain) Text("The write may have completed. Close and refresh to verify; do not resubmit.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        if (result is JsonNull) Button(enabled = !busy && !loadingBaseline && !baselineFailed && !uncertain && (!templateInput || variables["input"]["templateID"].text.isNotBlank()), onClick = { if (operation.destructive) confirm = true else scope.launch { submit() } }) { Text(if (operation.mutation) "Save" else "Read") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(if (result is JsonNull) "Cancel" else "Done") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm destructive operation") },
        confirmButton = { Button(enabled = !busy && !uncertain, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { confirm = false; scope.launch { submit() } }) { Text("Confirm") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

private fun operationVariables(schema: AdminSchema, op: AdminOperation, baseline: JsonElement, project: String?): JsonObject {
    val seed = operationSeed(schema, op.variables).toMutableMap()
    op.variables.forEach { field -> when (field.name) {
        "id" -> baseline["id"].text.takeIf(String::isNotBlank)?.let { seed["id"] = JsonPrimitive(it) }
        "status" -> baseline["status"].text.takeIf(String::isNotBlank)?.let { seed["status"] = JsonPrimitive(it) }
        "input" -> {
            val source = if (op.root in setOf("updateAPIKeyProfiles", "updateProjectProfiles")) baseline["profiles"] else baseline
            val projected = if (source !is JsonNull) schema.project(source, field.type).obj.toMutableMap() else seed["input"].obj.toMutableMap()
            val info = schema.types[schema.base(field.type)]; project?.let { id -> listOf("projectID", "projectId").firstOrNull { name -> info?.fields?.any { it.name == name } == true }?.let { projected[it] = JsonPrimitive(id) } }
            if (op.id == "createAPIKey") { projected["type"] = JsonPrimitive("user"); projected["allowedIps"] = JsonArray(emptyList()) }
            if (op.id == "updateAPIKey" && !KeyEditorPolicy.canEditScopes(baseline["type"].text)) projected.keys.filter { it.contains("scopes", true) }.toList().forEach(projected::remove)
            if (op.root == "loadApiKeyProfileTemplate" && baseline["id"].text.isNotBlank()) projected["apiKeyID"] = JsonPrimitive(baseline["id"].text)
            if (op.entity == "ProjectUser" && baseline !is JsonNull) {
                val membership = baseline["membership"]
                projected["projectId"] = JsonPrimitive(membership["projectID"].text)
                projected["userId"] = JsonPrimitive(membership["userID"].text.ifBlank { baseline["id"].text })
                if (op.root == "updateProjectUser") {
                    projected["isOwner"] = membership["isOwner"]
                    projected["scopes"] = membership["scopes"]
                }
            }
            seed["input"] = JsonObject(projected)
        }
        "profile" -> if (baseline !is JsonNull) seed["profile"] = schema.project(baseline["profile"], field.type)
    } }
    return JsonObject(seed)
}

@Composable private fun SystemOperationsScreen(app: AxonHubApplication, back: () -> Unit) {
    val groups = listOf("System Settings", "Account"); var selected by remember { mutableStateOf<AdminOperation?>(null) }; var restore by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) { WorkspaceBack(back); IosPageHeader(stringResource(R.string.ws_system)); LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("Backup and restore", style = MaterialTheme.typography.titleLarge) }; item { ElevatedCard(onClick = { restore = true }, Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("Restore from backup", style = MaterialTheme.typography.titleMedium); Text("Select and validate an AxonHub JSON backup, then upload it with the official multipart contract.", style = MaterialTheme.typography.bodySmall) } } }; groups.forEach { group -> item { Text(group, style = MaterialTheme.typography.titleLarge) }; items(app.adminCatalog.schema.operations.filter { it.group == group && !it.secretRead && it.root != "restore" }, key = { it.id }) { op -> ElevatedCard(onClick = { selected = op }, Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(op.id, style = MaterialTheme.typography.titleMedium); Text(if (op.mutation) "Change with readback verification" else "Read current server state", style = MaterialTheme.typography.bodySmall) } } } } } }
    selected?.let { OperationDialog(app, it, JsonNull, null, { selected = null }, {}) }
    if (restore) RestoreDialog(app) { restore = false }
}

@Composable private fun RestoreDialog(app: AxonHubApplication, dismiss: () -> Unit) {
    val schema = app.adminCatalog.schema
    var input by remember { mutableStateOf(schema.defaultValue("RestoreOptionsInput!").obj) }
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null; report = null
            try {
                val bytes = withContext(Dispatchers.IO) { readBounded(context.contentResolver.openInputStream(uri), AdminService.MAX_BACKUP_BYTES) }
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).obj.let { backup ->
                    require(backup["version"].text.isNotBlank() && "channels" in backup && "models" in backup) { "The selected file is not an AxonHub backup." }
                }
                file = bytes
                fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
            } catch (t: Throwable) { file = null; fileName = ""; error = t.message ?: "Unable to read the backup." } finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Restore backup") }, text = { Column(Modifier.heightIn(max = 650.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Backups may contain API keys and channel credentials. The file is kept in memory, never logged, and may overwrite live server configuration.", color = MaterialTheme.colorScheme.error)
        OutlinedButton({ picker.launch(arrayOf("application/json", "text/plain")) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text(if (fileName.isBlank()) "Choose backup JSON" else fileName) }
        Text("Restore options", style = MaterialTheme.typography.titleMedium)
        SchemaValueEditor(schema, "RestoreOptionsInput!", input, { input = it.obj }, "Restore options")
        report?.let { Text(it, color = MaterialTheme.colorScheme.primary) }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    } }, confirmButton = { Button(enabled = file != null && !busy, onClick = { confirm = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Restore") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancel") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm restore") }, text = { Text("Restore $fileName to the selected instance? Overwritten configuration cannot be restored automatically.") }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { confirm = false; scope.launch { busy = true; error = null; try { app.admin.restore(input, requireNotNull(file)); report = "The server reported success and the same instance version was read back. Review restored entities because the server has no transaction revision for an exact cross-entity readback."; app.repository.refresh() } catch (t: Throwable) { error = t.message } finally { busy = false } } }) { Text("Restore now") } }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

private fun readBounded(stream: java.io.InputStream?, maxBytes: Int): ByteArray {
    requireNotNull(stream) { "Unable to open the selected file." }
    return stream.use { input ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= maxBytes) { "The backup exceeds 50 MiB." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

@Composable private fun InvitationDialog(app: AxonHubApplication, projectId: String, dismiss: () -> Unit) {
    var roles by remember { mutableStateOf<List<JsonObject>>(emptyList()) }; var roleId by remember { mutableStateOf("") }; var expires by remember { mutableStateOf("168") }; var maxUses by remember { mutableStateOf("1") }; var invitation by remember { mutableStateOf<AdminService.Invitation?>(null) }; var reveal by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var confirm by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    LaunchedEffect(projectId) { busy = true; try { val page = app.admin.page("roles", buildJsonObject { put("first", 100); put("where", buildJsonObject { put("projectID", projectId); put("level", "project") }) }); roles = page.items; roleId = roles.firstOrNull()?.get("id")?.text.orEmpty() } catch (t: Throwable) { error = t.message } finally { busy = false } }
    val link = invitation?.let { result -> runCatching { val session = app.repository.session(); app.repository.api.endpoint(session.instance.address, "sign-up", session.instance.allowHttp).newBuilder().addQueryParameter("invite", result.token).build().toString() }.getOrDefault("") }.orEmpty()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Create project invitation") }, text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("The invitation grants a project role to anyone holding its secret link.", style = MaterialTheme.typography.bodySmall)
        if (roles.isNotEmpty()) EnumDropdown("Project role", roleId, roles.map { it["id"].text }, label = { id -> roles.firstOrNull { it["id"].text == id }?.get("name")?.text ?: id }) { roleId = it }
        OutlinedTextField(expires, { expires = it }, label = { Text("Expiry in hours (0 = never)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(maxUses, { maxUses = it }, label = { Text("Maximum uses (0 = unlimited)") }, modifier = Modifier.fillMaxWidth())
        invitation?.let { result -> Text("Invitation created and read back exactly.", color = MaterialTheme.colorScheme.primary); DetailFields(result.metadata, result.metadata.keys.filterNot { SensitiveFields.matches(it) }.sorted()); OutlinedButton({ reveal = !reveal }, Modifier.fillMaxWidth()) { Text(if (reveal) "Hide secret link" else "Reveal secret link") }; if (reveal) Text(link, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    } }, confirmButton = { Button(enabled = !busy && invitation == null && roleId.isNotBlank() && expires.toIntOrNull()?.let { it >= 0 } == true && maxUses.toIntOrNull()?.let { it >= 0 } == true, onClick = { confirm = true }) { Text("Create") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Close") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm invitation") }, text = { Text("Create an invitation for the selected project role? The generated link is a secret.") }, confirmButton = { Button(onClick = { confirm = false; scope.launch { busy = true; error = null; try { invitation = app.admin.createInvitation(roleId, expires.toInt(), maxUses.toInt()) } catch (t: Throwable) { error = t.message } finally { busy = false } } }) { Text("Create") } }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

@Composable private fun AdvancedOperationsScreen(app: AxonHubApplication, back: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AdminOperation?>(null) }
    val operations = app.adminCatalog.schema.operations.filter { !it.secretRead && it.root != "restore" }
        .filter { search.isBlank() || it.id.contains(search, true) || it.group.contains(search, true) || it.root.contains(search, true) }
        .groupBy { it.group.ifBlank { "Other" } }.toSortedMap()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        WorkspaceBack(back)
        IosPageHeader(stringResource(R.string.ws_advanced))
        Text("Native schema-driven forms for every imported safe operation; secret reads remain available only in their explicit detail screens.", style = MaterialTheme.typography.bodySmall)
        IosSearchField(search, { search = it }, stringResource(R.string.ws_search_records))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            operations.forEach { (group, rows) ->
                item { Text(group, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
                items(rows, key = { it.id }) { op -> ElevatedCard(onClick = { selected = op }, Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(op.id, style = MaterialTheme.typography.titleMedium); Text("${if (op.mutation) "Mutation" else "Query"} · ${op.root}", style = MaterialTheme.typography.bodySmall) } } }
            }
        }
    }
    selected?.let { OperationDialog(app, it, JsonNull, app.repository.projectId.value, { selected = null }, {}) }
}
