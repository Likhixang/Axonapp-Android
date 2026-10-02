package cc.khixang.axonhub.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
    when {
        analytics -> AnalyticsScreen(app) { analytics = false }
        observability -> ObservabilityScreen(app) { observability = false }
        system -> SystemOperationsScreen(app) { system = false }
        advanced -> AdvancedOperationsScreen(app) { advanced = false }
        module != null -> AdminModuleScreen(app, module!!) { module = null }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Management", style = MaterialTheme.typography.headlineMedium) }
            item { Text("Workspace", style = MaterialTheme.typography.titleLarge) }; items(modules.take(3)) { item -> MenuCard(item.title, Icons.Default.Folder) { module = item } }
            item { Text("Audit and history", style = MaterialTheme.typography.titleLarge) }; item { MenuCard("Requests, traces, threads, and usage", Icons.Default.QueryStats) { observability = true } }; item { MenuCard("Analytics, dimensions, and performance", Icons.Default.Analytics) { analytics = true } }
            item { Text("Access and security", style = MaterialTheme.typography.titleLarge) }; items(modules.drop(3).take(6)) { item -> MenuCard(item.title, Icons.Default.Security) { module = item } }
            item { Text("System and operations", style = MaterialTheme.typography.titleLarge) }; items(modules.drop(9)) { item -> MenuCard(item.title, Icons.Default.Storage) { module = item } }; item { MenuCard("System settings, cache, backup, catalog, and account", Icons.Default.Settings) { system = true } }; item { MenuCard("All schema operations", Icons.Default.DataObject) { advanced = true } }
        }
    }
}

@Composable private fun MenuCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) { ElevatedCard(onClick = action, Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp)) { Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(title, style = MaterialTheme.typography.titleMedium) } } }

@Composable private fun AdminModuleScreen(app: AxonHubApplication, module: AdminModule, back: () -> Unit) {
    var rows by remember(module.id) { mutableStateOf<List<JsonObject>>(emptyList()) }; var cursor by remember(module.id) { mutableStateOf<String?>(null) }; var total by remember { mutableStateOf<Int?>(null) }; var search by remember { mutableStateOf("") }; var project by remember { mutableStateOf<String?>(null) }; var projects by remember { mutableStateOf<List<JsonObject>>(emptyList()) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var selected by remember { mutableStateOf<JsonElement?>(null) }; var operation by remember { mutableStateOf<Pair<AdminOperation, JsonElement>?>(null) }; var invitation by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    suspend fun load(append: Boolean = false) {
        if (module.project && project.isNullOrBlank()) return
        if (!append) { rows = emptyList(); cursor = null; total = null; selected = null }
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
                val page = app.admin.page(module.list, vars); rows = if (append) rows + page.items else page.items; cursor = page.endCursor; total = page.total
            }
        } catch (t: Throwable) { error = t.message } finally { busy = false }
    }
    LaunchedEffect(module.id) {
        if (module.project) runCatching { app.admin.read("myProjects") }.getOrNull()?.arr?.let { projects = it.map(JsonElement::obj); project = projects.firstOrNull()?.get("id")?.text; app.repository.selectProject(project) }
        load()
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row { IconButton(back) { Icon(Icons.Default.ArrowBack, "Back") }; Text(module.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); if (module.id == "projectUsers") IconButton(enabled = !project.isNullOrBlank(), onClick = { invitation = true }) { Icon(Icons.Default.Link, "Create invitation") }; module.create?.let { create -> IconButton({ operation = app.adminCatalog.operation(create) to JsonNull }) { Icon(Icons.Default.Add, "Create") } }; IconButton({ scope.launch { load() } }) { Icon(Icons.Default.Refresh, "Refresh") } }
        if (module.project) EnumDropdown("Project", project.orEmpty(), listOf("") + projects.map { it["id"].text }, label = { id -> projects.firstOrNull { it["id"].text == id }?.get("name")?.text ?: "Select project" }) { project = it.takeIf(String::isNotBlank); scope.launch { load() } }
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, trailingIcon = { IconButton({ scope.launch { load() } }) { Icon(Icons.Default.Search, "Search") } }, modifier = Modifier.fillMaxWidth())
        total?.let { Text("${rows.size} of $it", style = MaterialTheme.typography.bodySmall) }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!busy && rows.isEmpty()) Text(if (module.project && project == null) "Select a project" else "No records", Modifier.padding(24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(rows, key = { it["id"].text.ifBlank { it.toString() } }) { row -> ElevatedCard(onClick = { selected = row }, Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(recordLabel(row), style = MaterialTheme.typography.titleMedium); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row["status"].text.takeIf(String::isNotBlank)?.let { AssistChip({}, { Text(it) }) }; row["type"].text.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }; row["description"].text.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 2) } } } }; if (cursor != null) item { OutlinedButton({ scope.launch { load(true) } }, Modifier.fillMaxWidth()) { Text("Load next page") } } }
    }
    selected?.let { summary -> AdminDetailDialog(app, module, summary, { selected = null }, { op, baseline -> operation = op to baseline; selected = null }, { scope.launch { load() } }) }
    operation?.let { (op, baseline) -> OperationDialog(app, op, baseline, project, { operation = null }, { scope.launch { load() } }) }
    if (invitation && project != null) InvitationDialog(app, project!!, { invitation = false })
}

