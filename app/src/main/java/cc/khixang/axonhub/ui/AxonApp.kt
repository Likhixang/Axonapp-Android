package cc.khixang.axonhub.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import cc.khixang.axonhub.*
import cc.khixang.axonhub.R
import cc.khixang.axonhub.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Destination(val titleRes: Int, val icon: ImageVector) {
    DASHBOARD(R.string.dashboard, Icons.Default.Dashboard), GATEWAY(R.string.gateway, Icons.Default.Hub),
    KEYS(R.string.keys, Icons.Default.Key), MANAGEMENT(R.string.management, Icons.Default.AdminPanelSettings),
    MODELS(R.string.models, Icons.Default.ViewInAr), PLAYGROUND(R.string.playground, Icons.Default.Chat),
    SETTINGS(R.string.settings, Icons.Default.Settings),
}

@Composable
fun AxonApp(app: AxonHubApplication) {
    val settings by app.settings.state.collectAsState()
    val baseContext = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(baseContext, configuration, settings.locale) {
        if (settings.locale.isBlank()) baseContext else baseContext.createConfigurationContext(
            Configuration(configuration).apply { setLocale(Locale.forLanguageTag(settings.locale)) },
        )
    }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
        AxonTheme(settings) {
            val view = LocalView.current
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            SideEffect {
                var context = view.context
                while (context is android.content.ContextWrapper && context !is Activity) context = context.baseContext
                (context as? Activity)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
            val instances by app.repository.instances.collectAsState()
            val selected by app.repository.selected.collectAsState()
            var editorId by rememberSaveable { mutableStateOf<String?>(null) }
            var showEditor by rememberSaveable { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                when {
                    instances.isEmpty() -> WelcomeScreen { editorId = null; showEditor = true }
                    selected != null -> MainWorkspace(app, selected!!,
                        onAdd = { editorId = null; showEditor = true },
                        onEdit = { editorId = selected?.id; showEditor = true })
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            if (showEditor) InstanceEditorDialog(app, instances.firstOrNull { it.id == editorId }, onDismiss = { showEditor = false; editorId = null })
        }
    }
}

@Composable
private fun WelcomeScreen(onConnect: () -> Unit) {
    Column(Modifier.testTag("axon_onboarding").fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.onSurface), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Hub, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.surface)
            }
            IosPageHeader(stringResource(R.string.ios_welcome_title), stringResource(R.string.ios_welcome_subtitle))
            IosCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    WelcomeFeature(Icons.Default.Dashboard, stringResource(R.string.ios_welcome_manage))
                    WelcomeFeature(Icons.Default.Chat, stringResource(R.string.ios_welcome_playground))
                    WelcomeFeature(Icons.Default.Lock, stringResource(R.string.ios_welcome_secure))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onConnect, Modifier.testTag("axon_connect_instance").fillMaxWidth()) { Text(stringResource(R.string.ios_connect)) }
            Text(stringResource(R.string.ios_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WelcomeFeature(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MainWorkspace(app: AxonHubApplication, instance: AxonInstance, onAdd: () -> Unit, onEdit: () -> Unit) {
    val admin = instance.authType == AuthType.ADMIN
    val destinations = if (admin) listOf(Destination.DASHBOARD, Destination.GATEWAY, Destination.KEYS, Destination.MANAGEMENT)
        else listOf(Destination.MODELS, Destination.PLAYGROUND, Destination.SETTINGS)
    var routeNames by rememberSaveable(instance.id, instance.authType) { mutableStateOf(listOf(destinations.first().name)) }
    val destination = Destination.valueOf(routeNames.last())
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val density = LocalDensity.current
    var dockHeight by remember { mutableStateOf(96.dp) }
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    fun navigate(next: Destination) { routeNames = routeNames + next.name }
    fun back() {
        routeNames = if (routeNames.size > 1) routeNames.dropLast(1) else listOf(destinations.first().name)
    }
    BackHandler(enabled = routeNames.size > 1 || destination != destinations.first()) { back() }
    val dockSelection = Destination.valueOf(routeNames.first())
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (routeNames.size > 1) IconButton({ back() }) { Icon(Icons.Default.ChevronLeft, stringResource(R.string.ios_back)) }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp)) {
                Text(instance.name, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (admin) R.string.ios_admin_workspace else R.string.ios_api_workspace), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (destination == Destination.SETTINGS) {
                IconButton(onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.ios_edit_instance)) }
                IconButton(onAdd) { Icon(Icons.Default.Add, stringResource(R.string.add_instance)) }
            } else IconButton({ navigate(Destination.SETTINGS) }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (wide) Column(Modifier.width(110.dp).padding(8.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                destinations.forEach { item -> DockItem(item, dockSelection == item, Modifier.fillMaxWidth()) { routeNames = listOf(item.name) } }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize().padding(bottom = if (wide || keyboardVisible) 0.dp else dockHeight)) {
                    key(instance.id, instance.authType, destination) {
                        when (destination) {
                            Destination.DASHBOARD -> DashboardScreen(app)
                            Destination.GATEWAY -> GatewayScreen(app)
                            Destination.KEYS -> ManagementScreen(app, initialModule = "apiKeys")
                            Destination.MANAGEMENT -> ManagementScreen(app)
                            Destination.MODELS -> ModelsScreen(app)
                            Destination.PLAYGROUND -> PlaygroundScreen(app)
                            Destination.SETTINGS -> SettingsScreen(app, onPlayground = { navigate(Destination.PLAYGROUND) })
                        }
                    }
                }
                if (!wide && !keyboardVisible) Row(Modifier.align(Alignment.BottomCenter)
                    .onSizeChanged { dockHeight = with(density) { it.height.toDp() } }.padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(max = 600.dp).fillMaxWidth().shadow(6.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface)
                    .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
                    .padding(6.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                    destinations.forEach { item -> DockItem(item, dockSelection == item, Modifier.weight(1f)) { routeNames = listOf(item.name) } }
                }
            }
        }
    }
}

