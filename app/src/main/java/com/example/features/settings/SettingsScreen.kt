package com.example.features.settings

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.settings.ACCENT_COLOR_OPTIONS
import com.example.core.settings.SEARCH_ENGINE_OPTIONS
import com.example.core.settings.SettingsRepository
import com.example.core.settings.ThemeMode
import com.example.ui.components.YASLogoEmblem
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.YasPurple
import com.example.ui.theme.extendedColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val repository = remember { SettingsRepository.getInstance(context) }
    val userSettings by repository.settings.collectAsState()
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    var showClearDataDialog by remember { mutableStateOf(false) }
    var showSearchEngineDialog by remember { mutableStateOf(false) }
    var showBitrateDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag("settings_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Section 1: Appearance & Accent Colors
            item {
                SettingsSectionCard(title = "Appearance & Theming", icon = Icons.Default.Palette) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Theme Mode",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ThemeMode.values().forEach { mode ->
                                val isSelected = userSettings.themeMode == mode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) accentColor.copy(alpha = if (colors.isDark) 0.22f else 0.15f) else colors.surfaceVariant)
                                        .border(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = if (isSelected) accentColor else colors.cardBorder,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { repository.setThemeMode(mode) }
                                        .padding(vertical = 10.dp)
                                        .testTag("theme_chip_${mode.name.lowercase()}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = mode.name.lowercase().capitalize(),
                                        color = if (isSelected) accentColor else colors.textSecondary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Accent Color (Affects Highlights, Glows & Buttons)",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        // Interactive Accent Color Swatches
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            ACCENT_COLOR_OPTIONS.forEach { option ->
                                val isSelected = userSettings.accentColorId == option.id
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(option.color)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) (if (colors.isDark) Color.White else colors.textPrimary) else colors.cardBorder,
                                            shape = CircleShape
                                        )
                                        .clickable { repository.setAccentColor(option.id) }
                                        .testTag("accent_color_${option.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Search Engine & Navigation
            item {
                SettingsSectionCard(title = "Search & Navigation", icon = Icons.Default.Search) {
                    SettingsActionRow(
                        title = "Default Search Engine",
                        subtitle = userSettings.activeSearchEngine.name,
                        onClick = { showSearchEngineDialog = true },
                        tag = "setting_search_engine"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsToggleRow(
                        title = "Desktop Site by Default",
                        subtitle = "Request desktop view version for all websites",
                        checked = userSettings.desktopMode,
                        onCheckedChange = { repository.setDesktopMode(it) },
                        tag = "setting_desktop_mode"
                    )
                }
            }

            // Section 3: Privacy & Security
            item {
                SettingsSectionCard(title = "Privacy & Protection", icon = Icons.Default.Shield) {
                    SettingsToggleRow(
                        title = "Do Not Track (DNT)",
                        subtitle = "Transmit DNT: 1 header with web page requests",
                        checked = userSettings.doNotTrack,
                        onCheckedChange = { repository.setDoNotTrack(it) },
                        tag = "setting_dnt"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsToggleRow(
                        title = "Block Third-Party Cookies",
                        subtitle = "Prevent cross-site tracking and third-party advertising cookies",
                        checked = userSettings.blockThirdPartyCookies,
                        onCheckedChange = { repository.setBlockThirdPartyCookies(it) },
                        tag = "setting_block_cookies"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsToggleRow(
                        title = "Enforce HTTPS Connection",
                        subtitle = "Automatically upgrade HTTP requests to secure HTTPS",
                        checked = userSettings.httpsOnly,
                        onCheckedChange = { repository.setHttpsOnly(it) },
                        tag = "setting_https_only"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceVariant)
                            .clickable { showClearDataDialog = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("setting_clear_browsing_data"),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Clear Browsing Data",
                                    color = ErrorRed,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Purge web cache, cookies, and local web storage",
                                    color = colors.textMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Section 4: Media & Downloads
            item {
                SettingsSectionCard(title = "Media Downloader Preferences", icon = Icons.Default.Download) {
                    SettingsToggleRow(
                        title = "Download on Wi-Fi Only",
                        subtitle = "When disabled (default), downloads work freely over both cellular data and Wi-Fi",
                        checked = userSettings.wifiOnlyDownloads,
                        onCheckedChange = { repository.setWifiOnlyDownloads(it) },
                        tag = "setting_wifi_only"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsActionRow(
                        title = "Audio Extraction Bitrate",
                        subtitle = "${userSettings.audioBitrate} (MP3)",
                        onClick = { showBitrateDialog = true },
                        tag = "setting_audio_bitrate"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsToggleRow(
                        title = "Multi-Threaded Turbo Chunking",
                        subtitle = "Accelerate stream downloads with 64KB high-throughput I/O buffers",
                        checked = userSettings.fastChunking,
                        onCheckedChange = { repository.setFastChunking(it) },
                        tag = "setting_fast_chunking"
                    )
                }
            }

            // Section 5: About & Engine
            item {
                SettingsSectionCard(title = "About", icon = Icons.Default.Info) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        YASLogoEmblem(size = 40.dp, primaryColor = accentColor, secondaryColor = YasPurple)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "YAS Browser PRO",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Version 2.4.0 (Build 2400) • Chromium WebKit",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Search Engine Selection Dialog
        if (showSearchEngineDialog) {
            AlertDialog(
                onDismissRequest = { showSearchEngineDialog = false },
                title = { Text("Choose Search Engine", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SEARCH_ENGINE_OPTIONS.forEach { engine ->
                            val isSelected = userSettings.searchEngineId == engine.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        repository.setSearchEngine(engine.id)
                                        showSearchEngineDialog = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        repository.setSearchEngine(engine.id)
                                        showSearchEngineDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(engine.name, color = colors.textPrimary, fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSearchEngineDialog = false }) {
                        Text("Cancel", color = accentColor)
                    }
                },
                containerColor = colors.cardBackground
            )
        }

        // Bitrate Selection Dialog
        if (showBitrateDialog) {
            val bitrates = listOf("320 kbps", "256 kbps", "192 kbps", "128 kbps")
            AlertDialog(
                onDismissRequest = { showBitrateDialog = false },
                title = { Text("Audio Transcoding Quality", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        bitrates.forEach { rate ->
                            val isSelected = userSettings.audioBitrate == rate
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        repository.setAudioBitrate(rate)
                                        showBitrateDialog = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        repository.setAudioBitrate(rate)
                                        showBitrateDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(rate, color = colors.textPrimary, fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBitrateDialog = false }) {
                        Text("Cancel", color = accentColor)
                    }
                },
                containerColor = colors.cardBackground
            )
        }

        // Clear Browsing Data Dialog
        if (showClearDataDialog) {
            AlertDialog(
                onDismissRequest = { showClearDataDialog = false },
                title = { Text("Clear All Browsing Data?", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "This will clear browser cache, cookies, and local database storage. Downloaded media files will be kept safely.",
                        color = colors.textSecondary,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            try {
                                context.cacheDir?.deleteRecursively()
                            } catch (e: Exception) {
                                // ignore
                            }
                            Toast.makeText(context, "Browsing cache, cookies, and web storage cleared", Toast.LENGTH_SHORT).show()
                            showClearDataDialog = false
                        }
                    ) {
                        Text("Clear Data", color = ErrorRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDataDialog = false }) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                },
                containerColor = colors.cardBackground
            )
        }
    }
}

@Composable
fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(colors.cardBorder, accentColor.copy(alpha = 0.25f)))
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (colors.isDark) 0.dp else 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = colors.textSecondary, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accentColor,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.surfaceVariant
            )
        )
    }
}

@Composable
fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tag: String
) {
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun String.capitalize(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
