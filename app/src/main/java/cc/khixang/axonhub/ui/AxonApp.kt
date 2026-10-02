package cc.khixang.axonhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.*
import cc.khixang.axonhub.R
import cc.khixang.axonhub.core.*
import kotlinx.coroutines.launch

private enum class Destination(val titleRes: Int, val icon: ImageVector) {
    DASHBOARD(R.string.dashboard, Icons.Default.Dashboard), GATEWAY(R.string.gateway, Icons.Default.Hub), KEYS(R.string.keys, Icons.Default.Key),
    MANAGEMENT(R.string.management, Icons.Default.AdminPanelSettings), MODELS(R.string.models, Icons.Default.ViewInAr),
    PLAYGROUND(R.string.playground, Icons.Default.Chat), SETTINGS(R.string.settings, Icons.Default.Settings),
}

@Composable fun AxonApp(app: AxonHubApplication) {
    val settings by app.settings.state.collectAsState()
    AxonTheme(settings) {
        val instances by app.repository.instances.collectAsState()
        val selected by app.repository.selected.collectAsState()
        var editor by remember { mutableStateOf<AxonInstance?>(null) }
        var showEditor by remember { mutableStateOf(false) }
        if (instances.isEmpty() || showEditor) {
            InstanceEditorDialog(app, editor, mandatory = instances.isEmpty(), onDismiss = { if (instances.isNotEmpty()) showEditor = false; editor = null })
        }
        if (selected != null) MainWorkspace(app, selected!!, onAdd = { editor = null; showEditor = true }, onEdit = { editor = selected; showEditor = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MainWorkspace(app: AxonHubApplication, instance: AxonInstance, onAdd: () -> Unit, onEdit: () -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val destinations = if (instance.authType == AuthType.ADMIN) listOf(Destination.DASHBOARD, Destination.GATEWAY, Destination.KEYS, Destination.MANAGEMENT, Destination.SETTINGS)
        else listOf(Destination.MODELS, Destination.PLAYGROUND, Destination.SETTINGS)
    var destination by remember(instance.id) { mutableStateOf(destinations.first()) }
    if (destination !in destinations) destination = destinations.first()
    Scaffold(
        topBar = { TopAppBar(title = { Text(instance.name) }, actions = { IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit instance") }; IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add instance") } }) },
        bottomBar = { if (!wide) NavigationBar { destinations.forEach { item -> val title = stringResource(item.titleRes); NavigationBarItem(selected = destination == item, onClick = { destination = item }, icon = { Icon(item.icon, title) }, label = { Text(title, maxLines = 1) }) } } },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (wide) NavigationRail { Spacer(Modifier.height(8.dp)); destinations.forEach { item -> val title = stringResource(item.titleRes); NavigationRailItem(selected = destination == item, onClick = { destination = item }, icon = { Icon(item.icon, title) }, label = { Text(title) }) } }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                key(instance) {
                    when (destination) {
                        Destination.DASHBOARD -> DashboardScreen(app)
                        Destination.GATEWAY -> GatewayScreen(app)
                        Destination.KEYS -> ManagementScreen(app, initialModule = "apiKeys")
                        Destination.MANAGEMENT -> ManagementScreen(app)
                        Destination.MODELS -> ModelsScreen(app)
                        Destination.PLAYGROUND -> PlaygroundScreen(app)
                        Destination.SETTINGS -> SettingsScreen(app, onPlayground = { destination = Destination.PLAYGROUND })
                    }
                }
            }
        }
    }
}