private fun recordLabel(value: JsonElement): String = listOf("name", "email", "title", "modelID", "id").firstNotNullOfOrNull { value[it].text.takeIf(String::isNotBlank) } ?: "Record"

@Composable private fun AdminDetailDialog(app: AxonHubApplication, module: AdminModule, summary: JsonElement, dismiss: () -> Unit, edit: (AdminOperation, JsonElement) -> Unit, changed: () -> Unit) {
    var value by remember { mutableStateOf(summary) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var confirm by remember { mutableStateOf<AdminOperation?>(null) }; var secret by remember { mutableStateOf("") }; val scope = rememberCoroutineScope(); val id = summary["id"].text
    LaunchedEffect(id) { if (module.entity != "ProjectUser") { busy = true; try { value = app.admin.detail(module.entity, id) } catch (t: Throwable) { error = t.message } finally { busy = false } } }
    val actions = app.adminCatalog.schema.operations.filter { it.entity == module.entity && it.mutation && !it.root.startsWith("create") && !it.root.startsWith("bulk") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(recordLabel(value)) }, text = { LazyColumn(Modifier.heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }; error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }; item { DetailFields(value, value.obj.keys.filterNot { SensitiveFields.matches(it) }.sorted()) }; if (module.id == "apiKeys") item { if (secret.isBlank()) OutlinedButton({ scope.launch { busy = true; try { secret = app.admin.read("revealAPIKey", buildJsonObject { put("id", id) })["key"].text } catch (t: Throwable) { error = t.message } finally { busy = false } } }) { Text("Reveal API key") } else Column { Text(secret, style = MaterialTheme.typography.bodySmall); TextButton({ secret = "" }) { Text("Hide") } } }; item { Text("Actions", style = MaterialTheme.typography.titleMedium) }; items(actions.distinctBy { it.id }) { op -> OutlinedButton({ if (op.destructive || op.variables.none { it.name == "input" || it.name == "status" || it.name == "profile" }) confirm = op else edit(op, value) }, Modifier.fillMaxWidth(), colors = if (op.destructive) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonColors()) { Text(op.id) } } } }, confirmButton = { TextButton(dismiss) { Text("Close") } })
    confirm?.let { op -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Confirm ${op.id}") }, text = { Text("Target: ${recordLabel(value)}") }, confirmButton = { Button(onClick = { scope.launch { busy = true; try { val vars = operationVariables(app.adminCatalog.schema, op, value, null); app.admin.execute(op.id, vars, value); changed(); dismiss() } catch (t: Throwable) { error = t.message } finally { busy = false; confirm = null } } }, colors = if (op.destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { Text("Confirm") } }, dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }) }
}

