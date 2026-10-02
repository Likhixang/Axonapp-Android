package cc.khixang.axonhub.ui

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.core.AuthType
import cc.khixang.axonhub.playground.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.UUID

@Composable fun PlaygroundScreen(app: AxonHubApplication) {
    val instance by app.repository.selected.collectAsState(); var catalog by remember { mutableStateOf(PlaygroundCatalog()) }; var messages by remember(instance?.id) { mutableStateOf<List<ChatMessage>>(emptyList()) }; var protocol by remember { mutableStateOf(PlaygroundProtocol.OPENAI_CHAT) }; var model by remember { mutableStateOf("") }; var project by remember { mutableStateOf("") }; var channel by remember { mutableStateOf("") }; var input by remember { mutableStateOf("") }; var images by remember { mutableStateOf<List<ChatPart>>(emptyList()) }; var system by remember { mutableStateOf("You are a helpful assistant.") }; var temperature by remember { mutableStateOf(0.6f) }; var maxTokens by remember { mutableStateOf("4096") }; var busy by remember { mutableStateOf(false) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }; var job by remember { mutableStateOf<Job?>(null) }; val scope = rememberCoroutineScope(); val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> scope.launch { try { require(images.size + uris.size <= 8) { "Attach at most 8 images." }; val added = withContext(Dispatchers.IO) { uris.map { uri -> val mime = context.contentResolver.getType(uri).orEmpty(); require(mime.startsWith("image/")) { "Only image files are supported." }; val bytes = readImageBounded(context.contentResolver.openInputStream(uri)); require(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) { "The selected image could not be decoded." }; val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "image"; val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP); ChatPart("file", name = name, data = buildJsonObject { put("filename", name); put("mediaType", mime); put("base64", base64); put("url", "data:$mime;base64,$base64") }) } }; images = images + added; error = null } catch (t: Throwable) { error = t.message } } }
    suspend fun reload() { loading = true; try { catalog = app.playground.catalog(); val saved = app.playground.loadTranscript(); if (saved != null) { messages = saved.messages; model = saved.model; protocol = saved.protocol }; if (model.isBlank()) model = catalog.models.firstOrNull()?.id ?: catalog.channels.firstOrNull()?.models?.firstOrNull()?.id.orEmpty(); project = catalog.projects.firstOrNull()?.id.orEmpty() } catch (t: Throwable) { error = t.message } finally { loading = false } }
    LaunchedEffect(instance?.id) { reload() }
    fun stop() { job?.cancel(); job = null; busy = false; messages = messages.mapIndexed { index, message -> if (index == messages.lastIndex && message.role == "assistant") message.copy(incomplete = true) else message } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row { Text("Playground", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f)); IconButton({ if (!busy) { messages = emptyList(); images = emptyList(); app.playground.clearTranscript() } }) { Icon(Icons.Default.Delete, "Clear") } }
        if (instance?.authType == AuthType.ADMIN) {
            if (catalog.projects.isNotEmpty()) EnumDropdown("Project", project, catalog.projects.map { it.id }, label = { id -> catalog.projects.firstOrNull { it.id == id }?.name ?: id }) { next -> stop(); project = next; app.repository.selectProject(next); channel = ""; messages = emptyList(); scope.launch { reload() } }
            EnumDropdown("Route", channel, listOf("") + catalog.channels.map { it.id }, label = { id -> if (id.isBlank()) "Gateway models" else catalog.channels.firstOrNull { it.id == id }?.name ?: id }) { next -> stop(); channel = next; messages = emptyList(); model = if (next.isBlank()) catalog.models.firstOrNull()?.id.orEmpty() else catalog.channels.firstOrNull { it.id == next }?.models?.firstOrNull()?.id.orEmpty() }
        } else EnumDropdown("Protocol", protocol, listOf(PlaygroundProtocol.OPENAI_CHAT, PlaygroundProtocol.OPENAI_RESPONSES, PlaygroundProtocol.ANTHROPIC, PlaygroundProtocol.GEMINI), { it.name.replace('_', ' ') }) { protocol = it }
        val available = if (channel.isBlank()) catalog.models else catalog.channels.firstOrNull { it.id == channel }?.models.orEmpty()
        if (available.isNotEmpty()) EnumDropdown("Model", model, available.map { it.id }, label = { id -> available.firstOrNull { it.id == id }?.name ?: id }) { model = it }
        else OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) { if (messages.isEmpty()) item { Text("Start a real streaming request. No sample dashboard or chat data is shown.", Modifier.padding(20.dp)) }; items(messages, key = { it.id }) { message -> ChatBubble(message) } }
        var advanced by remember { mutableStateOf(false) }; TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide parameters" else "Parameters") }
        if (advanced) { OutlinedTextField(system, { system = it }, label = { Text("System message") }, minLines = 2, modifier = Modifier.fillMaxWidth()); Text("Temperature %.1f".format(temperature)); Slider(temperature, { temperature = it }, valueRange = 0f..2f, steps = 19); OutlinedTextField(maxTokens, { maxTokens = it }, label = { Text("Output tokens") }, modifier = Modifier.fillMaxWidth()) }
        if (images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { images.forEach { image -> InputChip(selected = true, onClick = { images = images - image }, label = { Text(image.name, maxLines = 1) }, trailingIcon = { Icon(Icons.Default.Delete, "Remove image") }) } }
        OutlinedButton(enabled = !busy && images.size < 8, onClick = { imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text("Attach images (${images.size}/8)") }
        OutlinedTextField(input, { input = it }, label = { Text("Message") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(), trailingIcon = { if (busy) IconButton({ stop() }) { Icon(Icons.Default.Stop, "Stop") } else IconButton({
            if ((input.isBlank() && images.isEmpty()) || model.isBlank()) return@IconButton
            val parts = buildList { if (input.isNotBlank()) add(ChatPart("text", input.trim())); addAll(images) }; val user = ChatMessage(UUID.randomUUID().toString(), "user", parts); val conversation = messages + user; messages = conversation + ChatMessage(UUID.randomUUID().toString(), "assistant", emptyList()); input = ""; images = emptyList(); busy = true; error = null
            val settings = PlaygroundSettings(model, protocol, system, temperature.toDouble(), maxTokens.toIntOrNull() ?: 4096)
            job = scope.launch { try { app.playground.stream(settings, conversation, project, channel) { update -> withContext(Dispatchers.Main.immediate) { messages = conversation + update.message } } } catch (t: Throwable) { if (t !is kotlinx.coroutines.CancellationException) { error = t.message ?: "Stream interrupted"; messages = messages.mapIndexed { i, m -> if (i == messages.lastIndex && m.role == "assistant") m.copy(incomplete = true) else m } } } finally { busy = false; job = null } }
        }) { Icon(Icons.Default.Send, "Send") } })
    }
}

@Composable private fun ChatBubble(message: ChatMessage) {
    val container = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(message.role.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelLarge); message.parts.forEach { part -> when (part.type) { "reasoning" -> { Text("Reasoning", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary); Text(part.text, style = MaterialTheme.typography.bodySmall) }; "tool", "tool-result" -> { Text("Tool ${part.name}", style = MaterialTheme.typography.labelMedium); Text(part.text, style = MaterialTheme.typography.bodySmall) }; "file" -> Row { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text(part.name.ifBlank { "Image" }) }; else -> Text(part.text.ifBlank { part.data.toString() }) } }; if (message.incomplete) Text("Incomplete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) } }
}

private fun readImageBounded(stream: java.io.InputStream?): ByteArray {
    requireNotNull(stream) { "Unable to open the selected image." }
    return stream.use { input -> val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 10 * 1024 * 1024) { "Each image must be 10 MiB or smaller." }; output.write(buffer, 0, count) }; require(output.size() > 0) { "The selected image is empty." }; output.toByteArray() }
}
