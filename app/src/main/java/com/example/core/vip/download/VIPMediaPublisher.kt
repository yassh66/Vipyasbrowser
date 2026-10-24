package com.example.core.vip.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of publishing a downloaded VIP media file to Android MediaStore.
 */
sealed class VIPPublishResult {
    data class Success(
        val contentUriString: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long
    ) : VIPPublishResult() {
        /**
         * Android Uri representation of the published MediaStore entry.
         */
        val contentUri: Uri get() = Uri.parse(contentUriString)
    }

    data class Failure(
        val reason: String,
        val throwable: Throwable? = null
    ) : VIPPublishResult()
}

/**
 * Interface abstracting MediaStore operations for testability, isolation, and scoped storage safety.
 */
interface MediaStoreWriter {
    fun insertPending(displayName: String, mimeType: String, relativePath: String): String?
    fun openOutputStream(uriString: String): OutputStream?
    fun finalizePending(uriString: String): Boolean
    fun delete(uriString: String): Boolean
}

/**
 * Default MediaStore writer delegating to Android's ContentResolver.
 */
class DefaultMediaStoreWriter(private val contentResolver: ContentResolver) : MediaStoreWriter {
    override fun insertPending(displayName: String, mimeType: String, relativePath: String): String? {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(collection, values)
        return uri?.toString()
    }

    override fun openOutputStream(uriString: String): OutputStream? {
        val uri = Uri.parse(uriString)
        return contentResolver.openOutputStream(uri)
    }

