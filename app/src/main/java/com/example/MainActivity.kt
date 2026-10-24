package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.core.settings.SettingsRepository
import com.example.core.settings.ThemeMode
import com.example.features.browser.BrowserScreen
import com.example.features.media.MediaDownloadScreen
import com.example.features.settings.SettingsScreen
import com.example.features.vip.VIPToolsScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.YASBrowserTheme

sealed class Screen {
    object Browser : Screen()
    object Settings : Screen()
    data class MediaDownload(val targetUrl: String = "") : Screen()
    data class VIPTools(val targetUrl: String = "") : Screen()
    object History : Screen()
}

class MainActivity : ComponentActivity() {

    private var initialShareUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        val settingsRepository = SettingsRepository.getInstance(this)

        setContent {
            val userSettings by settingsRepository.settings.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (userSettings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }

            YASBrowserTheme(
                darkTheme = isDark,
                accentColor = userSettings.activeAccentColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember {
                        mutableStateOf<Screen>(
                            if (!initialShareUrl.isNullOrBlank()) {
                                Screen.MediaDownload(initialShareUrl ?: "")
                            } else {
                                Screen.Browser
                            }
                        )
                    }
                    var pendingUrlForBrowser by remember { mutableStateOf<String?>(null) }

                    Box(modifier = Modifier.fillMaxSize()) {
                        BrowserScreen(
                            externalUrlToOpen = pendingUrlForBrowser,
                            onExternalUrlConsumed = { pendingUrlForBrowser = null },
                            onOpenMediaDownload = { mediaUrl ->
                                currentScreen = Screen.MediaDownload(mediaUrl)
                            },
                            onOpenVipTools = { vipUrl ->
                                currentScreen = Screen.VIPTools(vipUrl)
                            },
                            onOpenSettings = {
                                currentScreen = Screen.Settings
                            },
                            onOpenHistory = {
                                currentScreen = Screen.History
                            }
                        )

                        when (val screen = currentScreen) {
                            is Screen.Browser -> {
                                // BrowserScreen is the active visible screen
                            }
                            is Screen.MediaDownload -> {
                                MediaDownloadScreen(
                                    initialUrl = screen.targetUrl,
                                    onNavigateBack = {
                                        currentScreen = Screen.Browser
                                    }
                                )
                            }
                            is Screen.VIPTools -> {
                                VIPToolsScreen(
                                    initialUrl = screen.targetUrl,
                                    onNavigateBack = {
                                        currentScreen = Screen.Browser
                                    }
                                )
                            }
                            is Screen.Settings -> {
                                SettingsScreen(
                                    onNavigateBack = {
                                        currentScreen = Screen.Browser
                                    }
                                )
                            }
                            is Screen.History -> {
                                com.example.features.history.HistoryScreen(
                                    onNavigateBack = {
                                        currentScreen = Screen.Browser
                                    },
                                    onSelectUrl = { selectedUrl ->
                                        pendingUrlForBrowser = selectedUrl
                                        currentScreen = Screen.Browser
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val extractedUrl = extractUrlFromText(sharedText)
                initialShareUrl = extractedUrl ?: sharedText
            }
        } else if (Intent.ACTION_VIEW == action) {
            val data = intent.dataString
            if (!data.isNullOrBlank()) {
                initialShareUrl = data
            }
        }
    }

    private fun extractUrlFromText(text: String): String? {
        val pattern = java.util.regex.Pattern.compile("(https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+)")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }
}
