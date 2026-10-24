package com.example.core.media.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.core.media.extraction.ExtractionResult
import com.example.core.media.extraction.MediaMetadata
import com.example.core.media.extraction.MediaStream
import com.example.core.media.extraction.YouTubeMediaExtractor
import com.example.core.settings.SettingsRepository
import com.example.core.settings.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class BulkDeleteResult(
    val deletedCount: Int,
    val protectedActiveCount: Int,
    val failedCount: Int = 0
)

data class SingleDeleteResult(
    val success: Boolean,
    val wasActiveProtected: Boolean = false,
    val errorMessage: String? = null
)

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String,
    val url: String,
    val format: String,
    val quality: String,
    val destinationPath: String,
    val contentUriString: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val isAudio: Boolean = false,
    val customHeaders: Map<String, String> = emptyMap(),
    val isSelected: Boolean = false,
    val sourceUrl: String? = null,
    val requiresAudioMerge: Boolean = false,
    val audioUrl: String? = null,
    val audioHeaders: Map<String, String> = emptyMap()
)

class DownloadEngine(
    private val context: Context,
    private val client: OkHttpClient = defaultHttpClient()
) {
    companion object {
        @Volatile
        private var INSTANCE: DownloadEngine? = null

        fun getInstance(context: Context): DownloadEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val settingsRepository = SettingsRepository.getInstance(context)

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    init {
        loadPersistedDownloads()
    }

    private fun loadPersistedDownloads() {
        try {
            val file = File(context.filesDir, "yas_downloads_history.json")
            if (file.exists()) {
                val jsonStr = file.readText()
                val array = JSONArray(jsonStr)
                val list = mutableListOf<DownloadItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val statusStr = obj.optString("status", "COMPLETED")
                    val status = if (statusStr == DownloadStatus.DOWNLOADING.name) {
                        DownloadStatus.PAUSED
                    } else {
                        try {
                            DownloadStatus.valueOf(statusStr)
                        } catch (e: Exception) {
                            DownloadStatus.COMPLETED
                        }
                    }
                    list.add(
                        DownloadItem(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            author = obj.optString("author", "Unknown Creator"),
                            url = obj.getString("url"),
                            format = obj.getString("format"),
                            quality = obj.getString("quality"),
                            destinationPath = obj.getString("destinationPath"),
                            contentUriString = obj.optString("contentUriString").takeIf { it.isNotBlank() },
                            totalBytes = obj.optLong("totalBytes", 0L),
                            downloadedBytes = obj.optLong("downloadedBytes", 0L),
                            progress = obj.optDouble("progress", 1.0).toFloat(),
                            speedBytesPerSec = 0L,
                            status = status,
                            errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() },
                            isAudio = obj.optBoolean("isAudio", false),
                            sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotBlank() },
                            requiresAudioMerge = obj.optBoolean("requiresAudioMerge", false),
                            audioUrl = obj.optString("audioUrl").takeIf { it.isNotBlank() }
                        )
                    )
                }
                _downloads.value = list
            }
        } catch (e: Exception) {
            // Ignore parse errors on corrupted history
        }
    }

    fun persistDownloads() {
        try {
            val file = File(context.filesDir, "yas_downloads_history.json")
            val array = JSONArray()
            _downloads.value.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("author", item.author)
                    put("url", item.url)
                    put("format", item.format)
                    put("quality", item.quality)
                    put("destinationPath", item.destinationPath)
                    put("contentUriString", item.contentUriString ?: "")
                    put("totalBytes", item.totalBytes)
                    put("downloadedBytes", item.downloadedBytes)
                    put("progress", item.progress.toDouble())
                    put("status", item.status.name)
                    put("errorMessage", item.errorMessage ?: "")
                    put("isAudio", item.isAudio)
                    put("sourceUrl", item.sourceUrl ?: "")
                    put("requiresAudioMerge", item.requiresAudioMerge)
                    put("audioUrl", item.audioUrl ?: "")
                }
                array.put(obj)
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            // Ignore disk write errors
        }
    }

    /**
     * Checks if the active network is Wi-Fi, Ethernet, or unmetered.
     * Note: Only consulted when the user explicitly turns ON "Wi-Fi Only" in Settings.
     */
    fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Checks if internet is reachable over any interface (cellular, Wi-Fi, VPN).
     */
    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun checkNetworkRestriction(settings: UserSettings): String? {
        if (!settings.wifiOnlyDownloads) {
            // Wi-Fi Only is OFF (Default): Cellular and all default networks allowed
            return null
        }
        if (!isWifiConnected()) {
            return "Blocked: 'Download on Wi-Fi Only' is enabled in Settings, but device is on cellular data."
        }
        return null
    }

    fun startDownload(metadata: MediaMetadata, stream: MediaStream): String {
        val downloadId = UUID.randomUUID().toString()
        val currentSettings = settingsRepository.settings.value

        val isAudioOnly = stream.isAudioOnly || stream.format.equals("mp3", ignoreCase = true)
        val isImage = stream.format.equals("jpg", ignoreCase = true) ||
                      stream.format.equals("jpeg", ignoreCase = true) ||
                      stream.format.equals("png", ignoreCase = true) ||
                      stream.format.equals("webp", ignoreCase = true)

        val extension = when {
            isAudioOnly -> "mp3"
            isImage -> stream.format.lowercase()
            else -> stream.format.lowercase().replace("original", "mp4")
        }
        val cleanTitle = metadata.title.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(40).ifBlank { "media" }
        val qualityTag = if (isAudioOnly) {
            currentSettings.audioBitrate.replace(" ", "")
        } else {
            stream.quality.replace(" ", "_").replace("(", "").replace(")", "").take(20)
        }
        val fileName = "YAS_${cleanTitle}_${qualityTag}.${extension}"

        // Display path in public storage: Pictures/YAS Browser, Movies/YAS Browser or Music/YAS Browser
        val relativeSubDir = when {
            isImage -> "Pictures/YAS Browser"
            isAudioOnly -> "Music/YAS Browser"
            else -> "Movies/YAS Browser"
        }
        val expectedDisplayPath = "$relativeSubDir/$fileName"

        val item = DownloadItem(
            id = downloadId,
            title = metadata.title,
            author = metadata.author,
            url = stream.url,
            format = stream.format,
            quality = if (isAudioOnly) "${currentSettings.audioBitrate} (MP3)" else stream.quality,
            destinationPath = expectedDisplayPath,
            totalBytes = stream.contentLength ?: 0L,
            status = DownloadStatus.DOWNLOADING,
            isAudio = isAudioOnly,
            customHeaders = stream.headers,
            sourceUrl = metadata.sourceUrl,
            requiresAudioMerge = stream.requiresAudioMerge,
            audioUrl = stream.audioUrl,
            audioHeaders = stream.audioHeaders
        )

        _downloads.update { listOf(item) + it }
        persistDownloads()

        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            executeDownload(item, fileName, isAudioOnly)
        }
        activeJobs[downloadId] = job
        job.start()

        return downloadId
    }

    fun resumeDownload(downloadId: String) {
        val item = _downloads.value.firstOrNull { it.id == downloadId } ?: return
        if (activeJobs.containsKey(downloadId)) return

        val fileName = item.destinationPath.substringAfterLast("/")
        val updated = item.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null)
        updateItem(downloadId) { updated }

        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            executeDownload(updated, fileName, item.isAudio)
        }
        activeJobs[downloadId] = job
        job.start()
    }

    private suspend fun executeDownload(item: DownloadItem, fileName: String, isAudio: Boolean) {
        val currentSettings = settingsRepository.settings.value

        val restriction = checkNetworkRestriction(currentSettings)
        if (restriction != null) {
            updateItem(item.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = restriction)
            }
            activeJobs.remove(item.id)
            return
        }

        val tempFile = File(context.cacheDir, "yas_dl_${item.id}.tmp")
        var downloadSuccess = false

        if (item.requiresAudioMerge && !item.audioUrl.isNullOrBlank()) {
            val tempVideo = File(context.cacheDir, "yas_dl_${item.id}_video.tmp")
            val tempAudio = File(context.cacheDir, "yas_dl_${item.id}_audio.tmp")

            val videoSuccess = downloadUrlToFile(item, item.url, item.customHeaders, tempVideo, 0.0f, 0.70f)
            val audioSuccess = if (videoSuccess && activeJobs.containsKey(item.id)) {
                downloadUrlToFile(item, item.audioUrl, item.audioHeaders, tempAudio, 0.70f, 0.90f)
            } else false

            if (videoSuccess && audioSuccess && activeJobs.containsKey(item.id)) {
                updateItem(item.id) { it.copy(progress = 0.92f) }
                val muxed = AdaptiveMediaMuxer().mux(tempVideo, tempAudio, tempFile)
                if (muxed && tempFile.exists() && tempFile.length() > 0L) {
                    downloadSuccess = true
                } else if (tempVideo.exists() && tempVideo.length() > 0L) {
                    tempVideo.copyTo(tempFile, overwrite = true)
                    downloadSuccess = true
                }
            }
            tempVideo.delete()
            tempAudio.delete()
        } else {
            downloadSuccess = downloadUrlToFile(item, item.url, item.customHeaders, tempFile, 0.0f, 1.0f)
        }

        // Handle MP3 transcoding & ID3 tagging if genuine MP3 requested
        if (downloadSuccess && isAudio && item.format.equals("MP3", ignoreCase = true) && activeJobs.containsKey(item.id)) {
            val targetBitrate = currentSettings.audioBitrate.filter { it.isDigit() }.toIntOrNull() ?: 320
            val transFile = File(context.cacheDir, "yas_dl_${item.id}_trans.mp3")
            val transResult = AudioTranscoder.transcodeToMp3(tempFile, transFile, targetBitrate)
            if (transResult.success && transResult.outputFile != null && transResult.outputFile.exists()) {
                transFile.copyTo(tempFile, overwrite = true)
                transFile.delete()

                try {
                    val id3Tag = MediaMuxerHelper.createId3v2Tag(
                        title = item.title,
                        artist = item.author,
                        album = "YAS Browser Downloads"
                    )
                    val originalBytes = tempFile.readBytes()
                    FileOutputStream(tempFile).use { out ->
                        out.write(id3Tag)
                        out.write(originalBytes)
                    }
                } catch (_: Exception) {}
            } else {
                // If device lacks an MP3 encoder, NEVER rename M4A/AAC to .mp3!
                // Preserve genuine M4A audio with correct extension and report honestly.
                val m4aFileName = fileName.replace(".mp3", ".m4a")
                val publishedUri = publishToMediaStore(tempFile, m4aFileName, true)
                tempFile.delete()

                if (publishedUri != null) {
                    updateItem(item.id) {
                        it.copy(
                            downloadedBytes = it.totalBytes,
                            progress = 1.0f,
                            speedBytesPerSec = 0L,
                            status = DownloadStatus.COMPLETED,
                            format = "M4A",
                            contentUriString = publishedUri.toString(),
                            destinationPath = "Music/YAS Browser/$m4aFileName",
                            errorMessage = "MP3 encoder unavailable on this device. Saved as original high-quality M4A."
                        )
                    }
                } else {
                    updateItem(item.id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = transResult.errorMessage ?: "MP3 transcoding failed"
                        )
                    }
                }
                activeJobs.remove(item.id)
                return
            }
        }

        if (downloadSuccess && activeJobs.containsKey(item.id)) {
            // Verify output file before publishing
            if (!tempFile.exists() || tempFile.length() < 1024L) {
                updateItem(item.id) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = "Downloaded media file is empty or incomplete"
                    )
                }
                tempFile.delete()
                activeJobs.remove(item.id)
                return
            }

            val publishedUri = publishToMediaStore(tempFile, fileName, isAudio)
            tempFile.delete()

            if (publishedUri != null) {
                updateItem(item.id) {
                    it.copy(
                        downloadedBytes = it.totalBytes,
                        progress = 1.0f,
                        speedBytesPerSec = 0L,
                        status = DownloadStatus.COMPLETED,
                        contentUriString = publishedUri.toString(),
                        destinationPath = when {
                            isAudio -> "Music/YAS Browser/$fileName"
                            fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) || fileName.endsWith(".jpeg", true) -> "Pictures/YAS Browser/$fileName"
                            else -> "Movies/YAS Browser/$fileName"
                        }
                    )
                }
            } else {
                updateItem(item.id) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = "Failed to publish file to device storage/gallery."
                    )
                }
            }
        } else if (!downloadSuccess && activeJobs.containsKey(item.id)) {
            // Ensure status is never stuck at DOWNLOADING 0% on failure
            updateItem(item.id) {
                if (it.status == DownloadStatus.DOWNLOADING) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = it.errorMessage ?: "Download failed or connection timed out"
                    )
                } else it
            }
            tempFile.delete()
        }

        activeJobs.remove(item.id)
    }

    private suspend fun downloadUrlToFile(
        item: DownloadItem,
        initialUrl: String,
        headers: Map<String, String>,
        targetFile: File,
        progressBase: Float,
        progressSpan: Float
    ): Boolean {
        val currentSettings = settingsRepository.settings.value
        var currentUrl = initialUrl
        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts && activeJobs.containsKey(item.id)) {
            attempt++
            try {
                val existingBytes = if (targetFile.exists()) targetFile.length() else 0L
                val requestBuilder = Request.Builder().url(currentUrl)

                val customUa = headers["User-Agent"]
                if (!customUa.isNullOrBlank()) {
                    requestBuilder.header("User-Agent", customUa)
                } else {
                    requestBuilder.header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                }

                val isYouTubeMedia = currentUrl.contains("googlevideo.com") || currentUrl.contains("youtube.com")
                if (currentUrl.contains("instagram") || currentUrl.contains("cdninstagram") || currentUrl.contains("fbcdn")) {
                    requestBuilder.header("Referer", "https://www.instagram.com/")
                    requestBuilder.header("Origin", "https://www.instagram.com")
                }

                if (currentSettings.doNotTrack) {
                    requestBuilder.header("DNT", "1")
                }

                // For YouTube CDN (googlevideo.com), do not send cross-origin Referer/Origin/Cookie
                // headers which cause CDN rejects and throttles. Send only User-Agent and Range.
                if (!isYouTubeMedia) {
                    headers.forEach { (key, value) ->
                        if (!key.equals("User-Agent", ignoreCase = true)) {
                            requestBuilder.header(key, value)
                        }
                    }
                }

                // Range header: For YouTube streams, sending Range ensures HTTP 206 Partial Content
                // without CDN playback rate-limiting or stalling.
                val rangeHeader = if (existingBytes > 0L) "bytes=$existingBytes-" else if (isYouTubeMedia) "bytes=0-" else null
                if (rangeHeader != null) {
                    requestBuilder.header("Range", rangeHeader)
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val isPartial = response.code == 206
                val isOk = response.isSuccessful

                if (!isOk) {
                    if (response.code == 416 && existingBytes > 0) {
                        response.close()
                        targetFile.delete()
                        continue
                    }

                    // Auto-refresh on 403 Forbidden for YouTube streams
                    if (response.code == 403 && isYouTubeMedia) {
                        response.close()
                        val src = item.sourceUrl
                        if (!src.isNullOrBlank()) {
                            try {
                                val refreshResult = YouTubeMediaExtractor().extract(src)
                                if (refreshResult is ExtractionResult.Success) {
                                    val matching = refreshResult.streams.firstOrNull { it.quality == item.quality }
                                        ?: refreshResult.streams.firstOrNull { it.isVideo == !item.isAudio }
                                    if (matching != null && matching.url != currentUrl) {
                                        currentUrl = matching.url
                                        continue
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    val code = response.code
                    response.close()
                    updateItem(item.id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = "Server returned HTTP $code"
                        )
                    }
                    return false
                }

                val body = response.body ?: run {
                    updateItem(item.id) {
                        it.copy(status = DownloadStatus.FAILED, errorMessage = "Empty response body from media server")
                    }
                    return false
                }

                val rawContentLength = body.contentLength()
                val totalBytes = if (rawContentLength > 0L) {
                    if (isPartial) existingBytes + rawContentLength else rawContentLength
                } else if (item.totalBytes > 0L) {
                    item.totalBytes
                } else 0L
                updateItem(item.id) { it.copy(totalBytes = totalBytes, status = DownloadStatus.DOWNLOADING) }

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile, isPartial)

                val bufferSize = if (currentSettings.fastChunking) 64 * 1024 else 8192
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                var totalDownloaded = if (isPartial) existingBytes else 0L
                var lastTime = System.currentTimeMillis()
                var lastDownloaded = totalDownloaded

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!activeJobs.containsKey(item.id)) {
                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()
                        return false
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    totalDownloaded += bytesRead

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastTime >= 500) {
                        val timeDiffSec = (currentTime - lastTime) / 1000.0
                        val speed = if (timeDiffSec > 0) ((totalDownloaded - lastDownloaded) / timeDiffSec).toLong() else 0L
                        val streamRatio = if (totalBytes > 0) (totalDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                        val overallProgress = (progressBase + streamRatio * progressSpan).coerceIn(0f, 1f)

                        updateItem(item.id) {
                            it.copy(
                                downloadedBytes = totalDownloaded,
                                progress = overallProgress,
                                speedBytesPerSec = speed
                            )
                        }

                        lastTime = currentTime
                        lastDownloaded = totalDownloaded
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                if (targetFile.exists() && targetFile.length() > 0L) {
                    return true
                }
            } catch (e: Exception) {
                if (attempt < maxAttempts && activeJobs.containsKey(item.id)) {
                    val recheck = settingsRepository.settings.value
                    if (recheck.wifiOnlyDownloads && !isWifiConnected()) {
                        updateItem(item.id) {
                            it.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = "Download paused: Switched to cellular data and 'Download on Wi-Fi Only' is enabled."
                            )
                        }
                        return false
                    }
                    delay(1200)
                } else {
                    updateItem(item.id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = e.message ?: "Download connection interrupted"
                        )
                    }
                    return false
                }
            }
        }

        return targetFile.exists() && targetFile.length() > 0L
    }

    /**
     * Publishes a completed video or audio download into Android MediaStore so it is
     * immediately discoverable in the user's Gallery, Photos, and Media Player apps.
     */
    private fun publishToMediaStore(sourceFile: File, fileName: String, isAudio: Boolean): Uri? {
        val resolver = context.contentResolver
        val isImage = fileName.endsWith(".jpg", ignoreCase = true) ||
                      fileName.endsWith(".jpeg", ignoreCase = true) ||
                      fileName.endsWith(".png", ignoreCase = true) ||
                      fileName.endsWith(".webp", ignoreCase = true)

        val mimeType = when {
            isAudio -> if (fileName.endsWith(".m4a", ignoreCase = true)) "audio/mp4" else "audio/mpeg"
            isImage -> if (fileName.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
            else -> "video/mp4"
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentUri = when {
                isAudio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val relativePath = when {
                isAudio -> "Music/YAS Browser"
                isImage -> "Pictures/YAS Browser"
                else -> "Movies/YAS Browser"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = resolver.insert(contentUri, values) ?: return null

            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }

                // Finalize entry in MediaStore
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                itemUri
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                null
            }
        } else {
            // Legacy storage for API <= 28
            try {
                val baseDir = when {
                    isAudio -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    isImage -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                }
                val destDir = File(baseDir, "YAS Browser").apply { mkdirs() }
                val destFile = File(destDir, fileName)

                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { out ->
                        input.copyTo(out)
                    }
                }

                // Trigger media scanner so it shows in Gallery
                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
                Uri.fromFile(destFile)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Deletes a single download record. If deleteFileFromStorage is true,
     * deletes the actual MediaStore file and disk entry safely.
     * Protects active downloads if protectIfActive is true.
     */
    fun deleteDownload(
        downloadId: String,
        deleteFileFromStorage: Boolean,
        protectIfActive: Boolean = false
    ): SingleDeleteResult {
        val item = _downloads.value.firstOrNull { it.id == downloadId }
            ?: return SingleDeleteResult(success = false, errorMessage = "Download record not found")

        val isActive = item.status == DownloadStatus.DOWNLOADING || activeJobs.containsKey(downloadId)
        if (isActive && protectIfActive) {
            return SingleDeleteResult(success = false, wasActiveProtected = true, errorMessage = "Active download protected")
        }

        // Cancel active job if not protected
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)

        var storageDeletionFailed = false
        var storageErrorMessage: String? = null

        if (deleteFileFromStorage) {
            // 1. Delete MediaStore URI if present
            item.contentUriString?.let { uriStr ->
                try {
                    context.contentResolver.delete(Uri.parse(uriStr), null, null)
                } catch (e: Exception) {
                    storageDeletionFailed = true
                    storageErrorMessage = "Failed to remove from MediaStore: ${e.message}"
                }
            }

            // 2. Delete legacy or direct file if present
            try {
                val directFile = File(item.destinationPath)
                if (directFile.exists()) {
                    val deleted = directFile.delete()
                    if (!deleted && directFile.exists()) {
                        storageDeletionFailed = true
                        storageErrorMessage = "File could not be deleted from storage"
                    }
                } else {
                    // Try resolving relative path in Movies or Music
                    val baseDir = if (item.isAudio) {
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    } else {
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    }
                    val publicFile = File(File(baseDir, "YAS Browser"), item.destinationPath.substringAfterLast("/"))
                    if (publicFile.exists()) {
                        val deleted = publicFile.delete()
                        if (!deleted && publicFile.exists()) {
                            storageDeletionFailed = true
                            storageErrorMessage = "File could not be deleted from device directory"
                        }
                    }
                }
            } catch (e: Exception) {
                storageDeletionFailed = true
                storageErrorMessage = "Storage deletion error: ${e.message}"
            }

            // 3. Delete any temporary cache file
            try {
                val tempFile = File(context.cacheDir, "yas_dl_${item.id}.tmp")
                if (tempFile.exists()) tempFile.delete()
            } catch (e: Exception) {
                // Ignore
            }
        }

        if (deleteFileFromStorage && storageDeletionFailed) {
            return SingleDeleteResult(
                success = false,
                errorMessage = storageErrorMessage ?: "Could not delete file from device storage"
            )
        }

        // Remove from memory state and persist
        _downloads.update { list -> list.filterNot { it.id == downloadId } }
        persistDownloads()
        return SingleDeleteResult(success = true)
    }

    /**
     * Overload returning Boolean for simple callers
     */
    fun deleteDownload(downloadId: String, deleteFileFromStorage: Boolean): Boolean {
        return deleteDownload(downloadId, deleteFileFromStorage, protectIfActive = false).success
    }

    /**
     * Clears all download records.
     * Protects active in-progress downloads from accidental bulk deletion.
     */
    fun clearAllDownloads(deleteFilesFromStorage: Boolean): BulkDeleteResult {
        val nonActiveIds = _downloads.value
            .filterNot { it.status == DownloadStatus.DOWNLOADING || activeJobs.containsKey(it.id) }
            .map { it.id }
            .toSet()
        return deleteSelectedDownloads(nonActiveIds, deleteFilesFromStorage)
    }

    /**
     * Deletes multiple selected downloads safely.
     * Protects active downloads from accidental deletion.
     */
    fun deleteSelectedDownloads(selectedIds: Set<String>, deleteFilesFromStorage: Boolean): BulkDeleteResult {
        var deletedCount = 0
        var protectedCount = 0
        var failedCount = 0

        selectedIds.forEach { id ->
            val result = deleteDownload(
                downloadId = id,
                deleteFileFromStorage = deleteFilesFromStorage,
                protectIfActive = true // ALWAYS protect active downloads in bulk mode
            )
            when {
                result.success -> deletedCount++
                result.wasActiveProtected -> protectedCount++
                else -> failedCount++
            }
        }
        return BulkDeleteResult(deletedCount, protectedCount, failedCount)
    }

    fun toggleItemSelection(downloadId: String) {
        updateItem(downloadId) { it.copy(isSelected = !it.isSelected) }
    }

    fun selectAll(select: Boolean) {
        _downloads.update { list -> list.map { it.copy(isSelected = select) } }
        persistDownloads()
    }

    fun cancelDownload(downloadId: String) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        updateItem(downloadId) { it.copy(status = DownloadStatus.CANCELLED) }

        // Clean up partial temporary file
        try {
            val tempFile = File(context.cacheDir, "yas_dl_${downloadId}.tmp")
            if (tempFile.exists()) tempFile.delete()
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun removeDownload(downloadId: String) {
        deleteDownload(downloadId, deleteFileFromStorage = false, protectIfActive = false)
    }

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        _downloads.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
        persistDownloads()
    }
}
