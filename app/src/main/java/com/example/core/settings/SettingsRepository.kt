package com.example.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccentColorOption(
    val id: String,
    val name: String,
    val color: Color
)

val ACCENT_COLOR_OPTIONS = listOf(
    AccentColorOption("cyan", "Cyber Cyan", Color(0xFF00E5FF)),
    AccentColorOption("purple", "Electric Purple", Color(0xFF8B5CF6)),
    AccentColorOption("amber", "Sunset Amber", Color(0xFFF59E0B)),
    AccentColorOption("green", "Emerald Green", Color(0xFF10B981)),
    AccentColorOption("pink", "Neon Pink", Color(0xFFFF2A85)),
    AccentColorOption("blue", "Ocean Blue", Color(0xFF3B82F6))
)

data class SearchEngineOption(
    val id: String,
    val name: String,
    val searchUrlTemplate: String
)

val SEARCH_ENGINE_OPTIONS = listOf(
    SearchEngineOption("google", "Google", "https://www.google.com/search?q="),
    SearchEngineOption("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
    SearchEngineOption("bing", "Bing", "https://www.bing.com/search?q="),
    SearchEngineOption("brave", "Brave Search", "https://search.brave.com/search?q="),
    SearchEngineOption("ecosia", "Ecosia", "https://www.ecosia.org/search?q=")
)

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentColorId: String = "cyan",
    val searchEngineId: String = "google",
    val doNotTrack: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val httpsOnly: Boolean = true,
    val desktopMode: Boolean = false,
    val incognitoMode: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val audioBitrate: String = "320 kbps",
    val fastChunking: Boolean = true
) {
    val activeAccentColor: Color
        get() = ACCENT_COLOR_OPTIONS.firstOrNull { it.id == accentColorId }?.color ?: Color(0xFF00E5FF)

    val activeSearchEngine: SearchEngineOption
        get() = SEARCH_ENGINE_OPTIONS.firstOrNull { it.id == searchEngineId } ?: SEARCH_ENGINE_OPTIONS[0]
}

class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "yas_browser_settings"
        private const val KEY_THEME_MODE = "pref_theme_mode"
        private const val KEY_ACCENT_COLOR = "pref_accent_color"
        private const val KEY_SEARCH_ENGINE = "pref_search_engine"
        private const val KEY_DO_NOT_TRACK = "pref_do_not_track"
        private const val KEY_BLOCK_COOKIES = "pref_block_cookies"
        private const val KEY_HTTPS_ONLY = "pref_https_only"
        private const val KEY_DESKTOP_MODE = "pref_desktop_mode"
        private const val KEY_WIFI_ONLY = "pref_wifi_only"
        private const val KEY_AUDIO_BITRATE = "pref_audio_bitrate"
        private const val KEY_FAST_CHUNKING = "pref_fast_chunking"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadSettings(): UserSettings {
        val themeStr = prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        val themeMode = try { ThemeMode.valueOf(themeStr) } catch (e: Exception) { ThemeMode.DARK }

        return UserSettings(
            themeMode = themeMode,
            accentColorId = prefs.getString(KEY_ACCENT_COLOR, "cyan") ?: "cyan",
            searchEngineId = prefs.getString(KEY_SEARCH_ENGINE, "google") ?: "google",
            doNotTrack = prefs.getBoolean(KEY_DO_NOT_TRACK, true),
            blockThirdPartyCookies = prefs.getBoolean(KEY_BLOCK_COOKIES, true),
            httpsOnly = prefs.getBoolean(KEY_HTTPS_ONLY, true),
            desktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false),
            incognitoMode = false, // Transient per session
            wifiOnlyDownloads = prefs.getBoolean(KEY_WIFI_ONLY, false),
            audioBitrate = prefs.getString(KEY_AUDIO_BITRATE, "320 kbps") ?: "320 kbps",
            fastChunking = prefs.getBoolean(KEY_FAST_CHUNKING, true)
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    fun setAccentColor(accentId: String) {
        prefs.edit().putString(KEY_ACCENT_COLOR, accentId).apply()
        _settings.value = _settings.value.copy(accentColorId = accentId)
    }

    fun setSearchEngine(engineId: String) {
        prefs.edit().putString(KEY_SEARCH_ENGINE, engineId).apply()
        _settings.value = _settings.value.copy(searchEngineId = engineId)
    }

    fun setDoNotTrack(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DO_NOT_TRACK, enabled).apply()
        _settings.value = _settings.value.copy(doNotTrack = enabled)
    }

    fun setBlockThirdPartyCookies(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_COOKIES, enabled).apply()
        _settings.value = _settings.value.copy(blockThirdPartyCookies = enabled)
    }

    fun setHttpsOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HTTPS_ONLY, enabled).apply()
        _settings.value = _settings.value.copy(httpsOnly = enabled)
    }

    fun setDesktopMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply()
        _settings.value = _settings.value.copy(desktopMode = enabled)
    }

    fun setIncognitoMode(enabled: Boolean) {
        _settings.value = _settings.value.copy(incognitoMode = enabled)
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        _settings.value = _settings.value.copy(wifiOnlyDownloads = enabled)
    }

    fun setAudioBitrate(bitrate: String) {
        prefs.edit().putString(KEY_AUDIO_BITRATE, bitrate).apply()
        _settings.value = _settings.value.copy(audioBitrate = bitrate)
    }

    fun setFastChunking(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FAST_CHUNKING, enabled).apply()
        _settings.value = _settings.value.copy(fastChunking = enabled)
    }
}
