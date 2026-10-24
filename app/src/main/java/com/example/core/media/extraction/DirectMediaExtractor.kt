package com.example.core.media.extraction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Locale

class DirectMediaExtractor(
    private val client: OkHttpClient = OkHttpClient()
) : MediaExtractor {

    private val supportedExtensions = listOf(".mp4", ".webm", ".mkv", ".mp3", ".m4a", ".wav", ".aac", ".ogg")

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return supportedExtensions.any { lower.contains(it) }
    }

    override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).head().build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                // If HEAD fails, proceed with basic URL analysis
                null
            }

            val contentType = response?.header("Content-Type") ?: ""
            val contentLength = response?.header("Content-Length")?.toLongOrNull()

            val isAudio = contentType.contains("audio") ||
                    url.endsWith(".mp3", ignoreCase = true) ||
                    url.endsWith(".m4a", ignoreCase = true) ||
                    url.endsWith(".wav", ignoreCase = true)

            val fileName = try {
                val path = URI(url).path
                path.substringAfterLast('/').ifBlank { "media_file" }
            } catch (e: Exception) {
                "media_file"
            }

            val streams = mutableListOf<MediaStream>()

            if (isAudio) {
                streams.add(
                    MediaStream(
                        id = "direct_audio_1",
                        url = url,
                        quality = "Direct Audio (Original)",
                        format = "MP3",
                        isVideo = false,
                        isAudio = true,
                        contentLength = contentLength
                    )
                )
            } else {
                streams.add(
                    MediaStream(
                        id = "direct_video_1",
                        url = url,
                        quality = "Direct Video (Original)",
                        format = "MP4",
                        isVideo = true,
                        isAudio = true,
                        contentLength = contentLength
                    )
                )
                // Also provide audio extraction stream
                streams.add(
                    MediaStream(
                        id = "direct_video_audio_1",
                        url = url,
                        quality = "Audio Track (MP3)",
                        format = "MP3",
                        isVideo = false,
                        isAudio = true,
                        contentLength = contentLength
                    )
                )
            }

            val metadata = MediaMetadata(
                id = fileName,
                title = fileName,
                author = "Direct Link",
                thumbnailUrl = null,
                sourceUrl = url,
                platform = Platform.DIRECT
            )

            ExtractionResult.Success(metadata, streams)
        } catch (e: Exception) {
            ExtractionResult.Error("Failed to extract direct media: ${e.message}", e)
        }
    }
}
