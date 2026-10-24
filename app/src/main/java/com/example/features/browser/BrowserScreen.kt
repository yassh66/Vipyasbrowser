package com.example.features.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import com.example.core.history.HistoryRepository
import com.example.core.media.extraction.instagram.InstagramSessionBridge
import com.example.core.navigation.UrlResolver
import com.example.core.settings.SettingsRepository
import com.example.ui.components.YASLogoEmblem
import com.example.ui.theme.InstagramGradientEnd
import com.example.ui.theme.InstagramGradientStart
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.YasPurple
import com.example.ui.theme.extendedColors

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String = "YAS Start",
    var url: String = "yas://home"
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    externalUrlToOpen: String? = null,
    onExternalUrlConsumed: () -> Unit = {},
    onOpenMediaDownload: (String) -> Unit,
    onOpenVipTools: (String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val userSettings by settingsRepository.settings.collectAsState()
    val historyRepository = remember { HistoryRepository.getInstance(context) }

    val tabs = remember { mutableStateListOf(BrowserTab(title = "YAS Start", url = "yas://home")) }
    var currentTabIndex by remember { mutableIntStateOf(0) }
    var showTabSwitcher by remember { mutableStateOf(false) }
    var showMenuDropdown by remember { mutableStateOf(false) }

    var currentUrl by remember { mutableStateOf(tabs[currentTabIndex].url) }
    var inputQuery by remember { mutableStateOf(if (currentUrl == "yas://home") "" else currentUrl) }
    var pageTitle by remember { mutableStateOf(tabs[currentTabIndex].title) }
    var pageProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(false) }
    var isImmersive by remember { mutableStateOf(false) }
    var isAddressBarFocused by remember { mutableStateOf(false) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    var customVideoView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }

    val currentTab = tabs.getOrNull(currentTabIndex) ?: tabs[0]

    fun isAuthOrMediaDomain(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("youtube.com") ||
            lower.contains("google.com") ||
            lower.contains("googlevideo.com") ||
            lower.contains("instagram.com") ||
            lower.contains("pornhub.com") ||
            lower.contains("phncdn.com") ||
            lower.contains("accounts.google.com") ||
            lower.contains("/oauth") ||
            lower.contains("/login") ||
            lower.contains("/auth") ||
            lower.contains("/signin")
    }

    fun shouldAllowThirdPartyCookies(url: String?): Boolean {
        if (userSettings.incognitoMode) return false
        return !userSettings.blockThirdPartyCookies || isAuthOrMediaDomain(url)
    }

    fun buildExtraHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (userSettings.doNotTrack) {
            headers["DNT"] = "1"
        }
        return headers
    }

    fun navigateTo(rawTarget: String) {
        val finalUrl = UrlResolver.resolveUrl(
            rawInput = rawTarget,
            searchEngineTemplate = userSettings.activeSearchEngine.searchUrlTemplate,
            httpsOnly = userSettings.httpsOnly
        )

        currentUrl = finalUrl
        inputQuery = if (finalUrl == "yas://home") "" else finalUrl
        currentTab.url = finalUrl
        currentTab.title = if (finalUrl == "yas://home") "YAS Start" else finalUrl
        focusManager.clearFocus()
        if (finalUrl == "yas://home") {
            isImmersive = false
        }

        if (finalUrl != "yas://home") {
            webViewInstance?.loadUrl(finalUrl, buildExtraHeaders())
        }
    }

    LaunchedEffect(externalUrlToOpen) {
        if (!externalUrlToOpen.isNullOrBlank()) {
            navigateTo(externalUrlToOpen)
            onExternalUrlConsumed()
        }
    }

    // Back handling: navigates popup auth window, tab switcher, fullscreen video, web history, then returns to yas://home (NEVER yasbrowser.com)
    BackHandler(enabled = popupWebView != null || showTabSwitcher || customVideoView != null || canGoBack || currentUrl != "yas://home") {
        if (popupWebView != null) {
            popupWebView?.destroy()
            popupWebView = null
            CookieManager.getInstance().flush()
            webViewInstance?.reload()
        } else if (showTabSwitcher) {
            showTabSwitcher = false
        } else if (customVideoView != null) {
            customViewCallback?.onCustomViewHidden()
            customVideoView = null
            customViewCallback = null
        } else if (canGoBack && webViewInstance != null) {
            webViewInstance?.goBack()
        } else if (currentUrl != "yas://home") {
            currentUrl = "yas://home"
            inputQuery = ""
            currentTab.url = "yas://home"
            currentTab.title = "YAS Start"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Chrome-inspired single-row collapsing top toolbar
            AnimatedVisibility(
                visible = !isImmersive || isAddressBarFocused || currentUrl == "yas://home",
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it })
            ) {
                Surface(
                    color = colors.surface,
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = if (colors.isDark) 4.dp else 2.dp,
                    shadowElevation = if (colors.isDark) 0.dp else 2.dp
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Home icon button
                            IconButton(
                                onClick = {
                                    currentUrl = "yas://home"
                                    inputQuery = ""
                                    currentTab.url = "yas://home"
                                    currentTab.title = "YAS Start"
                                    isImmersive = false
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("browser_home_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Home",
                                    tint = if (currentUrl == "yas://home") accentColor else colors.textPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // 2. Clean rounded Address / Search field (unclipped, maximized editable space, custom cursor)
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        focusRequester.requestFocus()
                                    },
                                shape = RoundedCornerShape(21.dp),
                                color = colors.inputBackground,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isAddressBarFocused) accentColor else colors.inputBorder
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Leading Security/Shield Icon
                                    Icon(
                                        imageVector = if (userSettings.incognitoMode) Icons.Default.Shield else if (currentUrl.startsWith("https://")) Icons.Default.Security else Icons.Default.Public,
                                        contentDescription = null,
                                        tint = if (userSettings.incognitoMode) Color(0xFF10B981) else if (currentUrl.startsWith("https://")) accentColor else colors.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    // Center Text Area with maximized horizontal space and no vertical clipping
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (inputQuery.isEmpty()) {
                                            Text(
                                                text = "Search or enter web address",
                                                color = colors.textMuted,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        CompositionLocalProvider(
                                            LocalTextSelectionColors provides TextSelectionColors(
                                                handleColor = accentColor,
                                                backgroundColor = accentColor.copy(alpha = 0.35f)
                                            )
                                        ) {
                                            BasicTextField(
                                                value = inputQuery,
                                                onValueChange = { inputQuery = it },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .focusRequester(focusRequester)
                                                    .onFocusChanged { isAddressBarFocused = it.isFocused }
                                                    .testTag("browser_address_bar"),
                                                singleLine = true,
                                                textStyle = TextStyle(
                                                    fontSize = 14.sp,
                                                    color = colors.textPrimary,
                                                    textDirection = TextDirection.ContentOrLtr
                                                ),
                                                cursorBrush = SolidColor(accentColor),
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                                keyboardActions = KeyboardActions(onGo = { navigateTo(inputQuery) })
                                            )
                                        }
                                    }

                                    // Trailing Action Controls: Stop, Clear (when typing), or Reload
                                    if (isLoading) {
                                        IconButton(
                                            onClick = { webViewInstance?.stopLoading() },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Stop",
                                                tint = colors.textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else if (isAddressBarFocused && inputQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { inputQuery = "" },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .testTag("browser_clear_text_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Clear",
                                                tint = colors.textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else if (inputQuery.isNotBlank() && currentUrl != "yas://home") {
                                        IconButton(
                                            onClick = { webViewInstance?.reload() },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Reload",
                                                tint = colors.textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // 3. Tab button: '+' when 1 tab, compact square counter when 2+ tabs
                            if (tabs.size <= 1) {
                                IconButton(
                                    onClick = {
                                        tabs.add(BrowserTab(title = "YAS Start", url = "yas://home"))
                                        currentTabIndex = tabs.size - 1
                                        currentUrl = "yas://home"
                                        inputQuery = ""
                                        isImmersive = false
                                    },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("browser_new_tab_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "New Tab",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            width = 1.5.dp,
                                            color = if (showTabSwitcher) accentColor else colors.textPrimary.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { showTabSwitcher = true }
                                        .testTag("browser_tabs_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${tabs.size}",
                                        color = if (showTabSwitcher) accentColor else colors.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // 4. 3-Dot Overflow Menu
                            Box {
                                IconButton(
                                    onClick = { showMenuDropdown = true },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("browser_more_menu_button")
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = colors.textPrimary, modifier = Modifier.size(22.dp))
                                }

                                DropdownMenu(
                                    expanded = showMenuDropdown,
                                    onDismissRequest = { showMenuDropdown = false },
                                    modifier = Modifier
                                        .width(240.dp)
                                        .background(colors.cardBackground)
                                ) {
                                    // Top quick navigation action row: Back, Forward, Reload, Home
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = {
                                                showMenuDropdown = false
                                                webViewInstance?.goBack()
                                            },
                                            enabled = canGoBack,
                                            modifier = Modifier.size(38.dp).testTag("menu_nav_back")
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back",
                                                tint = if (canGoBack) colors.textPrimary else colors.textMuted,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                showMenuDropdown = false
                                                webViewInstance?.goForward()
                                            },
                                            enabled = canGoForward,
                                            modifier = Modifier.size(38.dp).testTag("menu_nav_forward")
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = "Forward",
                                                tint = if (canGoForward) colors.textPrimary else colors.textMuted,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                showMenuDropdown = false
                                                webViewInstance?.reload()
                                            },
                                            modifier = Modifier.size(38.dp).testTag("menu_nav_reload")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Reload",
                                                tint = colors.textPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                showMenuDropdown = false
                                                currentUrl = "yas://home"
                                                inputQuery = ""
                                                currentTab.url = "yas://home"
                                                currentTab.title = "YAS Start"
                                            },
                                            modifier = Modifier.size(38.dp).testTag("menu_nav_home")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Home,
                                                contentDescription = "Home",
                                                tint = colors.textPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = colors.cardBorder
                                    )

                                    DropdownMenuItem(
                                        text = { Text("New Tab", color = colors.textPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = accentColor) },
                                        onClick = {
                                            showMenuDropdown = false
                                            tabs.add(BrowserTab(title = "YAS Start", url = "yas://home"))
                                            currentTabIndex = tabs.size - 1
                                            currentUrl = "yas://home"
                                            inputQuery = ""
                                        },
                                        modifier = Modifier.testTag("menu_item_new_tab")
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (userSettings.incognitoMode) "Exit Incognito" else "New Incognito Tab",
                                                color = if (userSettings.incognitoMode) Color(0xFF10B981) else colors.textPrimary
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Shield,
                                                contentDescription = null,
                                                tint = if (userSettings.incognitoMode) Color(0xFF10B981) else colors.textSecondary
                                            )
                                        },
                                        onClick = {
                                            showMenuDropdown = false
                                            settingsRepository.setIncognitoMode(!userSettings.incognitoMode)
                                        },
                                        modifier = Modifier.testTag("menu_item_incognito")
                                    )

                                    DropdownMenuItem(
                                        text = { Text("History", color = colors.textPrimary) },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null, tint = accentColor) },
                                        onClick = {
                                            showMenuDropdown = false
                                            onOpenHistory()
                                        },
                                        modifier = Modifier.testTag("menu_item_history")
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Media Downloader", color = colors.textPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = accentColor) },
                                        onClick = {
                                            showMenuDropdown = false
                                            val url = if (currentUrl != "yas://home") currentUrl else ""
                                            onOpenMediaDownload(url)
                                        },
                                        modifier = Modifier.testTag("menu_item_downloads")
                                    )

                                    DropdownMenuItem(
                                        text = { Text("VIP Tools", color = colors.textPrimary) },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = accentColor) },
                                        onClick = {
                                            showMenuDropdown = false
                                            val url = if (currentUrl != "yas://home") currentUrl else ""
                                            onOpenVipTools(url)
                                        },
                                        modifier = Modifier.testTag("menu_item_vip_tools")
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (userSettings.desktopMode) "Mobile Version" else "Desktop Site",
                                                color = colors.textPrimary
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.DesktopWindows,
                                                contentDescription = null,
                                                tint = if (userSettings.desktopMode) accentColor else colors.textSecondary
                                            )
                                        },
                                        onClick = {
                                            showMenuDropdown = false
                                            settingsRepository.setDesktopMode(!userSettings.desktopMode)
                                        },
                                        modifier = Modifier.testTag("menu_item_desktop_mode")
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Settings", color = colors.textPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = colors.textSecondary) },
                                        onClick = {
                                            showMenuDropdown = false
                                            onOpenSettings()
                                        },
                                        modifier = Modifier.testTag("menu_item_settings")
                                    )

                                    if (currentUrl != "yas://home" && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) {
                                        DropdownMenuItem(
                                            text = { Text("Open in External Browser", color = colors.textPrimary) },
                                            leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = colors.textSecondary) },
                                            onClick = {
                                                showMenuDropdown = false
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            },
                                            modifier = Modifier.testTag("menu_item_open_external")
                                        )
                                    }
                                }
                            }
                        }

                        // Web loading progress indicator
                        if (isLoading && pageProgress > 0f && pageProgress < 1f) {
                            LinearProgressIndicator(
                                progress = { pageProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp),
                                color = accentColor,
                                trackColor = colors.surfaceVariant
                            )
                        }
                    }
                }
            }

            // Main Web & Home View
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (currentUrl == "yas://home") {
                    HomeExperience(
                        searchEngineName = userSettings.activeSearchEngine.name,
                        isIncognito = userSettings.incognitoMode,
                        onNavigateToUrl = { target -> navigateTo(target) },
                        onOpenSearch = { focusRequester.requestFocus() },
                        onOpenMediaDownload = { onOpenMediaDownload("") },
                        onOpenVipTools = { onOpenVipTools("") },
                        onToggleIncognito = { settingsRepository.setIncognitoMode(!userSettings.incognitoMode) }
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                webViewInstance = this
                                InstagramSessionBridge.registerActiveWebView(this)

                                val currentUA = if (userSettings.desktopMode) {
                                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                                } else {
                                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                                }

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    useWideViewPort = userSettings.desktopMode
                                    loadWithOverviewMode = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    userAgentString = currentUA
                                    allowContentAccess = true
                                    allowFileAccess = true
                                    setSupportMultipleWindows(true)
                                    javaScriptCanOpenWindowsAutomatically = true
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                }

                                // Cookie handling: ensure auth providers (Google OAuth), adult platforms, and media streaming CDNs receive cookies
                                val allowThirdPartyCookies = shouldAllowThirdPartyCookies(currentUrl)
                                CookieManager.getInstance().setAcceptCookie(!userSettings.incognitoMode)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, allowThirdPartyCookies)

                                // Smart Immersive Scroll & Reveal Handlers
                                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                                    if (!isAddressBarFocused) {
                                        val dy = scrollY - oldScrollY
                                        if (dy > 14 && scrollY > 80) {
                                            // Scroll down -> hide toolbars smoothly
                                            if (!isImmersive) isImmersive = true
                                        } else if (dy < -14 || scrollY <= 10) {
                                            // Scroll up or near top -> reveal toolbars smoothly
                                            if (isImmersive) isImmersive = false
                                        }
                                    }
                                }

                                setOnLongClickListener {
                                    // Deliberate long press reveals toolbars
                                    if (isImmersive) {
                                        isImmersive = false
                                        true
                                    } else {
                                        false
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                        view?.destroy()
                                        webViewInstance = null
                                        return true
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        isLoading = true
                                        url?.let {
                                            currentUrl = it
                                            inputQuery = it
                                            currentTab.url = it
                                        }
                                        canGoBack = canGoBack()
                                        canGoForward = canGoForward()
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isLoading = false
                                        view?.title?.let {
                                            pageTitle = it
                                            currentTab.title = it
                                        }
                                        canGoBack = canGoBack()
                                        canGoForward = canGoForward()
                                        CookieManager.getInstance().flush()

                                        // Blank callback safety fallback: if OAuth callback page loaded in main window
                                        if (url != null && (url.contains("/oauth/") || url.contains("/callback")) && url.contains("pornhub.com")) {
                                            view?.evaluateJavascript("(function(){ return document.body ? document.body.innerHTML.trim() : ''; })();") { bodyHtml ->
                                                if (bodyHtml == null || bodyHtml == "\"\"" || bodyHtml == "null") {
                                                    val origin = try {
                                                        val parsed = Uri.parse(url)
                                                        "${parsed.scheme}://${parsed.host}"
                                                    } catch (_: Exception) {
                                                        "https://www.pornhub.com"
                                                    }
                                                    view.loadUrl(origin, buildExtraHeaders())
                                                }
                                            }
                                        }

                                        // Record persistent browsing history (incognito excluded)
                                        if (url != null && !userSettings.incognitoMode) {
                                            historyRepository.recordVisit(
                                                url = url,
                                                rawTitle = view?.title ?: pageTitle,
                                                isIncognito = userSettings.incognitoMode
                                            )
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val reqUrl = request?.url?.toString() ?: return false

                                        // Intent scheme handling: prevent ERR_UNKNOWN_URL_SCHEME and keep inside browser
                                        if (reqUrl.startsWith("intent://", ignoreCase = true)) {
                                            try {
                                                val intent = Intent.parseUri(reqUrl, Intent.URI_INTENT_SCHEME)
                                                val fallback = intent.getStringExtra("browser_fallback_url")
                                                if (!fallback.isNullOrBlank()) {
                                                    view?.loadUrl(fallback, buildExtraHeaders())
                                                    return true
                                                }
                                            } catch (_: Exception) {}
                                            return true
                                        }

                                        // HTTPS Enforcement
                                        if (userSettings.httpsOnly && reqUrl.startsWith("http://", ignoreCase = true)) {
                                            val secureUrl = "https://" + reqUrl.removePrefix("http://")
                                            view?.loadUrl(secureUrl, buildExtraHeaders())
                                            return true
                                        }
                                        return false
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        pageProgress = newProgress / 100f
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        title?.let {
                                            pageTitle = it
                                            currentTab.title = it
                                        }
                                    }

                                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                        customVideoView = view
                                        customViewCallback = callback
                                    }

                                    override fun onHideCustomView() {
                                        customViewCallback?.onCustomViewHidden()
                                        customVideoView = null
                                        customViewCallback = null
                                    }

                                    override fun onPermissionRequest(request: PermissionRequest?) {
                                        if (request == null) return
                                        val resources = request.resources
                                        // Auto-grant protected media id for EME / DRM HTML5 video playback
                                        val protectedMedia = resources.filter {
                                            it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                                        }
                                        if (protectedMedia.isNotEmpty()) {
                                            request.grant(protectedMedia.toTypedArray())
                                        } else {
                                            request.grant(resources)
                                        }
                                    }

                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?
                                    ): Boolean {
                                        if (resultMsg == null || view == null) return false
                                        val newWebView = WebView(view.context).apply {
                                            layoutParams = ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                            settings.apply {
                                                javaScriptEnabled = true
                                                domStorageEnabled = true
                                                databaseEnabled = true
                                                setSupportZoom(true)
                                                builtInZoomControls = true
                                                displayZoomControls = false
                                                userAgentString = view.settings.userAgentString
                                                setSupportMultipleWindows(true)
                                                javaScriptCanOpenWindowsAutomatically = true
                                            }
                                            CookieManager.getInstance().setAcceptCookie(!userSettings.incognitoMode)
                                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                            webViewClient = object : WebViewClient() {
                                                override fun onPageFinished(popupView: WebView?, popupUrl: String?) {
                                                    super.onPageFinished(popupView, popupUrl)
                                                    CookieManager.getInstance().flush()
                                                }
                                            }

                                            webChromeClient = object : WebChromeClient() {
                                                override fun onCloseWindow(window: WebView?) {
                                                    window?.destroy()
                                                    popupWebView = null
                                                    CookieManager.getInstance().flush()
                                                    webViewInstance?.reload()
                                                }
                                            }
                                        }

                                        val transport = resultMsg.obj as? WebView.WebViewTransport
                                        if (transport != null) {
                                            transport.webView = newWebView
                                            resultMsg.sendToTarget()
                                            popupWebView = newWebView
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onCloseWindow(window: WebView?) {
                                        if (window == popupWebView) {
                                            window?.destroy()
                                            popupWebView = null
                                            CookieManager.getInstance().flush()
                                            webViewInstance?.reload()
                                        }
                                    }
                                }

                                loadUrl(currentUrl, buildExtraHeaders())
                            }
                        },
                        update = { view ->
                            InstagramSessionBridge.registerActiveWebView(view)
                            // Dynamically apply desktop mode updates
                            val targetUA = if (userSettings.desktopMode) {
                                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                            } else {
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                            }
                            if (view.settings.userAgentString != targetUA) {
                                view.settings.userAgentString = targetUA
                                view.settings.useWideViewPort = userSettings.desktopMode
                                view.reload()
                            }

                            // Dynamically apply cookie settings
                            val allowThirdPartyCookies = shouldAllowThirdPartyCookies(currentUrl)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(view, allowThirdPartyCookies)

                            if (view.url != currentUrl && currentUrl != "yas://home") {
                                view.loadUrl(currentUrl, buildExtraHeaders())
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

        }

        // Tab Switcher View
        if (showTabSwitcher) {
            TabSwitcherView(
                tabs = tabs,
                activeTabIndex = currentTabIndex,
                isIncognito = userSettings.incognitoMode,
                onSelectTab = { index ->
                    currentTabIndex = index
                    currentUrl = tabs[index].url
                    inputQuery = if (tabs[index].url == "yas://home") "" else tabs[index].url
                    showTabSwitcher = false
                },
                onCloseTab = { index ->
                    if (tabs.size > 1) {
                        tabs.removeAt(index)
                        if (currentTabIndex >= tabs.size) {
                            currentTabIndex = tabs.size - 1
                        }
                        currentUrl = tabs[currentTabIndex].url
                        inputQuery = if (currentUrl == "yas://home") "" else currentUrl
                    } else {
                        tabs[0] = BrowserTab(title = "YAS Start", url = "yas://home")
                        currentTabIndex = 0
                        currentUrl = "yas://home"
                        inputQuery = ""
                    }
                },
                onNewTab = {
                    tabs.add(BrowserTab(title = "YAS Start", url = "yas://home"))
                    currentTabIndex = tabs.size - 1
                    currentUrl = "yas://home"
                    inputQuery = ""
                    showTabSwitcher = false
                },
                onDismiss = {
                    showTabSwitcher = false
                }
            )
        }

        // Fullscreen video playback overlay
        if (customVideoView != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .zIndex(100f)
            ) {
                AndroidView(
                    factory = { customVideoView!! },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Popup WebView Dialog (for Google OAuth and multi-window popups)
        if (popupWebView != null) {
            Dialog(
                onDismissRequest = {
                    popupWebView?.destroy()
                    popupWebView = null
                    CookieManager.getInstance().flush()
                    webViewInstance?.reload()
                },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = true
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = colors.background
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surface)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Authentication / Sign In",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                            IconButton(
                                onClick = {
                                    popupWebView?.destroy()
                                    popupWebView = null
                                    CookieManager.getInstance().flush()
                                    webViewInstance?.reload()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = colors.textPrimary
                                )
                            }
                        }
                        AndroidView(
                            factory = { popupWebView!! },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