@Composable
private fun DockItem(item: Destination, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val title = stringResource(item.titleRes)
    Column(modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(22.dp))
        .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
        .selectable(selected, source, null, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(item.icon, null, Modifier.size(22.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InstanceEditorDialog(app: AxonHubApplication, existing: AxonInstance?, onDismiss: () -> Unit) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var address by rememberSaveable(existing?.id) { mutableStateOf(existing?.address ?: "https://") }
    var allowHttp by rememberSaveable(existing?.id) { mutableStateOf(existing?.allowHttp ?: false) }
    var auth by rememberSaveable(existing?.id) { mutableStateOf(existing?.authType ?: AuthType.ADMIN) }
    var email by rememberSaveable(existing?.id) { mutableStateOf(existing?.adminEmail.orEmpty()) }
    var secret by remember(existing) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fallback = stringResource(R.string.ios_connection_error)
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.testTag("axon_instance_editor"),
        title = { Text(stringResource(if (existing == null) R.string.ios_new_connection else R.string.ios_edit_instance)) },
        text = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                IosSectionTitle(stringResource(R.string.ios_server))
                IosCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ios_instance_name)) }, singleLine = true, enabled = !busy, modifier = Modifier.testTag("instance_name").fillMaxWidth())
                        OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.ios_server_url)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !busy, modifier = Modifier.testTag("instance_address").fillMaxWidth())
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ios_allow_http), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(allowHttp, { allowHttp = it }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "HTTP" })
                        }
                        if (allowHttp) Text(stringResource(R.string.ios_http_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                IosSectionTitle(stringResource(R.string.ios_authentication))
                IosSegmentedControl(listOf(stringResource(R.string.ios_admin), stringResource(R.string.ios_api_key)), auth.ordinal, { if (!busy) { auth = AuthType.entries[it]; secret = ""; error = null } })
                IosCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (auth == AuthType.ADMIN) OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.ios_admin_email)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), enabled = !busy, modifier = Modifier.testTag("instance_email").fillMaxWidth())
                        OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(if (auth == AuthType.ADMIN) R.string.ios_password else R.string.ios_api_key)) },
                            supportingText = { if (existing != null) Text(stringResource(R.string.ios_secret_unchanged)) },
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !busy, modifier = Modifier.testTag("instance_secret").fillMaxWidth())
                    }
                }
                Text(stringResource(R.string.ios_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("instance_save"), enabled = !busy && name.isNotBlank() && address.isNotBlank() && (existing != null || secret.isNotBlank()), onClick = {
                scope.launch {
                    busy = true; error = null
                    try {
                        val value = existing?.copy(name = name, address = address, allowHttp = allowHttp, authType = auth, adminEmail = email)
                            ?: AxonInstance(name = name, address = address, allowHttp = allowHttp, authType = auth, adminEmail = email)
                        if (existing == null) app.repository.add(value, secret) else app.repository.update(value, secret)
                        secret = ""; onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = t.message ?: fallback }
                    finally { busy = false }
                }
            }) { Text(stringResource(if (existing == null) R.string.ios_connect else R.string.ios_done)) }
        },
        dismissButton = { TextButton(modifier = Modifier.testTag("axon_instance_cancel"), enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.ios_cancel)) } },
    )
}