    override fun finalizePending(uriString: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = Uri.parse(uriString)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            return contentResolver.update(uri, values, null, null) > 0
        }
        return true
    }

    override fun delete(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Dedicated MediaStore publisher for successfully completed VIP media downloads.
 *
 * Ensures:
 * - Only verified, COMPLETED downloads with valid non-empty media files are published.
 * - Safe sanitization of display filenames and reliable MIME/extension resolution.
 * - Android 10+ IS_PENDING scoped storage contract compliance.
 * - Incomplete entry deletion upon failure or interruption.
 * - Strict ownership tracking for published MediaStore URIs.
 */
class VIPMediaPublisher(
    private val writer: MediaStoreWriter
) {
    /**
     * Convenience constructor using Android Context.
     */
    constructor(context: Context) : this(DefaultMediaStoreWriter(context.contentResolver))

    // Track URIs published during the active session to prevent deletion of foreign files
    private val publishedUris = ConcurrentHashMap.newKeySet<String>()

    /**
     * Publishes a completed VIP media download item into Android's public MediaStore.
     *
     * @param item Completed VIP download item.
     * @param deleteSourceOnSuccess If true, deletes the temporary file only after MediaStore finalization.
     * @return Success containing the published URI and metadata, or Failure with reason.
     */
    fun publishMedia(
        item: VIPDownloadItem,
        deleteSourceOnSuccess: Boolean = true
    ): VIPPublishResult {
        // 1. Verify item state
        if (item.status != VIPDownloadStatus.COMPLETED) {
            return VIPPublishResult.Failure("Cannot publish download with non-completed status: ${item.status}")
        }

        // 2. Verify source file exists
        val sourceFile = item.tempFile
        if (sourceFile == null || !sourceFile.exists()) {
            return VIPPublishResult.Failure("Source temporary file does not exist")
        }

        // 3. Verify source file is non-empty
        val fileLength = sourceFile.length()
        if (fileLength == 0L) {
            return VIPPublishResult.Failure("Source temporary file is empty (0 bytes)")
        }

        // 4. Verify source file is not truncated
        if (item.totalBytes > 0L && fileLength < item.totalBytes) {
            return VIPPublishResult.Failure("Source file is incomplete (found $fileLength bytes, expected ${item.totalBytes})")
        }

        // 5. Verify container signature and media format integrity
        val validation = VIPMediaValidator.validateMediaFile(sourceFile, item.format)
        if (validation is VIPMediaValidator.Result.Invalid) {
            return VIPPublishResult.Failure("Media validation rejected publication: ${validation.reason}")
        }

        // 6. Resolve extension, MIME type, and sanitized filename
        val (mimeType, extension) = resolveMimeAndExtension(item.format)
        val displayName = sanitizeDisplayName(item.mediaTitle, extension)
        val relativePath = "Movies/YAS Browser"

        // 7. Insert pending entry into MediaStore
        val targetUriString = writer.insertPending(displayName, mimeType, relativePath)
            ?: return VIPPublishResult.Failure("Failed to allocate MediaStore entry")

        // 8. Stream copy bytes to MediaStore output stream
        try {
            val outputStream = writer.openOutputStream(targetUriString)
                ?: throw IOException("Failed to open output stream for MediaStore URI")

            outputStream.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, bufferSize = BUFFER_SIZE_BYTES)
                }
                out.flush()
            }
        } catch (e: Exception) {
            // Clean up incomplete MediaStore entry immediately
            writer.delete(targetUriString)
            val cleanMsg = e.message?.replace(Regex("(\\?|&)[^\\s'\"]+"), "") ?: "I/O copy error"
            return VIPPublishResult.Failure("Failed to copy media bytes to MediaStore: $cleanMsg", e)
        }

        // 9. Finalize entry (clear IS_PENDING)
        val finalized = writer.finalizePending(targetUriString)
        if (!finalized) {
            writer.delete(targetUriString)
            return VIPPublishResult.Failure("Failed to finalize MediaStore entry")
        }

        // 10. Register ownership
        publishedUris.add(targetUriString)

        // 11. Optionally delete temporary source file after confirmed success
        if (deleteSourceOnSuccess) {
            try {
                sourceFile.delete()
            } catch (_: Exception) { }
        }

        return VIPPublishResult.Success(
            contentUriString = targetUriString,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = fileLength
        )
    }

    /**
     * Deletes a published media item only if its exact URI was published and owned by this VIP session.
     * Prevents deletion of other apps' or users' files.
     */
    fun deletePublishedMedia(uriString: String): Boolean {
        if (!publishedUris.contains(uriString)) {
            return false
        }
        val deleted = writer.delete(uriString)
        if (deleted) {
            publishedUris.remove(uriString)
        }
        return deleted
    }

    /**
     * Deletes a published media item by Uri if owned by this session.
     */
    fun deletePublishedMedia(uri: Uri): Boolean = deletePublishedMedia(uri.toString())

    /**
     * Returns true if the exact URI string is recognized as owned by this VIP session.
     */
    fun isOwnedPublishedUri(uriString: String): Boolean = publishedUris.contains(uriString)

    /**
     * Returns true if the exact Uri is recognized as owned by this VIP session.
     */
    fun isOwnedPublishedUri(uri: Uri): Boolean = isOwnedPublishedUri(uri.toString())

    companion object {
        private const val BUFFER_SIZE_BYTES = 32 * 1024
        private const val MAX_TITLE_LENGTH = 80

        /**
         * Sanitizes a title for filesystem and MediaStore display names.
         * Strips control characters, directory separators, and illegal characters.
         */
        fun sanitizeDisplayName(rawTitle: String, extension: String): String {
            val cleanExt = extension.trimStart('.').lowercase().ifBlank { "mp4" }

            // Replace control characters and collapse whitespace
            val normalized = rawTitle.replace(Regex("[\\s\\x00-\\x1F\\x7F]+"), " ").trim()

            // Replace illegal characters on FAT32/ext4/Android filesystems: \ / : * ? " < > |
            var base = normalized
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('.', ' ', '_')

            if (base.isBlank() || base.all { it == '_' }) {
                base = "VIP_Media"
            }

            if (base.length > MAX_TITLE_LENGTH) {
                base = base.take(MAX_TITLE_LENGTH).trimEnd()
            }

            return "$base.$cleanExt"
        }

        /**
         * Derives reliable MIME type and extension from the stream format.
         */
        fun resolveMimeAndExtension(format: String): Pair<String, String> {
            return when (format.trim().uppercase()) {
                "WEBM" -> Pair("video/webm", "webm")
                "MKV" -> Pair("video/x-matroska", "mkv")
                "3GP" -> Pair("video/3gpp", "3gp")
                "MOV" -> Pair("video/quicktime", "mov")
                "MP4" -> Pair("video/mp4", "mp4")
                else -> Pair("video/mp4", "mp4")
            }
        }

        /**
         * Legacy helper maintained for backward compatibility.
         */
        fun isHtmlOrTextError(file: File): Boolean {
            val res = VIPMediaValidator.validateMediaFile(file)
            return res is VIPMediaValidator.Result.Invalid &&
                (res.reason.contains("HTML", ignoreCase = true) ||
                 res.reason.contains("markup", ignoreCase = true) ||
                 res.reason.contains("challenge", ignoreCase = true) ||
                 res.reason.contains("JSON", ignoreCase = true) ||
                 res.reason.contains("HLS", ignoreCase = true))
        }
    }
}

/**
 * Format-aware, conservative container signature validator for downloaded media files.
 * Validates MP4/ISO-BMFF and WebM/EBML container structures while rejecting HTML, XML,
 * JSON, HLS playlists, subtitle tracks, and corrupt payloads.
 */
object VIPMediaValidator {

    private const val MIN_MEDIA_SIZE_BYTES = 128L

    sealed class Result {
        object Valid : Result()
        data class Invalid(val reason: String) : Result()
    }

