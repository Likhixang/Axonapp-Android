package cc.khixang.axonhub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import cc.khixang.axonhub.AppSettings
import cc.khixang.axonhub.ThemeMode

@Composable fun AxonTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val accent = Color(settings.accent.toInt())
    val scheme = if (dark) darkColorScheme(primary = accent, secondary = accent, tertiary = accent) else lightColorScheme(primary = accent, secondary = accent, tertiary = accent)
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
