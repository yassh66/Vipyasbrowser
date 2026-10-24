package com.example.features.media

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.R
import com.example.core.media.MediaAnalyzer
import com.example.core.media.download.DownloadEngine
import com.example.core.media.download.DownloadItem
import com.example.core.media.download.DownloadStatus
import com.example.core.media.extraction.ExtractionResult
import com.example.core.media.extraction.MediaMetadata
import com.example.core.media.extraction.MediaStream
import com.example.core.media.extraction.Platform
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.InstagramGradientEnd
import com.example.ui.theme.InstagramGradientStart
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.YasPurple
import com.example.ui.theme.extendedColors
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDownloadScreen(
    initialUrl: String = "",
    onNavigateBack: () -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaAnalyzer = remember { MediaAnalyzer(context) }
    val downloadEngine = remember { DownloadEngine.getInstance(context) }
    val activeDownloads by downloadEngine.downloads.collectAsState()
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    var inputUrl by remember { mutableStateOf(initialUrl) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var extractionResult by remember { mutableStateOf<ExtractionResult?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Video, 1 = Audio
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var singleItemToDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var deleteFromStorageOption by remember { mutableStateOf(false) }

    fun runAnalyze(urlToAnalyze: String) {
        val trimmed = urlToAnalyze.trim()
        if (trimmed.isBlank() || trimmed == "about:blank" || trimmed.startsWith("yas://")) {
            return
        }
        scope.launch {
            isAnalyzing = true
            extractionResult = null
            try {
                val result = mediaAnalyzer.analyzeUrl(trimmed)
                extractionResult = result
            } catch (e: Exception) {
                extractionResult = ExtractionResult.Error("Analysis failed: ${e.message}")
            } finally {
                isAnalyzing = false
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank() && initialUrl != "about:blank" && !initialUrl.startsWith("yas://")) {
            inputUrl = initialUrl
            runAnalyze(initialUrl)
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(listOf(accentColor, YasPurple))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.media_extractor_title),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("media_back_button")
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
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // URL Input Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(listOf(colors.cardBorder, accentColor.copy(alpha = 0.35f)))
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (colors.isDark) 0.dp else 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Media URL",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            placeholder = { Text("Paste Instagram Reel, Post, or video link", color = colors.textMuted) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("url_input_field"),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = colors.inputBorder,
                                focusedContainerColor = colors.inputBackground,
                                unfocusedContainerColor = colors.inputBackground
                            ),
                            trailingIcon = {
                                Row {
                                    if (inputUrl.isNotBlank()) {
                                        IconButton(
                                            onClick = { inputUrl = "" },
                                            modifier = Modifier.testTag("clear_url_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear",
                                                tint = colors.textSecondary
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                                if (clip.isNotBlank()) {
                                                    inputUrl = clip
                                                    runAnalyze(clip)
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("paste_url_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentPaste,
                                            contentDescription = "Paste",
                                            tint = accentColor
                                        )
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { runAnalyze(inputUrl) },
                            enabled = inputUrl.isNotBlank() && !isAnalyzing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("analyze_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = Color.White,
                                disabledContainerColor = colors.surfaceVariant,
                                disabledContentColor = colors.textMuted
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isAnalyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.analyzing), fontWeight = FontWeight.Bold, color = Color.White)
                            } else {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.analyze_url), fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }

            // Analysis Result or Error State
            item {
                when (val result = extractionResult) {
                    is ExtractionResult.Success -> {
                        val metadata = result.metadata
                        val streamMatrix = mediaAnalyzer.buildStreamMatrix(result.streams)

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = Brush.linearGradient(listOf(colors.cardBorder, accentColor.copy(alpha = 0.4f)))
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = if (colors.isDark) 0.dp else 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Media Header: Platform badge, Title, Author
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (metadata.thumbnailUrl != null) {
                                        AsyncImage(
                                            model = metadata.thumbnailUrl,
                                            contentDescription = "Media Thumbnail",
                                            modifier = Modifier
                                                .size(80.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.width(14.dp))
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        PlatformBadge(platform = metadata.platform)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = metadata.title,
                                            color = colors.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = metadata.author,
                                            color = colors.textSecondary,
                                            fontSize = 13.sp
                                        )
                                        if (metadata.isCarousel) {
                                            Text(
                                                text = "Carousel (${metadata.carouselCount} items)",
                                                color = accentColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Tabs: Video & Audio
                                TabRow(
                                    selectedTabIndex = selectedTab,
                                    containerColor = colors.surfaceVariant,
                                    contentColor = accentColor,
                                    indicator = { tabPositions ->
                                        TabRowDefaults.SecondaryIndicator(
                                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                            color = accentColor
                                        )
                                    }
                                ) {
                                    Tab(
                                        selected = selectedTab == 0,
                                        onClick = { selectedTab = 0 },
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("${stringResource(R.string.format_video_tab)} (${streamMatrix.videoStreams.size})")
                                            }
                                        },
                                        selectedContentColor = accentColor,
                                        unselectedContentColor = colors.textSecondary
                                    )
                                    Tab(
                                        selected = selectedTab == 1,
                                        onClick = { selectedTab = 1 },
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("${stringResource(R.string.format_audio_tab)} (${streamMatrix.adaptiveAudioStreams.size})")
                                            }
                                        },
                                        selectedContentColor = accentColor,
                                        unselectedContentColor = colors.textSecondary
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                val streamsToShow = if (selectedTab == 0) streamMatrix.videoStreams else streamMatrix.adaptiveAudioStreams
                                if (streamsToShow.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 20.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No formats available for this tab", color = colors.textMuted)
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        streamsToShow.forEach { stream ->
                                            StreamItemRow(
                                                stream = stream,
                                                onDownloadClick = {
                                                    val downloadId = downloadEngine.startDownload(metadata, stream)
                                                    Toast.makeText(context, "Download initiated: ${stream.quality}", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is ExtractionResult.NoFormatsFound -> {
                        ErrorCard(
                            title = "No Formats Found",
                            message = result.reason,
                            isWarning = true
                        )
                    }

                    is ExtractionResult.LoginRequired -> {
                        ErrorCard(
                            title = "Authentication Required",
                            message = result.message,
                            actionLabel = "Open in Browser",
                            onAction = onNavigateBack
                        )
                    }

                    is ExtractionResult.Error -> {
                        ErrorCard(
                            title = "Extraction Error",
                            message = result.message
                        )
                    }

                    null -> {}
                }
            }

            // Active Downloads Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.active_downloads),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(${activeDownloads.size})",
                            color = colors.textSecondary,
                            fontSize = 13.sp
                        )
                    }

                    if (activeDownloads.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isMultiSelectMode) {
                                val selectedCount = activeDownloads.count { it.isSelected }
                                val allSelected = activeDownloads.isNotEmpty() && activeDownloads.all { it.isSelected }

                                TextButton(
                                    onClick = { downloadEngine.selectAll(!allSelected) },
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text(
                                        text = if (allSelected) "Deselect" else "Select All",
                                        color = accentColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                if (selectedCount > 0) {
                                    IconButton(
                                        onClick = { showBulkDeleteDialog = true },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Selected",
                                            tint = ErrorRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                TextButton(
                                    onClick = {
                                        isMultiSelectMode = false
                                        downloadEngine.selectAll(false)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("Done", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                TextButton(
                                    onClick = { isMultiSelectMode = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Manage", color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            if (activeDownloads.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.cardBackground)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.no_downloads),
                                color = colors.textMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                items(activeDownloads, key = { it.id }) { item ->
                    DownloadItemCard(
                        item = item,
                        isMultiSelectMode = isMultiSelectMode,
                        onToggleSelect = { downloadEngine.toggleItemSelection(item.id) },
                        onCancelClick = { downloadEngine.removeDownload(item.id) },
                        onResumeClick = { downloadEngine.resumeDownload(item.id) },
                        onOpenClick = {
                            val file = File(item.destinationPath)
                            if (file.exists()) {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, if (item.isAudio) "audio/*" else "video/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "File does not exist in local cache path", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDeleteClick = {
                            singleItemToDelete = item
                        }
                    )
                }
            }
        }

        // Single Download Deletion Dialog
        if (singleItemToDelete != null) {
            val target = singleItemToDelete!!
            AlertDialog(
                onDismissRequest = { singleItemToDelete = null },
                title = {
                    Text("Delete Download Record?", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text(
                            text = "\"${target.title}\"",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { deleteFromStorageOption = !deleteFromStorageOption }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = deleteFromStorageOption,
                                onCheckedChange = { deleteFromStorageOption = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Also delete file from device storage",
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (deleteFromStorageOption) "Permanently removes file from Gallery/disk" else "Keeps media file, removes record from list only",
                                    color = colors.textMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val result = downloadEngine.deleteDownload(
                                downloadId = target.id,
                                deleteFileFromStorage = deleteFromStorageOption,
                                protectIfActive = false
                            )
                            if (!result.success) {
                                Toast.makeText(context, result.errorMessage ?: "Failed to delete from storage", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    if (deleteFromStorageOption) "File deleted from storage" else "Removed from list",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            singleItemToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (deleteFromStorageOption) ErrorRed else accentColor)
                    ) {
                        Text(if (deleteFromStorageOption) "Delete File" else "Remove", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { singleItemToDelete = null }) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                },
                containerColor = colors.cardBackground
            )
        }

        // Bulk Deletion Dialog
        if (showBulkDeleteDialog) {
            val selected = activeDownloads.filter { it.isSelected }
            val selectedNonActive = selected.filterNot { it.status == DownloadStatus.DOWNLOADING }
            val activeProtectedCount = selected.size - selectedNonActive.size

            AlertDialog(
                onDismissRequest = { showBulkDeleteDialog = false },
                title = {
                    Text("Delete Selected Downloads?", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        if (activeProtectedCount > 0) {
                            Text(
                                text = "Notice: $activeProtectedCount active in-progress download(s) are protected and will not be deleted.",
                                color = accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        Text(
                            text = "${selectedNonActive.size} download(s) selected for deletion.",
                            color = colors.textSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { deleteFromStorageOption = !deleteFromStorageOption }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = deleteFromStorageOption,
                                onCheckedChange = { deleteFromStorageOption = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Also delete files from device storage",
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (deleteFromStorageOption) "Permanently removes files from Gallery and storage" else "Leaves files on device, only removes records",
                                    color = colors.textMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val result = downloadEngine.deleteSelectedDownloads(
                                selectedIds = selectedNonActive.map { it.id }.toSet(),
                                deleteFilesFromStorage = deleteFromStorageOption
                            )
                            Toast.makeText(
                                context,
                                "Deleted ${result.deletedCount} items" + if (result.protectedActiveCount > 0) " (${result.protectedActiveCount} active protected)" else "",
                                Toast.LENGTH_SHORT
                            ).show()
                            showBulkDeleteDialog = false
                            isMultiSelectMode = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (deleteFromStorageOption) ErrorRed else accentColor)
                    ) {
                        Text(if (deleteFromStorageOption) "Delete from Storage" else "Remove Records", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBulkDeleteDialog = false }) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                },
                containerColor = colors.cardBackground
            )
        }
    }
}

@Composable
fun PlatformBadge(platform: Platform) {
    val gradient = when (platform) {
        Platform.INSTAGRAM -> Brush.linearGradient(listOf(InstagramGradientStart, InstagramGradientEnd))
        Platform.YOUTUBE -> Brush.linearGradient(listOf(Color(0xFFFF0000), Color(0xFFCC0000)))
        Platform.TIKTOK -> Brush.linearGradient(listOf(Color(0xFF00F2FE), Color(0xFF4FACFE)))
        Platform.TWITTER -> Brush.linearGradient(listOf(Color(0xFF1DA1F2), Color(0xFF0D8ECF)))
        Platform.DIRECT -> Brush.linearGradient(listOf(Color(0xFF00E5FF), YasPurple))
        Platform.UNKNOWN -> Brush.linearGradient(listOf(Color(0xFF94A3B8), Color(0xFF64748B)))
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(gradient)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = platform.displayName,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
fun StreamItemRow(
    stream: MediaStream,
    onDownloadClick: () -> Unit
) {
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        stream.isVideo -> Icons.Default.Videocam
                        stream.isAudio -> Icons.Default.Audiotrack
                        else -> Icons.Default.Image
                    },
                    contentDescription = null,
                    tint = if (stream.isVideo || !stream.isAudio) accentColor else YasPurple,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stream.quality,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = stream.format,
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Button(
                onClick = onDownloadClick,
                modifier = Modifier
                    .height(36.dp)
                    .testTag("download_stream_button_${stream.id}"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (stream.isVideo) accentColor else YasPurple,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Get", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
fun DownloadItemCard(
    item: DownloadItem,
    isMultiSelectMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onCancelClick: () -> Unit,
    onResumeClick: () -> Unit,
    onOpenClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors
    val isActive = item.status == DownloadStatus.DOWNLOADING

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isMultiSelectMode && !isActive) {
                onToggleSelect()
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                listOf(
                    if (item.isSelected && isMultiSelectMode) accentColor else colors.cardBorder,
                    (if (item.isSelected && isMultiSelectMode) accentColor else colors.cardBorder).copy(alpha = 0.5f)
                )
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (colors.isDark) 0.dp else 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = item.isSelected,
                        onCheckedChange = { onToggleSelect() },
                        enabled = !isActive,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.quality} • ${item.format}" + (if (isActive && isMultiSelectMode) " • (Active)" else ""),
                        color = if (isActive && isMultiSelectMode) accentColor else colors.textSecondary,
                        fontSize = 12.sp
                    )
                }

                if (!isMultiSelectMode) {
                    when (item.status) {
                        DownloadStatus.COMPLETED -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onOpenClick,
                                    modifier = Modifier.size(36.dp).testTag("open_download_file")
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Open", tint = SuccessGreen)
                                }
                                IconButton(
                                    onClick = onDeleteClick,
                                    modifier = Modifier.size(36.dp).testTag("delete_download_file")
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = colors.textSecondary)
                                }
                            }
                        }
                        DownloadStatus.DOWNLOADING -> {
                            IconButton(
                                onClick = onCancelClick,
                                modifier = Modifier.size(36.dp).testTag("cancel_download_file")
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Cancel", tint = ErrorRed)
                            }
                        }
                        DownloadStatus.FAILED, DownloadStatus.PAUSED -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onResumeClick,
                                    modifier = Modifier.size(36.dp).testTag("resume_download_file")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = accentColor)
                                }
                                IconButton(
                                    onClick = onDeleteClick,
                                    modifier = Modifier.size(36.dp).testTag("delete_download_file")
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = colors.textSecondary)
                                }
                            }
                        }
                        else -> {
                            IconButton(
                                onClick = onDeleteClick,
                                modifier = Modifier.size(36.dp).testTag("delete_download_file")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = colors.textSecondary)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (item.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = accentColor,
                    trackColor = colors.surfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val downloadedMb = item.downloadedBytes / (1024 * 1024.0)
                    val totalMb = item.totalBytes / (1024 * 1024.0)
                    val speedKb = item.speedBytesPerSec / 1024.0

                    Text(
                        text = if (item.totalBytes > 0) {
                            String.format(java.util.Locale.US, "%.1f / %.1f MB (%.0f%%)", downloadedMb, totalMb, item.progress * 100)
                        } else {
                            String.format(java.util.Locale.US, "%.1f MB", downloadedMb)
                        },
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = String.format(java.util.Locale.US, "%.0f KB/s", speedKb),
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (item.status == DownloadStatus.COMPLETED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Saved to Downloads", color = SuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            } else if (item.status == DownloadStatus.FAILED) {
                Text(
                    text = item.errorMessage ?: "Download failed",
                    color = ErrorRed,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun ErrorCard(
    title: String,
    message: String,
    isWarning: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                listOf(if (isWarning) Color(0xFFF59E0B) else ErrorRed, colors.cardBorder)
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isWarning) Icons.Default.Info else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (isWarning) Color(0xFFF59E0B) else ErrorRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(actionLabel, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}