@Composable private fun OperationDialog(app: AxonHubApplication, operation: AdminOperation, baseline: JsonElement, project: String?, dismiss: () -> Unit, completed: () -> Unit) {
    val schema = app.adminCatalog.schema; var effectiveBaseline by remember(operation.id, baseline) { mutableStateOf(baseline) }; var variables by remember(operation.id, baseline) { mutableStateOf(operationVariables(schema, operation, baseline, project)) }; var result by remember { mutableStateOf<JsonElement>(JsonNull) }; var busy by remember { mutableStateOf(false) }; var loadingBaseline by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var confirm by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope(); val context = LocalContext.current; val openedFence = remember(operation.id) { app.repository.currentFence() }
    LaunchedEffect(operation.id) {
        if (operation.replacement && effectiveBaseline is JsonNull && operation.verification.isNotBlank()) {
            loadingBaseline = true
            try { app.repository.verify(openedFence); effectiveBaseline = app.admin.read(operation.verification); variables = operationVariables(schema, operation, effectiveBaseline, project) }
            catch (t: Throwable) { error = t.message }
            finally { loadingBaseline = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = result["data"].text
        if (uri != null && data.isNotEmpty()) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "w")?.use { it.write(data.toByteArray()) } ?: throw IllegalStateException("Unable to open destination") } }
                .onFailure { error = "Could not save the backup file." }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(operation.id) }, text = { Column(Modifier.heightIn(max = 650.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Bound target: ${openedFence.instanceId}${openedFence.projectId?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall); operation.variables.forEach { field -> val boundId = effectiveBaseline["id"].text.takeIf { field.name == "id" && it.isNotBlank() }; if (boundId != null) { Text(field.name, style = MaterialTheme.typography.labelMedium); Text(boundId) } else SchemaValueEditor(schema, field.type, variables[field.name] ?: field.default ?: schema.defaultValue(field.type), { next -> variables = JsonObject(variables.toMutableMap().also { it[field.name] = next }) }, field.name, SensitiveFields.matches(field.name)) }; if (result !is JsonNull) { Text(if (operation.asyncEffect) "Server accepted the operation; asynchronous effects may still be pending" else "Verified server response", style = MaterialTheme.typography.titleMedium); if (result is JsonPrimitive) Text(result.text.ifBlank { result.toString() }) else DetailFields(result, result.obj.keys.filterNot { SensitiveFields.matches(it) || operation.root == "backup" && it == "data" }.sorted()); if (operation.root == "backup" && result["data"].text.isNotBlank()) { Text("Backup content can include credentials and is intentionally not displayed.", style = MaterialTheme.typography.bodySmall); OutlinedButton({ export.launch("axonhub-backup-${System.currentTimeMillis()}.json") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Export backup JSON") } } }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy || loadingBaseline) LinearProgressIndicator(Modifier.fillMaxWidth()) } }, confirmButton = { Button(enabled = !busy && !loadingBaseline, onClick = { if (operation.destructive) confirm = true else scope.launch { try { app.repository.verify(openedFence); runOperation(app, operation, variables, effectiveBaseline, { busy = it }, { result = it; completed() }, { error = it }) } catch (t: Throwable) { error = t.message } } }) { Text(if (operation.mutation) "Save" else "Run") } }, dismissButton = { TextButton(dismiss) { Text("Close") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm destructive operation") }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { confirm = false; scope.launch { try { app.repository.verify(openedFence); runOperation(app, operation, variables, effectiveBaseline, { busy = it }, { result = it; completed() }, { error = it }) } catch (t: Throwable) { error = t.message } } }) { Text("Execute") } }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

private suspend fun runOperation(app: AxonHubApplication, op: AdminOperation, vars: JsonObject, baseline: JsonElement, busy: (Boolean) -> Unit, success: (JsonElement) -> Unit, error: (String?) -> Unit) { busy(true); error(null); try { app.adminCatalog.schema.validate(vars, op.variables, op.mutation); success(if (op.mutation) app.admin.execute(op.id, vars, baseline) else app.admin.read(op.id, vars)) } catch (t: Throwable) { error(t.message) } finally { busy(false) } }

private fun operationVariables(schema: AdminSchema, op: AdminOperation, baseline: JsonElement, project: String?): JsonObject {
    val seed = operationSeed(schema, op.variables).toMutableMap()
    op.variables.forEach { field -> when (field.name) {
        "id" -> baseline["id"].text.takeIf(String::isNotBlank)?.let { seed["id"] = JsonPrimitive(it) }
        "status" -> baseline["status"].text.takeIf(String::isNotBlank)?.let { seed["status"] = JsonPrimitive(it) }
        "input" -> {
            val source = if (op.root in setOf("updateAPIKeyProfiles", "updateProjectProfiles")) baseline["profiles"] else baseline
            val projected = if (source !is JsonNull) schema.project(source, field.type).obj.toMutableMap() else seed["input"].obj.toMutableMap()
            val info = schema.types[schema.base(field.type)]; project?.let { id -> listOf("projectID", "projectId").firstOrNull { name -> info?.fields?.any { it.name == name } == true }?.let { projected[it] = JsonPrimitive(id) } }
            if (op.id == "createAPIKey") projected["type"] = JsonPrimitive("user")
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
    Column(Modifier.fillMaxSize().padding(16.dp)) { Row { IconButton(back) { Icon(Icons.Default.ArrowBack, "Back") }; Text("System and account", style = MaterialTheme.typography.headlineSmall) }; LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("Backup and restore", style = MaterialTheme.typography.titleLarge) }; item { ElevatedCard(onClick = { restore = true }, Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("Restore from backup", style = MaterialTheme.typography.titleMedium); Text("Select and validate an AxonHub JSON backup, then upload it with the official multipart contract.", style = MaterialTheme.typography.bodySmall) } } }; groups.forEach { group -> item { Text(group, style = MaterialTheme.typography.titleLarge) }; items(app.adminCatalog.schema.operations.filter { it.group == group && !it.secretRead && it.root != "restore" }, key = { it.id }) { op -> ElevatedCard(onClick = { selected = op }, Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(op.id, style = MaterialTheme.typography.titleMedium); Text(if (op.mutation) "Change with readback verification" else "Read current server state", style = MaterialTheme.typography.bodySmall) } } } } } }
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { IconButton(back) { Icon(Icons.Default.ArrowBack, "Back") }; Text("All schema operations", style = MaterialTheme.typography.headlineSmall) }
        Text("Native schema-driven forms for every imported safe operation; secret reads remain available only in their explicit detail screens.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(search, { search = it }, label = { Text("Search operations") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            operations.forEach { (group, rows) ->
                item { Text(group, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
                items(rows, key = { it.id }) { op -> ElevatedCard(onClick = { selected = op }, Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(op.id, style = MaterialTheme.typography.titleMedium); Text("${if (op.mutation) "Mutation" else "Query"} · ${op.root}", style = MaterialTheme.typography.bodySmall) } } }
            }
        }
    }
    selected?.let { OperationDialog(app, it, JsonNull, app.repository.projectId.value, { selected = null }, {}) }
}