@Composable private fun InstanceEditorDialog(app: AxonHubApplication, existing: AxonInstance?, mandatory: Boolean, onDismiss: () -> Unit) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var address by remember(existing) { mutableStateOf(existing?.address ?: "https://") }
    var allowHttp by remember(existing) { mutableStateOf(existing?.allowHttp ?: false) }
    var auth by remember(existing) { mutableStateOf(existing?.authType ?: AuthType.ADMIN) }
    var email by remember(existing) { mutableStateOf(existing?.adminEmail.orEmpty()) }
    var secret by remember(existing) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!mandatory && !busy) onDismiss() }, title = { Text(if (existing == null) "Connect to AxonHub" else "Edit instance") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(allowHttp, { allowHttp = it }); Spacer(Modifier.width(8.dp)); Text("Allow HTTP for this instance") }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthType.entries.forEachIndexed { i, value -> SegmentedButton(auth == value, { auth = value }, SegmentedButtonDefaults.itemShape(i, AuthType.entries.size)) { Text(if (value == AuthType.ADMIN) "Admin" else "API Key") } }
            }
            if (auth == AuthType.ADMIN) OutlinedTextField(email, { email = it }, label = { Text("Admin email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text(if (auth == AuthType.ADMIN) "Password${if (existing != null) " (leave blank if unchanged)" else ""}" else "API Key${if (existing != null) " (leave blank if unchanged)" else ""}") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Passwords are never stored. The resulting JWT or API key is encrypted with Android Keystore.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { Button(enabled = !busy, onClick = { scope.launch { busy = true; error = null; try {
            val value = existing?.copy(name = name, address = address, allowHttp = allowHttp, authType = auth, adminEmail = email)
                ?: AxonInstance(name = name, address = address, allowHttp = allowHttp, authType = auth, adminEmail = email)
            if (existing == null) app.repository.add(value, secret) else app.repository.update(value, secret)
            onDismiss()
        } catch (t: Throwable) { error = t.message ?: "Unable to connect." } finally { busy = false } } }) { Text(if (existing == null) "Connect" else "Save") } },
        dismissButton = { if (!mandatory) TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable fun SettingsScreen(app: AxonHubApplication, onPlayground: () -> Unit) {
    val instances by app.repository.instances.collectAsState(); val selectedId by app.repository.selectedId.collectAsState(); val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope(); var delete by remember { mutableStateOf<AxonInstance?>(null) }; var relogin by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Instances", style = MaterialTheme.typography.headlineSmall) }
        items(instances.size) { index -> val item = instances[index]; ElevatedCard(onClick = { scope.launch { app.repository.switch(item.id) } }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(item.id == selectedId, { scope.launch { app.repository.switch(item.id) } }); Column(Modifier.weight(1f)) { Text(item.name); Text(item.address, style = MaterialTheme.typography.bodySmall) }; IconButton({ delete = item }) { Icon(Icons.Default.Delete, "Remove") } } } }
        item { HorizontalDivider(); Text("Appearance", style = MaterialTheme.typography.titleLarge) }
        item { EnumDropdown("Theme", settings.theme, ThemeMode.entries) { app.settings.update(settings.copy(theme = it)) } }
        item { EnumDropdown("Language", settings.locale, listOf("", "en", "zh-CN", "zh-TW", "ja", "ko"), label = { if (it.isBlank()) "System" else it }) { app.settings.update(settings.copy(locale = it)) } }
        item { Text("Accent"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0xff4f46e5, 0xff2563eb, 0xff0f766e, 0xffbe123c, 0xff7c3aed).forEach { color -> FilledIconButton(onClick = { app.settings.update(settings.copy(accent = color)) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = androidx.compose.ui.graphics.Color(color.toInt()))) { if (settings.accent == color) Icon(Icons.Default.Check, "Selected") } } } }
        item { Button(onClick = { relogin = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Login, null); Spacer(Modifier.width(8.dp)); Text("Sign in again") } }
        item { OutlinedButton(onClick = onPlayground, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Chat, null); Spacer(Modifier.width(8.dp)); Text("Open Playground") } }
    }
    delete?.let { target -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Remove ${target.name}?") }, text = { Text("Local metadata, encrypted credentials, cached state, and this instance's transcript will no longer be accessible.") }, confirmButton = { Button(onClick = { scope.launch { app.repository.delete(target.id); delete = null } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Remove") } }, dismissButton = { TextButton({ delete = null }) { Text("Cancel") } }) }
    if (relogin) SecretDialog("Sign in again", onDismiss = { relogin = false }) { secret -> app.repository.relogin(secret); relogin = false }
}

@Composable private fun SecretDialog(title: String, onDismiss: () -> Unit, action: suspend (String) -> Unit) {
    var value by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = { Column { OutlinedTextField(value, { value = it }, visualTransformation = PasswordVisualTransformation(), label = { Text("Password or API key") }); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(enabled = !busy, onClick = { scope.launch { busy = true; try { action(value) } catch (t: Throwable) { error = t.message } finally { busy = false } } }) { Text("Continue") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun <T> EnumDropdown(title: String, selected: T, values: List<T>, label: (T) -> String = { it.toString() }, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(open, { open = it }) { OutlinedTextField(label(selected), {}, readOnly = true, label = { Text(title) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()); ExposedDropdownMenu(open, { open = false }) { values.forEach { DropdownMenuItem({ Text(label(it)) }, { onSelect(it); open = false }) } } }
}