@Composable
fun SettingsScreen(app: AxonHubApplication, onPlayground: () -> Unit) {
    val instances by app.repository.instances.collectAsState()
    val selectedId by app.repository.selectedId.collectAsState()
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var delete by remember { mutableStateOf<AxonInstance?>(null) }
    var relogin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun switchInstance(item: AxonInstance) {
        if (busy || item.id == selectedId) return
        scope.launch {
            busy = true; error = null
            try { app.repository.switch(item.id) }
            catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = t.message }
            finally { busy = false }
        }
    }
    val themeLabels = listOf(stringResource(R.string.ios_system), stringResource(R.string.ios_light), stringResource(R.string.ios_dark))
    val systemLabel = stringResource(R.string.ios_system)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { IosPageHeader(stringResource(R.string.settings), stringResource(R.string.ios_settings_subtitle)) }
        item { IosSectionTitle(stringResource(R.string.ios_instances)) }
        items(instances, key = { it.id }) { item ->
            IosCard(Modifier.fillMaxWidth().semantics { selected = item.id == selectedId }, onClick = { switchInstance(item) }) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.id == selectedId) Icon(Icons.Default.Check, stringResource(R.string.ios_selected), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    IconButton({ delete = item }, enabled = !busy) { Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.ios_remove), tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { IosSectionTitle(stringResource(R.string.ios_appearance)) }
        item { IosCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.ios_theme), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IosSegmentedControl(themeLabels, settings.theme.ordinal, { app.settings.update(settings.copy(theme = ThemeMode.entries[it])) })
                HorizontalDivider()
                EnumDropdown(stringResource(R.string.ios_language), settings.locale, listOf("", "en", "zh-CN", "zh-TW", "ja", "ko"), label = {
                    when (it) { "" -> systemLabel; "en" -> "English"; "zh-CN" -> "简体中文"; "zh-TW" -> "繁體中文"; "ja" -> "日本語"; "ko" -> "한국어"; else -> it }
                }) { app.settings.update(settings.copy(locale = it)) }
                HorizontalDivider()
                Text(stringResource(R.string.ios_accent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(0xff4f46e5, 0xff2563eb, 0xff0f766e, 0xffbe123c, 0xff7c3aed).forEachIndexed { index, color ->
                        val selected = settings.accent == color
                        val source = remember { MutableInteractionSource() }
                        val title = stringResource(R.string.ios_accent_option, index + 1)
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(color.toInt()))
                            .selectable(selected, source, null, role = Role.RadioButton, onClick = { app.settings.update(settings.copy(accent = color)) })) {
                            if (selected) Icon(Icons.Default.Check, title, Modifier.align(Alignment.Center), tint = Color.White)
                            else Box(Modifier.fillMaxSize().semantics { contentDescription = title })
                        }
                    }
                }
            }
        } }
        item { IosSectionTitle(stringResource(R.string.ios_account_tools)) }
        item { IosCard(Modifier.fillMaxWidth()) {
            SettingsAction(Icons.Default.Login, stringResource(R.string.ios_sign_in_again)) { relogin = true }
            HorizontalDivider(Modifier.padding(start = 54.dp))
            SettingsAction(Icons.Default.Chat, stringResource(R.string.ios_open_playground), onPlayground)
        } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { Text(stringResource(R.string.ios_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    delete?.let { target ->
        AlertDialog(onDismissRequest = { if (!busy) delete = null }, title = { Text(stringResource(R.string.ios_remove_title, target.name)) },
            text = { Text(stringResource(R.string.ios_remove_warning)) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try { app.repository.delete(target.id); delete = null }
                    catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = t.message }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.ios_remove), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { delete = null }) { Text(stringResource(R.string.ios_cancel)) } },
        )
    }
    if (relogin) SecretDialog(stringResource(R.string.ios_sign_in_again), onDismiss = { relogin = false }) { secret -> app.repository.relogin(secret); relogin = false }
}

@Composable
private fun SettingsAction(icon: ImageVector, title: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp)); Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SecretDialog(title: String, onDismiss: () -> Unit, action: suspend (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value, { value = it }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.ios_password_or_key)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.ios_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !busy && value.isNotBlank(), onClick = {
            scope.launch {
                busy = true; error = null
                try { action(value); value = "" }
                catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = t.message }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.ios_done)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.ios_cancel)) } },
    )
}

@Composable
fun <T> EnumDropdown(title: String, selected: T, values: List<T>, label: (T) -> String = { it.toString() }, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) {
                Text(label(selected), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Default.UnfoldMore, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded, { expanded = false }) {
                values.forEach { value -> DropdownMenuItem(text = { Text(label(value)) }, onClick = { onSelect(value); expanded = false },
                    trailingIcon = { if (selected == value) Icon(Icons.Default.Check, stringResource(R.string.ios_selected), tint = MaterialTheme.colorScheme.primary) }) }
            }
        }
    }
}
