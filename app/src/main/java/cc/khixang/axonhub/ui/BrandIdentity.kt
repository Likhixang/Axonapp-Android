package cc.khixang.axonhub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import org.json.JSONObject
import java.util.Locale

/** Match iOS BrandIdentity: explicit model icon or exact channel type only.
 * Unknown metadata gets a plain initial, never an invented provider logo.
 */
@Composable
fun BrandMark(name: String, icon: String? = null, channelType: String? = null, size: Dp = 32.dp) {
    val context = LocalContext.current
    val available = remember(context) { context.assets.list("brand-icons").orEmpty().toSet() }
    val channelIcons = remember(context) {
        runCatching { JSONObject(context.assets.open("brand-icons/provenance.json").bufferedReader().use { it.readText() }).getJSONObject("channelTypes") }
            .getOrDefault(JSONObject())
    }
    val candidate = if (channelType != null) channelIcons.optString(channelType)
        else icon.orEmpty().lowercase(Locale.ROOT)
    val valid = candidate.matches(Regex("[a-z0-9]+")) && "$candidate.png" in available
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (valid) Image(rememberAsyncImagePainter("file:///android_asset/brand-icons/$candidate.png"), null, Modifier.size(size))
        else Text(name.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
