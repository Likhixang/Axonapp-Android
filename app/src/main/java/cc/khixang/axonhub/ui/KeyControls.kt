package cc.khixang.axonhub.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.util.UUID

/** Sensitive clipboard, with a guarded local expiry; never persist a key in saved UI state. */
private fun copyKey(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val ticket = "axon-key-${UUID.randomUUID()}"
    val clip = ClipData.newPlainText(ticket, text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    manager.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        if (manager.primaryClipDescription?.label?.toString() == ticket) {
            if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
            else manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }, 60_000)
}

@Composable fun KeyEditorField(title: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    var visible by remember { mutableStateOf(true) }
    var copied by remember(value) { mutableStateOf(false) }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(value, onChange, Modifier.weight(1f), enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), maxLines = 6)
            IconButton({ visible = !visible }, enabled = enabled) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "Hide" else "Show") }
            IconButton({ copyKey(context, value); copied = true }, enabled = enabled && value.isNotEmpty()) { Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, if (copied) "Copied" else "Copy") }
        }
    }
}

/** One-tap reveal/copy. Copy while hidden does not reveal or retain the fetched value. */
@Composable fun KeyValueRow(initialValue: String = "", initiallyVisible: Boolean = false, enabled: Boolean = true, read: suspend () -> String) {
    var secret by remember { mutableStateOf(if (initiallyVisible) initialValue else "") }
    var visible by remember { mutableStateOf(initiallyVisible) }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun obtain(copy: Boolean) {
        if (busy || !enabled) return
        scope.launch {
            busy = true; error = null
            try {
                val key = secret.ifEmpty { initialValue.ifEmpty { read() } }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                require(key.isNotBlank()) { "Unable to read this API key" }
                if (copy) { copyKey(context, key); copied = true }
                else { secret = key; visible = true }
            } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { error = t.message } finally { busy = false }
        }
    }
    LaunchedEffect(enabled) { if (!enabled) { secret = ""; visible = false; copied = false } }
    DisposableEffect(Unit) { onDispose { secret = ""; visible = false; copied = false } }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(if (visible) secret else "••••••••", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            IconButton({ if (visible) { visible = false; secret = ""; copied = false } else obtain(false) }, enabled = enabled && !busy) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "Hide" else "Show") }
            IconButton({ obtain(true) }, enabled = enabled && !busy) { Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, if (copied) "Copied" else "Copy") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