    /**
     * Validates that the downloaded file is a genuine, uncorrupted media file matching the expected format.
     */
    fun validateMediaFile(file: File, expectedFormat: String = "MP4"): Result {
        if (!file.exists()) {
            return Result.Invalid("File does not exist")
        }
        val length = file.length()
        if (length == 0L) {
            return Result.Invalid("File is empty (0 bytes)")
        }
        if (length < MIN_MEDIA_SIZE_BYTES) {
            return Result.Invalid("File size ($length bytes) is too small to be a valid playable video")
        }

        val headerSize = 512.coerceAtMost(length.toInt())
        val header = ByteArray(headerSize)
        val bytesRead = try {
            FileInputStream(file).use { it.read(header) }
        } catch (e: Exception) {
            return Result.Invalid("Could not read file header: ${e.message}")
        }
        if (bytesRead < 8) {
            return Result.Invalid("Incomplete file header (read $bytesRead bytes)")
        }

        // 1. Text / Markup / Error Check
        val textCheck = checkNonMediaSignatures(header, bytesRead)
        if (textCheck != null) {
            return Result.Invalid(textCheck)
        }

        // 2. Format-aware container signature validation
        return when (expectedFormat.trim().uppercase()) {
            "MP4" -> validateMp4(header, bytesRead, length)
            "WEBM" -> validateWebm(header, bytesRead, length)
            "MKV" -> validateWebm(header, bytesRead, length)
            else -> {
                // If format unknown or generic, attempt MP4 then WebM
                val mp4Res = validateMp4(header, bytesRead, length)
                if (mp4Res is Result.Valid) {
                    Result.Valid
                } else {
                    val webmRes = validateWebm(header, bytesRead, length)
                    if (webmRes is Result.Valid) {
                        Result.Valid
                    } else {
                        Result.Invalid("Unsupported or unrecognizable media container format: $expectedFormat")
                    }
                }
            }
        }
    }

    private fun checkNonMediaSignatures(header: ByteArray, length: Int): String? {
        val str = String(header, 0, length, Charsets.UTF_8).lowercase().trimStart()

        if (str.startsWith("<!doctype html") ||
            str.startsWith("<html") ||
            str.startsWith("<?xml") ||
            str.startsWith("<html>") ||
            str.contains("<title>cloudflare") ||
            str.contains("<title>access denied") ||
            str.contains("<title>just a moment") ||
            str.contains("<title>403") ||
            str.contains("<title>404") ||
            str.contains("<title>500") ||
            str.contains("<body")
        ) {
            return "File contains HTML markup or access challenge rather than media"
        }

        if (str.startsWith("{") || str.startsWith("[")) {
            return "File contains JSON payload rather than media"
        }

        if (str.startsWith("#extm3u")) {
            return "File contains HLS playlist manifest (#EXTM3U) rather than a progressive playable video"
        }

        if (str.startsWith("webvtt")) {
            return "File contains subtitle/text tracks rather than video"
        }

        return null
    }

    private fun validateMp4(header: ByteArray, bytesRead: Int, totalLength: Long): Result {
        if (bytesRead < 8) {
            return Result.Invalid("Header too short for MP4 box ($bytesRead bytes)")
        }

        // ISO BMFF box: 4 bytes size, 4 bytes type
        val boxSize = readBigEndianUInt32(header, 0)
        val boxType = String(header, 4, 4, Charsets.US_ASCII)

        // Standard MP4: starts with 'ftyp'
        if (boxType == "ftyp") {
            if (boxSize < 8L) {
                return Result.Invalid("Invalid MP4 ftyp box size ($boxSize bytes)")
            }
            if (boxSize > totalLength) {
                return Result.Invalid("MP4 ftyp box size ($boxSize bytes) exceeds total file size ($totalLength bytes)")
            }
            if (bytesRead >= 12) {
                val majorBrand = String(header, 8, 4, Charsets.US_ASCII)
                if (majorBrand.isBlank() || majorBrand.all { it == '\u0000' }) {
                    return Result.Invalid("Invalid MP4 major brand in ftyp box")
                }
            }
            return Result.Valid
        }

        // Some MP4s start with padding 'free', 'skip', or 'wide'
        if (boxType == "free" || boxType == "skip" || boxType == "wide") {
            if (boxSize in 8L until totalLength && boxSize < bytesRead - 8) {
                val nextOffset = boxSize.toInt()
                val nextBoxType = String(header, nextOffset + 4, 4, Charsets.US_ASCII)
                if (nextBoxType == "ftyp" || nextBoxType == "moov" || nextBoxType == "mdat") {
                    return Result.Valid
                }
            }
        }

        // Fragmented or live-recorded MP4s can start with 'moov' or 'mdat'
        if (boxType == "moov" || boxType == "mdat") {
            if (boxSize >= 8L && (boxSize <= totalLength || boxSize == 0L)) {
                return Result.Valid
            }
        }

        return Result.Invalid("File does not start with valid ISO-BMFF / MP4 container signature (found box: '$boxType')")
    }

    private fun validateWebm(header: ByteArray, bytesRead: Int, totalLength: Long): Result {
        if (bytesRead < 4) {
            return Result.Invalid("Header too short for WebM ($bytesRead bytes)")
        }
        // EBML magic: 0x1A 0x45 0xDF 0xA3
        if (header[0] == 0x1A.toByte() &&
            header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() &&
            header[3] == 0xA3.toByte()
        ) {
            return Result.Valid
        }
        return Result.Invalid("File does not start with valid WebM/EBML container signature")
    }

    private fun readBigEndianUInt32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
    }
}
