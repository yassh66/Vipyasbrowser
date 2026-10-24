package com.example.core.vip.download

import android.content.Context
import com.example.core.vip.extraction.VIPMediaMetadata
import com.example.core.vip.extraction.VIPMediaStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated, dedicated download engine for VIP media streams.
 *
 * Streams bytes incrementally to a temporary file without buffering full payloads into memory.
 * Features safe cross-host redirect policies, atomic task registration, truncation detection,
 * guaranteed resource closure order, and terminal-state race protection.
 */
class VIPDownloadEngine(
    private val downloadDir: File,
    client: OkHttpClient = createDefaultClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    /**
     * Android Context convenience constructor.
     */
    constructor(
        context: Context,
        client: OkHttpClient = createDefaultClient(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) : this(
        downloadDir = File(context.cacheDir, "vip_downloads"),
        client = client,
        scope = scope
    )

    // Ensure automatic redirects are disabled so our safe redirect policy governs all requests
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val _downloads = MutableStateFlow<Map<String, VIPDownloadItem>>(emptyMap())
    val downloads: StateFlow<List<VIPDownloadItem>> = _downloads.map { it.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList<VIPDownloadItem>())

    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val taskRegistrationLock = Any()
    private val stateUpdateLock = Any()

    init {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
    }

    /**
     * Enqueues and starts downloading a genuine VIP stream.
     * Task deduplication and registration are atomic.
     *
     * @param metadata Extracted media metadata for display and identification.
     * @param stream Valid media stream target.
     * @return Result containing the unique taskId on success, or an exception on validation/duplicate error.
     */
    fun startDownload(
        metadata: VIPMediaMetadata,
        stream: VIPMediaStream
    ): Result<String> {
        val (taskId, _) = synchronized(taskRegistrationLock) {
            if (!isValidStreamUrl(stream.url)) {
                return Result.failure(IllegalArgumentException("Invalid or malformed stream URL"))
            }

            val activeDuplicate = _downloads.value.values.find {
                it.streamUrl == stream.url && it.isActive
            }
            if (activeDuplicate != null) {
                return Result.failure(
                    IllegalStateException("Download already active for this stream URL (taskId: ${activeDuplicate.id})")
                )
            }

            val id = UUID.randomUUID().toString().take(8)
            val initialItem = VIPDownloadItem(
                id = id,
                mediaTitle = metadata.title.ifBlank { "VIP Media" },
                author = metadata.author.ifBlank { metadata.siteName },
                quality = stream.quality,
                format = stream.format,
                streamUrl = stream.url,
                totalBytes = stream.contentLength ?: -1L,
                status = VIPDownloadStatus.QUEUED
            )

            updateItem(id, initialItem)

            val job = scope.launch {
                executeDownload(id, metadata, stream)
            }
            activeJobs[id] = job

            Pair(id, job)
        }

        return Result.success(taskId)
    }

    /**
     * Cancels an ongoing download and immediately deletes incomplete temporary files.
     */
    fun cancelDownload(taskId: String) {
        val call = activeCalls.remove(taskId)
        call?.cancel()

        val job = activeJobs.remove(taskId)
        job?.cancel()

        updateItem(taskId) { current ->
            if (current.isTerminal) {
                current
            } else {
                current.tempFile?.let { file ->
                    if (file.exists()) {
                        file.delete()
                    }
                }
                current.copy(
                    status = VIPDownloadStatus.CANCELLED,
                    speedBytesPerSec = 0L,
                    errorMessage = null
                )
            }
        }
    }

    /**
     * Observes changes for a single download task.
     */
    fun observeDownload(taskId: String): Flow<VIPDownloadItem?> {
        return _downloads.map { it[taskId] }.distinctUntilChanged()
    }

    /**
     * Returns snapshot of a single download task.
     */
    fun getDownload(taskId: String): VIPDownloadItem? {
        return _downloads.value[taskId]
    }

    /**
     * Removes a download record from tracking, optionally deleting its file.
     */
    fun removeDownload(taskId: String, deleteFile: Boolean = true) {
        cancelDownload(taskId)
        val item = _downloads.value[taskId]
        if (deleteFile && item?.tempFile?.exists() == true) {
            item.tempFile.delete()
        }
        synchronized(stateUpdateLock) {
            _downloads.value = _downloads.value - taskId
        }
    }

    private suspend fun executeDownload(
        taskId: String,
        metadata: VIPMediaMetadata,
        stream: VIPMediaStream
    ) {
        val tempFile = File(downloadDir, "vip_dl_${taskId}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        updateItem(taskId) { current ->
            if (current.isTerminal) current else current.copy(
                tempFile = tempFile,
                status = VIPDownloadStatus.DOWNLOADING
            )
        }

        var outputStream: FileOutputStream? = null
        var response: Response? = null

        try {
            // Explicit safe redirect handling loop
            var currentUrl = stream.url
            var currentHeaders = stream.headers.toMutableMap()
            val visitedUrls = mutableSetOf(currentUrl)
            var redirectCount = 0

            while (true) {
                if (!isValidStreamUrl(currentUrl)) {
                    cleanUpFailedTask(
                        taskId,
                        tempFile,
                        "Invalid or malformed target URL",
                        response,
                        outputStream
                    )
                    return
                }

                val requestBuilder = Request.Builder()
                    .url(currentUrl)
                    .get()

                currentHeaders.forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                if (!currentHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                    requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
                }

                val call = client.newCall(requestBuilder.build())
                activeCalls[taskId] = call

                val currentResponse: Response
                try {
                    currentResponse = call.execute()
                } catch (e: CancellationException) {
                    cleanUpCancelledTask(taskId, tempFile, null, outputStream)
                    throw e
                } catch (e: IOException) {
                    if (call.isCanceled() || !scope.isActive) {
                        cleanUpCancelledTask(taskId, tempFile, null, outputStream)
                    } else {
                        val cleanMsg = sanitizeErrorMessage(e.message)
                        cleanUpFailedTask(taskId, tempFile, cleanMsg, null, outputStream)
                    }
                    return
                }

                if (currentResponse.isRedirect) {
                    val locationHeader = currentResponse.header("Location")
                    currentResponse.close() // Close intermediate response immediately

                    if (locationHeader.isNullOrBlank()) {
                        cleanUpFailedTask(
                            taskId,
                            tempFile,
                            "Redirect response missing Location header",
                            null,
                            outputStream
                        )
                        return
                    }

                    if (redirectCount >= MAX_REDIRECTS) {
                        cleanUpFailedTask(
                            taskId,
                            tempFile,
                            "Too many redirects (exceeded limit of $MAX_REDIRECTS)",
                            null,
                            outputStream
                        )
                        return
                    }

                    val nextUri = try {
                        val baseUri = URI(currentUrl)
                        baseUri.resolve(locationHeader)
                    } catch (_: Exception) {
                        cleanUpFailedTask(
                            taskId,
                            tempFile,
                            "Malformed redirect Location header",
                            null,
                            outputStream
                        )
                        return
                    }

                    val currentScheme = try { URI(currentUrl).scheme?.lowercase() } catch (_: Exception) { null }
                    val nextScheme = nextUri.scheme?.lowercase()

                    // Reject HTTPS-to-HTTP downgrade
                    if (currentScheme == "https" && nextScheme == "http") {
                        cleanUpFailedTask(
                            taskId,
                            tempFile,
                            "Insecure HTTPS-to-HTTP redirect downgrade rejected",
                            null,
                            outputStream
                        )
                        return
                    }

                    if (nextScheme != "http" && nextScheme != "https") {
                        cleanUpFailedTask(
                            taskId,
                            tempFile,
                            "Invalid redirect scheme: $nextScheme",
                            null,
                            outputStream
                        )
                        return
                    }

                    val nextUrl = nextUri.toString()
                    if (visitedUrls.contains(nextUrl)) {
                        cleanUpFailedTask(
                            taskId,
                            tempFile,
                            "Redirect loop detected",
                            null,
                            outputStream
                        )
                        return
                    }
                    visitedUrls.add(nextUrl)

                    val currentHost = try { URI(currentUrl).host?.lowercase() } catch (_: Exception) { null }
                    val nextHost = nextUri.host?.lowercase()

                    // Cross-host redirect: strip sensitive and site-specific headers
                    if (currentHost != nextHost) {
                        val strippedHeaders = mutableMapOf<String, String>()
                        currentHeaders.forEach { (k, v) ->
                            if (SAFE_FORWARD_HEADERS.contains(k.lowercase())) {
                                strippedHeaders[k] = v
                            }
                        }
                        currentHeaders = strippedHeaders
                    }

                    currentUrl = nextUrl
                    redirectCount++
                    continue
                }

                // Final non-redirect response obtained
                response = currentResponse
                break
            }

            val finalResponse = response ?: run {
                cleanUpFailedTask(taskId, tempFile, "Failed to establish media connection", null, outputStream)
                return
            }

            val code = finalResponse.code
            val contentType = finalResponse.header("Content-Type")
            val cleanType = contentType?.split(";")?.firstOrNull()?.trim()?.lowercase() ?: "unknown"
            val finalHost = try { URI(finalResponse.request.url.toString()).host?.lowercase() ?: "unknown" } catch (_: Exception) { "unknown" }

            if (!finalResponse.isSuccessful) {
                val (receivedBytes, summary) = extractBodyDiagnostics(finalResponse)
                val diagnosticDetails = buildString {
                    append("HTTP $code")
                    append(", host: $finalHost")
                    append(", type: $cleanType")
                    append(", received: $receivedBytes bytes")
                    if (!summary.isNullOrBlank()) {
                        append(", summary: $summary")
                    }
                }
                cleanUpFailedTask(
                    taskId,
                    tempFile,
                    "HTTP download failed with code $code [$diagnosticDetails]",
                    finalResponse,
                    outputStream
                )
                return
            }

            if (isForbiddenContentType(contentType)) {
                val (receivedBytes, summary) = extractBodyDiagnostics(finalResponse)
                val diagnosticDetails = buildString {
                    append("HTTP $code")
                    append(", host: $finalHost")
                    append(", type: $cleanType")
                    append(", received: $receivedBytes bytes")
                    if (!summary.isNullOrBlank()) {
                        append(", summary: $summary")
                    }
                }
                cleanUpFailedTask(
                    taskId,
                    tempFile,
                    "Invalid media content type ($cleanType) [$diagnosticDetails]",
                    finalResponse,
                    outputStream
                )
                return
            }

            val body = finalResponse.body
            if (body == null) {
                cleanUpFailedTask(
                    taskId,
                    tempFile,
                    "Empty HTTP response body",
                    finalResponse,
                    outputStream
                )
                return
            }

            val serverContentLength = body.contentLength()
            val totalBytes = when {
                serverContentLength > 0L -> serverContentLength
                (stream.contentLength ?: -1L) > 0L -> stream.contentLength!!
                else -> -1L
            }

            updateItem(taskId) { current ->
                if (current.isTerminal) current else current.copy(totalBytes = totalBytes)
            }

            outputStream = FileOutputStream(tempFile)
            val inputStream = body.byteStream()
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            var totalBytesRead = 0L
            var lastEmissionTime = System.currentTimeMillis()
            var lastEmittedBytes = 0L

            while (scope.isActive) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                val now = System.currentTimeMillis()
                val elapsed = now - lastEmissionTime
                if (elapsed >= PROGRESS_INTERVAL_MS) {
                    val speed = if (elapsed > 0) ((totalBytesRead - lastEmittedBytes) * 1000L) / elapsed else 0L
                    val progress = if (totalBytes > 0L) {
                        (totalBytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else {
                        -1f
                    }

                    updateItem(taskId) { current ->
                        if (current.isTerminal) {
                            current
                        } else {
                            current.copy(
                                downloadedBytes = totalBytesRead,
                                progress = progress,
                                speedBytesPerSec = speed,
                                status = VIPDownloadStatus.DOWNLOADING
                            )
                        }
                    }

                    lastEmissionTime = now
                    lastEmittedBytes = totalBytesRead
                }
            }

            outputStream.flush()
            try {
                outputStream.close()
            } catch (_: Exception) { }
            outputStream = null

            try {
                finalResponse.close()
            } catch (_: Exception) { }
            response = null

            if (!scope.isActive || activeCalls[taskId]?.isCanceled() == true) {
                cleanUpCancelledTask(taskId, tempFile, null, null)
                return
            }

            if (totalBytesRead == 0L) {
                cleanUpFailedTask(
                    taskId,
                    tempFile,
                    "Empty media payload received (0 bytes)",
                    null,
                    null
                )
                return
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                cleanUpFailedTask(
                    taskId,
                    tempFile,
                    "Target file not written or empty on disk",
                    null,
                    null
                )
                return
            }

            if (tempFile.length() != totalBytesRead) {
                cleanUpFailedTask(
                    taskId,
                    tempFile,
                    "File size on disk (${tempFile.length()} bytes) does not match received bytes ($totalBytesRead bytes)",
                    null,
                    null
                )
                return
            }

            // Truncation check: verify received bytes match declared Content-Length
            if (serverContentLength > 0L && totalBytesRead < serverContentLength) {
                cleanUpFailedTask(
                    taskId,
                    tempFile,
                    "Download truncated: received $totalBytesRead of $serverContentLength declared bytes",
                    null,
                    null
                )
                return
            }

            // Format-aware container signature validation before declaring COMPLETED
            val validation = VIPMediaValidator.validateMediaFile(tempFile, stream.format)
            if (validation is VIPMediaValidator.Result.Invalid) {
                val sampleSummary = if (tempFile.exists() && tempFile.length() > 0) {
                    try {
                        val header = tempFile.inputStream().use { s ->
                            val buf = ByteArray(2048)
                            val read = s.read(buf)
                            if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
                        }
                        extractSanitizedJsonSummary(header)
                    } catch (_: Exception) { null }
                } else null

                val extraInfo = if (!sampleSummary.isNullOrBlank()) " [summary: $sampleSummary]" else ""
                cleanUpFailedTask(
                    taskId,
                    tempFile,
                    "Downloaded file validation failed: ${validation.reason}$extraInfo",
                    null,
                    null
                )
                return
            }

            updateItem(taskId) { current ->
                if (current.isTerminal) {
                    current
                } else {
                    current.copy(
                        downloadedBytes = totalBytesRead,
                        totalBytes = if (totalBytes > 0L) totalBytes else totalBytesRead,
                        progress = 1.0f,
                        speedBytesPerSec = 0L,
                        status = VIPDownloadStatus.COMPLETED,
                        errorMessage = null
                    )
                }
            }
        } catch (e: CancellationException) {
            cleanUpCancelledTask(taskId, tempFile, response, outputStream)
            throw e
        } catch (e: IOException) {
            val call = activeCalls[taskId]
            if (call?.isCanceled() == true || !scope.isActive) {
                cleanUpCancelledTask(taskId, tempFile, response, outputStream)
            } else {
                val cleanMsg = sanitizeErrorMessage(e.message)
                cleanUpFailedTask(taskId, tempFile, cleanMsg, response, outputStream)
            }
        } catch (e: Exception) {
            val cleanMsg = sanitizeErrorMessage(e.message)
            cleanUpFailedTask(taskId, tempFile, cleanMsg, response, outputStream)
        } finally {
            activeCalls.remove(taskId)
            activeJobs.remove(taskId)
            try {
                outputStream?.close()
            } catch (_: Exception) { }
            try {
                response?.close()
            } catch (_: Exception) { }
        }
    }

    private fun cleanUpCancelledTask(
        taskId: String,
        tempFile: File,
        response: Response?,
        outputStream: FileOutputStream?
    ) {
        try {
            outputStream?.flush()
        } catch (_: Exception) { }
        try {
            outputStream?.close()
        } catch (_: Exception) { }
        try {
            response?.close()
        } catch (_: Exception) { }

        var fileDeleted = true
        if (tempFile.exists()) {
            fileDeleted = tempFile.delete()
        }

        updateItem(taskId) { current ->
            if (current.status == VIPDownloadStatus.COMPLETED) {
                current
            } else {
                val errorMsg = if (!fileDeleted && tempFile.exists()) {
                    "Cancellation cleanup: incomplete file could not be deleted"
                } else {
                    null
                }
                current.copy(
                    status = VIPDownloadStatus.CANCELLED,
                    speedBytesPerSec = 0L,
                    errorMessage = errorMsg
                )
            }
        }
    }

    private fun cleanUpFailedTask(
        taskId: String,
        tempFile: File,
        errorMessage: String,
        response: Response?,
        outputStream: FileOutputStream?
    ) {
        try {
            outputStream?.flush()
        } catch (_: Exception) { }
        try {
            outputStream?.close()
        } catch (_: Exception) { }
        try {
            response?.close()
        } catch (_: Exception) { }

        var fileDeleted = true
        if (tempFile.exists()) {
            fileDeleted = tempFile.delete()
        }

        val finalError = if (!fileDeleted && tempFile.exists()) {
            "$errorMessage (incomplete file could not be deleted)"
        } else {
            errorMessage
        }

        updateItem(taskId) { current ->
            if (current.isTerminal) {
                current
            } else {
                current.copy(
                    status = VIPDownloadStatus.FAILED,
                    speedBytesPerSec = 0L,
                    errorMessage = finalError
                )
            }
        }
    }

    private fun updateItem(taskId: String, item: VIPDownloadItem) {
        synchronized(stateUpdateLock) {
            _downloads.value = _downloads.value + (taskId to item)
        }
    }

    private fun updateItem(taskId: String, transform: (VIPDownloadItem) -> VIPDownloadItem) {
        synchronized(stateUpdateLock) {
            val current = _downloads.value[taskId] ?: return
            val updated = transform(current)
            _downloads.value = _downloads.value + (taskId to updated)
        }
    }

    private fun isValidStreamUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun isForbiddenContentType(contentTypeHeader: String?): Boolean {
        if (contentTypeHeader == null) return false
        val clean = contentTypeHeader.split(";").firstOrNull()?.trim()?.lowercase() ?: return false
        return clean.startsWith("text/") ||
                clean.contains("html") ||
                clean.contains("json") ||
                clean.contains("xml") ||
                clean.contains("javascript") ||
                clean.contains("mpegurl") ||
                clean.contains("dash+xml")
    }

    private fun sanitizeErrorMessage(rawMessage: String?): String {
        if (rawMessage.isNullOrBlank()) return "Download connection error"
        // Strip sensitive URL query parameters or credential tokens
        return rawMessage.replace(Regex("(\\?|&)[^\\s'\"]+"), "")
    }

    private fun extractBodyDiagnostics(response: Response?): Pair<Long, String?> {
        if (response == null) return Pair(0L, null)
        return try {
            val body = response.body ?: return Pair(0L, null)
            val stream = body.byteStream()
            val buf = ByteArray(2048)
            var totalRead = 0
            while (totalRead < buf.size) {
                val read = stream.read(buf, totalRead, buf.size - totalRead)
                if (read == -1) break
                totalRead += read
            }
            val sampleStr = if (totalRead > 0) String(buf, 0, totalRead, Charsets.UTF_8) else ""
            val summary = extractSanitizedJsonSummary(sampleStr)
            Pair(totalRead.toLong(), summary)
        } catch (_: Exception) {
            Pair(0L, null)
        }
    }

    companion object {
        private const val BUFFER_SIZE_BYTES = 32 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_REDIRECTS = 3
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        val SAFE_FORWARD_HEADERS = setOf(
            "user-agent",
            "accept",
            "accept-encoding",
            "accept-language",
            "range"
        )

        private fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        /**
         * Helper to sanitize URLs for logging or diagnostic output, removing queries and tokens.
         */
        fun sanitizeUrl(url: String): String {
            return try {
                val uri = URI(url)
                val scheme = uri.scheme ?: "http"
                val host = uri.host ?: "unknown"
                val path = uri.path ?: ""
                "$scheme://$host$path"
            } catch (_: Exception) {
                "[sanitized_url]"
            }
        }

        /**
         * Safely extracts a sanitized, short error summary from a JSON string payload without logging raw secrets.
         */
        fun extractSanitizedJsonSummary(rawBody: String): String? {
            if (rawBody.isBlank()) return null
            val trimmed = rawBody.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null

            return try {
                if (trimmed.startsWith("{")) {
                    val json = JSONObject(trimmed)
                    val candidate = when {
                        json.has("error") -> {
                            val err = json.opt("error")
                            if (err is JSONObject) {
                                err.optString("message", err.optString("detail", err.toString()))
                            } else {
                                err?.toString()
                            }
                        }
                        json.has("message") -> json.optString("message")
                        json.has("detail") -> json.optString("detail")
                        json.has("reason") -> json.optString("reason")
                        json.has("status") -> json.optString("status")
                        json.has("code") -> json.optString("code")
                        else -> null
                    }
                    if (!candidate.isNullOrBlank()) {
                        sanitizeSummaryString(candidate)
                    } else null
                } else if (trimmed.startsWith("[")) {
                    val arr = JSONArray(trimmed)
                    if (arr.length() > 0) {
                        val first = arr.opt(0)
                        if (first is JSONObject) {
                            val candidate = first.optString("error", first.optString("message", ""))
                            if (candidate.isNotBlank()) {
                                return sanitizeSummaryString(candidate)
                            }
                        }
                        "JSON array with ${arr.length()} elements"
                    } else null
                } else null
            } catch (_: Exception) {
                null
            }
        }

        fun sanitizeSummaryString(raw: String): String {
            val noUrls = raw.replace(Regex("https?://[^\\s'\"]+"), "[url]")
                .replace(Regex("(\\?|&)[^\\s'\"]+"), "")
            val clean = noUrls.replace(Regex("[\\r\\n\\t]+"), " ").trim()
            return if (clean.length > 80) clean.take(77) + "..." else clean
        }
    }
}
