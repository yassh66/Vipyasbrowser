package com.example.features.vip

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.core.vip.extraction.VIPExtractionManager
import com.example.core.vip.extraction.VIPExtractionResult
import com.example.core.vip.extraction.VIPMediaMetadata
import com.example.core.vip.extraction.VIPMediaStream
import androidx.compose.material3.LinearProgressIndicator
import com.example.core.vip.download.VIPDownloadEngine
import com.example.core.vip.download.VIPDownloadItem
import com.example.core.vip.download.VIPDownloadStatus
import com.example.core.vip.download.VIPMediaPublisher
import com.example.core.vip.download.VIPPublishResult
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.YasPurple
import com.example.ui.theme.extendedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VIPToolsScreen(
    initialUrl: String = "",
    onNavigateBack: () -> Unit,
    downloadEngine: VIPDownloadEngine? = null,
    mediaPublisher: VIPMediaPublisher? = null,
    extractionManager: VIPExtractionManager? = null
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    val vipExtractionManager = remember(extractionManager) { extractionManager ?: VIPExtractionManager() }
    val vipDownloadEngine = remember(downloadEngine) { downloadEngine ?: VIPDownloadEngine(context) }
    val vipMediaPublisher = remember(mediaPublisher) { mediaPublisher ?: VIPMediaPublisher(context) }

    var urlInput by remember { mutableStateOf(initialUrl) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var extractionResult by remember { mutableStateOf<VIPExtractionResult?>(null) }
    var selectedStream by remember { mutableStateOf<VIPMediaStream?>(null) }

    var activeTaskId by remember { mutableStateOf<String?>(null) }
    var activeDownloadItem by remember { mutableStateOf<VIPDownloadItem?>(null) }
    var publishResult by remember { mutableStateOf<VIPPublishResult?>(null) }
    var isPublishing by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(activeTaskId) {
        val taskId = activeTaskId ?: return@LaunchedEffect
        vipDownloadEngine.observeDownload(taskId).collect { item ->
            activeDownloadItem = item
            if (item != null && item.status == VIPDownloadStatus.COMPLETED && !isPublishing && publishResult == null) {
                isPublishing = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        vipMediaPublisher.publishMedia(item, deleteSourceOnSuccess = true)
                    }
                    publishResult = result
                    if (result is VIPPublishResult.Success) {
                        Toast.makeText(context, "Saved to Gallery (${result.displayName})", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    publishResult = VIPPublishResult.Failure(e.localizedMessage ?: "Failed to save media", e)
                } finally {
                    isPublishing = false
                }
            }
        }
    }

    fun startVipDownload(stream: VIPMediaStream, metadata: VIPMediaMetadata) {
        val existingActive = vipDownloadEngine.downloads.value.find { it.streamUrl == stream.url && it.isActive }
        if (existingActive != null) {
            activeTaskId = existingActive.id
            publishResult = null
            isPublishing = false
            showDownloadDialog = true
            Toast.makeText(context, "Resuming active download for this media", Toast.LENGTH_SHORT).show()
            return
        }

        publishResult = null
        isPublishing = false
        val startResult = vipDownloadEngine.startDownload(metadata, stream)
        startResult.fold(
            onSuccess = { taskId ->
                activeTaskId = taskId
                showDownloadDialog = true
            },
            onFailure = { error ->
                Toast.makeText(context, error.localizedMessage ?: "Failed to start download", Toast.LENGTH_LONG).show()
            }
        )
    }

    fun analyzeUrl(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) {
            Toast.makeText(context, "Please enter a media URL", Toast.LENGTH_SHORT).show()
            return
        }

        isAnalyzing = true
        extractionResult = null
        selectedStream = null

        scope.launch {
            try {
                val result = vipExtractionManager.extractMedia(trimmed)
                extractionResult = result
                if (result is VIPExtractionResult.Success) {
                    selectedStream = result.streams.firstOrNull()
                }
            } catch (e: Exception) {
                extractionResult = VIPExtractionResult.Error(
                    message = e.localizedMessage ?: "Failed to analyze URL",
                    throwable = e
                )
            } finally {
                isAnalyzing = false
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) {
            analyzeUrl(initialUrl)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("vip_tools_screen"),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(accentColor, YasPurple)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "VIP Tools",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.testTag("vip_header_title")
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("vip_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("vip_content_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // URL Input Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vip_input_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(
                            listOf(
                                accentColor.copy(alpha = 0.5f),
                                YasPurple.copy(alpha = 0.3f)
                            )
                        )
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Media Link Analyzer",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(accentColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "PREMIUM",
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = {
                                Text(
                                    text = "Enter media link",
                                    color = colors.textMuted,
                                    fontSize = 14.sp
                                )
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (urlInput.isNotBlank()) {
                                        IconButton(
                                            onClick = { urlInput = "" },
                                            modifier = Modifier.testTag("vip_clear_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear",
                                                tint = colors.textSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            if (clipboard.hasPrimaryClip() &&
                                                (clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true ||
                                                 clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true)
                                            ) {
                                                val clipItem = clipboard.primaryClip?.getItemAt(0)
                                                val text = clipItem?.text?.toString() ?: ""
                                                if (text.isNotBlank()) {
                                                    urlInput = text
                                                    analyzeUrl(text)
                                                }
                                            } else {
                                                Toast.makeText(context, "Clipboard empty", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.testTag("vip_paste_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentPaste,
                                            contentDescription = "Paste",
                                            tint = accentColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vip_url_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = colors.inputBackground,
                                unfocusedContainerColor = colors.inputBackground,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = colors.inputBorder,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            )
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { analyzeUrl(urlInput) },
                            enabled = !isAnalyzing && urlInput.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("vip_analyze_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = Color.White
                            )
                        ) {
                            if (isAnalyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Analyzing Stream...", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Analyze Media", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Results Section
            when (val result = extractionResult) {
                is VIPExtractionResult.Success -> {
                    // Metadata card
                    item {
                        VIPMetadataCard(
                            metadata = result.metadata,
                            accentColor = accentColor,
                            colors = colors
                        )
                    }

                    // Available Qualities header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Available Streams (${result.streams.size})",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "Verified Formats",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Format Stream List
                    items(result.streams) { stream ->
                        val isSelected = selectedStream?.id == stream.id
                        VIPStreamFormatCard(
                            stream = stream,
                            isSelected = isSelected,
                            accentColor = accentColor,
                            colors = colors,
                            onSelect = { selectedStream = stream }
                        )
                    }

                    // Primary Action Footer
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vip_action_panel"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = Brush.linearGradient(
                                    listOf(accentColor.copy(alpha = 0.4f), colors.cardBorder)
                                )
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Selected: ${selectedStream?.quality ?: "None"} (${selectedStream?.format ?: "MP4"})",
                                            color = colors.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "Direct high-speed stream ready",
                                            color = colors.textSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Copy Stream Link Button
                                    Button(
                                        onClick = {
                                            selectedStream?.let { stream ->
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = android.content.ClipData.newPlainText("VIP Stream URL", stream.url)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "Stream link copied to clipboard", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .testTag("vip_copy_stream_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.surfaceVariant,
                                            contentColor = colors.textPrimary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Copy Link", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }

                                    // Download Button (triggers real Phase 3 VIP download & publishing engine)
                                    Button(
                                        onClick = {
                                            val stream = selectedStream
                                            val metadata = (extractionResult as? VIPExtractionResult.Success)?.metadata
                                            if (metadata != null && stream != null) {
                                                startVipDownload(stream, metadata)
                                            } else {
                                                Toast.makeText(context, "Please select a stream first", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        enabled = selectedStream != null && !isAnalyzing,
                                        modifier = Modifier
                                            .weight(1.3f)
                                            .height(46.dp)
                                            .testTag("vip_download_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = accentColor,
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Download", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                is VIPExtractionResult.AccessRestricted -> {
                    item {
                        VIPStatusBanner(
                            icon = Icons.Default.Lock,
                            iconColor = Color(0xFFF59E0B),
                            title = "Access Restricted",
                            message = result.reason,
                            testTag = "vip_restricted_banner",
                            colors = colors,
                            accentColor = accentColor
                        )
                    }
                }

                is VIPExtractionResult.ChallengeDetected -> {
                    item {
                        VIPStatusBanner(
                            icon = Icons.Default.Security,
                            iconColor = Color(0xFF3B82F6),
                            title = "Access Challenge Detected (${result.challengeType})",
                            message = result.message,
                            testTag = "vip_challenge_banner",
                            colors = colors,
                            accentColor = accentColor
                        )
                    }
                }

                is VIPExtractionResult.NoFormatsFound -> {
                    item {
                        VIPStatusBanner(
                            icon = Icons.Default.Info,
                            iconColor = Color(0xFF94A3B8),
                            title = "No Formats Found",
                            message = result.reason,
                            testTag = "vip_no_formats_banner",
                            colors = colors,
                            accentColor = accentColor
                        )
                    }
                }

                is VIPExtractionResult.HttpError -> {
                    item {
                        VIPStatusBanner(
                            icon = Icons.Default.Error,
                            iconColor = Color(0xFFEF4444),
                            title = "HTTP Error ${result.code}",
                            message = result.message,
                            testTag = "vip_http_error_banner",
                            colors = colors,
                            accentColor = accentColor,
                            onRetry = { analyzeUrl(urlInput) }
                        )
                    }
                }

                is VIPExtractionResult.ParseError -> {
                    item {
                        VIPStatusBanner(
                            icon = Icons.Default.Error,
                            iconColor = Color(0xFFEF4444),
                            title = "Parsing Error",
                            message = result.message,
                            testTag = "vip_parse_error_banner",
                            colors = colors,
                            accentColor = accentColor,
                            onRetry = { analyzeUrl(urlInput) }
                        )
                    }
                }

                is VIPExtractionResult.Error -> {
                    item {
                        VIPStatusBanner(
                            icon = Icons.Default.Error,
                            iconColor = Color(0xFFEF4444),
                            title = "Extraction Error",
                            message = result.message,
                            testTag = "vip_generic_error_banner",
                            colors = colors,
                            accentColor = accentColor,
                            onRetry = { analyzeUrl(urlInput) }
                        )
                    }
                }

                null -> {
                    if (!isAnalyzing) {
                        item {
                            VIPGuideCard(colors = colors, accentColor = accentColor)
                        }
                    }
                }
            }
        }
    }

    // Real Phase 3 VIP Download and MediaStore progress dialog
    if (showDownloadDialog) {
        VIPDownloadProgressDialog(
            item = activeDownloadItem,
            publishResult = publishResult,
            isPublishing = isPublishing,
            colors = colors,
            accentColor = accentColor,
            onDismiss = { showDownloadDialog = false },
            onCancel = {
                activeTaskId?.let { taskId ->
                    vipDownloadEngine.cancelDownload(taskId)
                }
            },
            onRetry = {
                val metadata = (extractionResult as? VIPExtractionResult.Success)?.metadata
                val stream = selectedStream
                if (metadata != null && stream != null) {
                    startVipDownload(stream, metadata)
                }
            }
        )
    }
}

@Composable
private fun VIPDownloadProgressDialog(
    item: VIPDownloadItem?,
    publishResult: VIPPublishResult?,
    isPublishing: Boolean,
    colors: com.example.ui.theme.ExtendedThemeColors,
    accentColor: Color,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                publishResult is VIPPublishResult.Success -> accentColor.copy(alpha = 0.15f)
                                publishResult is VIPPublishResult.Failure || item?.status == VIPDownloadStatus.FAILED -> Color(0xFFEF4444).copy(alpha = 0.15f)
                                else -> accentColor.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            publishResult is VIPPublishResult.Success -> Icons.Default.CheckCircle
                            publishResult is VIPPublishResult.Failure || item?.status == VIPDownloadStatus.FAILED -> Icons.Default.Error
                            item?.status == VIPDownloadStatus.CANCELLED -> Icons.Default.Info
                            else -> Icons.Default.Download
                        },
                        contentDescription = null,
                        tint = when {
                            publishResult is VIPPublishResult.Success -> accentColor
                            publishResult is VIPPublishResult.Failure || item?.status == VIPDownloadStatus.FAILED -> Color(0xFFEF4444)
                            item?.status == VIPDownloadStatus.CANCELLED -> colors.textSecondary
                            else -> accentColor
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            publishResult is VIPPublishResult.Success -> "Saved to Gallery"
                            publishResult is VIPPublishResult.Failure -> "Publishing Failed"
                            isPublishing -> "Publishing to Gallery..."
                            item?.status == VIPDownloadStatus.FAILED -> "Download Failed"
                            item?.status == VIPDownloadStatus.CANCELLED -> "Download Cancelled"
                            item?.status == VIPDownloadStatus.DOWNLOADING -> "Downloading Media"
                            else -> "VIP Download"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = colors.textPrimary,
                        modifier = Modifier.testTag("vip_download_status_text")
                    )
                    Text(
                        text = item?.mediaTitle ?: "Media Download",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("vip_download_title")
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                if (item != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "${item.quality} • ${item.format}",
                                color = colors.textPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                when {
                    publishResult is VIPPublishResult.Success -> {
                        Text(
                            text = "Successfully saved to device MediaStore.",
                            color = colors.textPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceVariant)
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = publishResult.displayName,
                                    color = colors.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Size: ${VIPDownloadFormatting.formatBytes(publishResult.sizeBytes)}",
                                    color = colors.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    publishResult is VIPPublishResult.Failure -> {
                        Text(
                            text = publishResult.reason,
                            color = Color(0xFFEF4444),
                            fontSize = 13.sp,
                            modifier = Modifier.testTag("vip_download_error_text")
                        )
                    }

                    isPublishing -> {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .testTag("vip_download_progress_bar"),
                            color = accentColor,
                            trackColor = colors.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Moving media into public gallery storage...",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }

                    item?.status == VIPDownloadStatus.FAILED -> {
                        Text(
                            text = item.errorMessage ?: "An unexpected download error occurred.",
                            color = Color(0xFFEF4444),
                            fontSize = 13.sp,
                            modifier = Modifier.testTag("vip_download_error_text")
                        )
                    }

                    item?.status == VIPDownloadStatus.CANCELLED -> {
                        Text(
                            text = "Download was cancelled and temporary files were deleted.",
                            color = colors.textSecondary,
                            fontSize = 13.sp
                        )
                    }

                    else -> {
                        val progressValue = item?.progress ?: -1f
                        if (progressValue >= 0f) {
                            LinearProgressIndicator(
                                progress = { progressValue.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .testTag("vip_download_progress_bar"),
                                color = accentColor,
                                trackColor = colors.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .testTag("vip_download_progress_bar"),
                                color = accentColor,
                                trackColor = colors.surfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val bytesText = if (item != null && item.totalBytes > 0L) {
                                "${VIPDownloadFormatting.formatBytes(item.downloadedBytes)} / ${VIPDownloadFormatting.formatBytes(item.totalBytes)}"
                            } else if (item != null) {
                                VIPDownloadFormatting.formatBytes(item.downloadedBytes)
                            } else {
                                "0 B"
                            }

                            Text(
                                text = bytesText,
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.testTag("vip_download_bytes_text")
                            )

                            val speedText = if (item != null && item.speedBytesPerSec > 0L) {
                                VIPDownloadFormatting.formatSpeed(item.speedBytesPerSec)
                            } else if (item?.status == VIPDownloadStatus.DOWNLOADING) {
                                "Streaming..."
                            } else {
                                "Connecting..."
                            }

                            Text(
                                text = speedText,
                                color = accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("vip_download_speed_text")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                publishResult is VIPPublishResult.Success -> {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("vip_download_done_button")
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }

                publishResult is VIPPublishResult.Failure || item?.status == VIPDownloadStatus.FAILED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("vip_download_done_button")
                        ) {
                            Text("Dismiss", color = colors.textSecondary)
                        }
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("vip_download_retry_button")
                        ) {
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item?.status == VIPDownloadStatus.CANCELLED -> {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceVariant),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("vip_download_done_button")
                    ) {
                        Text("Close", color = colors.textPrimary)
                    }
                }

                isPublishing -> {
                    // Ongoing publishing
                }

                else -> {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444).copy(alpha = 0.15f),
                            contentColor = Color(0xFFEF4444)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("vip_download_cancel_button")
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        dismissButton = if (item?.isActive == true && !isPublishing) {
            {
                TextButton(onClick = onDismiss) {
                    Text("Background", color = colors.textSecondary)
                }
            }
        } else null,
        containerColor = colors.cardBackground,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        modifier = Modifier.testTag("vip_download_dialog")
    )
}

object VIPDownloadFormatting {
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mb)
        } else {
            val kb = bytesPerSec / 1024.0
            String.format(Locale.US, "%.0f KB/s", kb)
        }
    }
}

@Composable
private fun VIPMetadataCard(
    metadata: VIPMediaMetadata,
    accentColor: Color,
    colors: com.example.ui.theme.ExtendedThemeColors
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vip_metadata_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        border = BorderStroke(1.dp, colors.cardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(width = 110.dp, height = 75.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!metadata.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = metadata.thumbnailUrl,
                            contentDescription = "Thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = metadata.title,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "By ${metadata.author}",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (metadata.durationSeconds != null && metadata.durationSeconds > 0) {
                        val minutes = metadata.durationSeconds / 60
                        val seconds = metadata.durationSeconds % 60
                        Text(
                            text = String.format("%02d:%02d", minutes, seconds),
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VIPStreamFormatCard(
    stream: VIPMediaStream,
    isSelected: Boolean,
    accentColor: Color,
    colors: com.example.ui.theme.ExtendedThemeColors,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("vip_format_card_${stream.quality}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) accentColor.copy(alpha = 0.12f) else colors.cardBackground
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) accentColor else colors.cardBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) accentColor else colors.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = if (isSelected) Color.White else colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${stream.quality} Quality",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stream.format,
                            color = colors.textSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = if (stream.contentLength != null && stream.contentLength > 0) {
                        "Size: ${String.format("%.1f MB", stream.contentLength / (1024.0 * 1024.0))}"
                    } else {
                        "Direct MP4 stream"
                    },
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun VIPStatusBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    message: String,
    testTag: String,
    colors: com.example.ui.theme.ExtendedThemeColors,
    accentColor: Color,
    onRetry: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        border = BorderStroke(1.dp, iconColor.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = message,
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            if (onRetry != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun VIPGuideCard(
    colors: com.example.ui.theme.ExtendedThemeColors,
    accentColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vip_guide_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        border = BorderStroke(1.dp, colors.cardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Supported Media Engine",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Paste a supported public media link above to extract authentic direct stream formats, resolutions, and video metadata with zero simulated data.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}
