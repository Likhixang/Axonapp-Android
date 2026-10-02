package cc.khixang.axonhub

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val accent: Long = 0xff4f46e5, val locale: String = "")

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("axonhub_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AppSettings(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        prefs.getLong("accent", 0xff4f46e5), prefs.getString("locale", "").orEmpty(),
    ))
    val state = _state.asStateFlow()
    init { applyLocale(_state.value.locale) }
    fun update(value: AppSettings) {
        _state.value = value
        prefs.edit().putString("theme", value.theme.name).putLong("accent", value.accent).putString("locale", value.locale).apply()
        applyLocale(value.locale)
    }
    private fun applyLocale(tag: String) { AppCompatDelegate.setApplicationLocales(if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)) }
}
