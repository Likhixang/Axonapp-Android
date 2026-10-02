package cc.khixang.axonhub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.khixang.axonhub.AppSettings
import cc.khixang.axonhub.ThemeMode

@Composable
fun AxonTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = Color(settings.accent.toInt())
    val accentInk = if (accent.luminance() > 0.45f) Color.Black else Color.White
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = accentInk,
        primaryContainer = accent.copy(alpha = 0.20f), onPrimaryContainer = Color(0xFFF2F2F7),
        secondary = Color(0xFFB5B5BE), onSecondary = Color(0xFF171719),
        tertiary = accent, background = Color(0xFF101012), onBackground = Color(0xFFF2F2F7),
        surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF2F2F7),
        surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFFAEAEB8),
        surfaceContainerLowest = Color(0xFF101012), surfaceContainerLow = Color(0xFF1C1C1E),
        surfaceContainer = Color(0xFF242426), surfaceContainerHigh = Color(0xFF2C2C2E),
        surfaceContainerHighest = Color(0xFF363638), surfaceTint = Color.Transparent,
        outline = Color(0xFF63636B), outlineVariant = Color(0xFF39393D),
        error = Color(0xFFFF6961),
    ) else lightColorScheme(
        primary = accent, onPrimary = accentInk,
        primaryContainer = accent.copy(alpha = 0.10f), onPrimaryContainer = Color(0xFF1C1C1E),
        secondary = Color(0xFF63636B), onSecondary = Color.White,
        tertiary = accent, background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E),
        surface = Color.White, onSurface = Color(0xFF1C1C1E),
        surfaceVariant = Color(0xFFEAEAEE), onSurfaceVariant = Color(0xFF63636B),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F8FA),
        surfaceContainer = Color(0xFFF2F2F7), surfaceContainerHigh = Color(0xFFEAEAEE),
        surfaceContainerHighest = Color(0xFFE3E3E8), surfaceTint = Color.Transparent,
        outline = Color(0xFF8E8E98), outlineVariant = Color(0xFFDDDDE3),
    )
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
            headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
            titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
        ), content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalContentColor provides scheme.onSurface,
            ) { content() }
        },
    )
}
