package com.example.core.media.extraction.youtube

import com.example.core.media.extraction.ExtractionResult
import com.example.core.media.extraction.MediaExtractor
import com.example.core.media.extraction.MediaMetadata
import com.example.core.media.extraction.MediaStream
import com.example.core.media.extraction.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

class NewPipeYouTubeExtractor(
    private val client: OkHttpClient = defaultHttpClient()
) : MediaExtractor {

    companion object {
        private val isInitialized = AtomicBoolean(false)

        private val YOUTUBE_URL_PATTERNS = listOf(
            Pattern.compile("https?://(?:www\\.|m\\.)?youtube\\.com/watch\\?.*v=([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://youtu\\.be/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://(?:www\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://(?:www\\.)?youtube\\.com/embed/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://(?:www\\.)?youtube\\.com/live/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE)
        )

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private fun ensureInitialized() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                NewPipe.init(NewPipeDownloader(client))
            } catch (e: Exception) {
                // If already initialized or init warning, proceed
            }
        }
    }

    override fun canHandle(url: String): Boolean {
        if (url.isBlank()) return false
        val clean = url.trim()
        return YOUTUBE_URL_PATTERNS.any { it.matcher(clean).find() }
    }

    fun extractVideoId(url: String): String? {
        val clean = url.trim()
        for (pattern in YOUTUBE_URL_PATTERNS) {
            val matcher = pattern.matcher(clean)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)
            ?: return@withContext ExtractionResult.Error("Could not parse YouTube video ID from URL: $url")

        ensureInitialized()

        try {
            val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)

            val title = streamInfo.name.ifBlank { "YouTube Video ($videoId)" }
            val author = streamInfo.uploaderName.ifBlank { "YouTube Creator" }
            val thumbUrl = streamInfo.thumbnails.firstOrNull()?.url ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
            val duration = streamInfo.duration

            val streams = mutableListOf<MediaStream>()

            // 1. Audio streams: prefer M4A/AAC for pairing with MP4 video
            val audioStreams = streamInfo.audioStreams ?: emptyList<AudioStream>()
            val bestAudio = audioStreams.firstOrNull { it.format?.name.equals("M4A", ignoreCase = true) }
                ?: audioStreams.firstOrNull()

            val bestAudioUrl = bestAudio?.content
            val audioHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )

            // 2. Video streams: Progressive streams (video + audio)
            val progressiveStreams = streamInfo.videoStreams ?: emptyList<VideoStream>()
            for ((idx, prog) in progressiveStreams.withIndex()) {
                val streamUrl = prog.content
                if (!streamUrl.isNullOrBlank()) {
                    val res = prog.resolution ?: "${prog.height}p"
                    val formatName = prog.format?.name ?: "MP4"
                    streams.add(
                        MediaStream(
                            id = "np_${videoId}_prog_${idx}",
                            url = streamUrl,
                            quality = "$res ($formatName)",
                            format = formatName,
                            isVideo = true,
                            isAudio = true,
                            requiresAudioMerge = false,
                            headers = audioHeaders
                        )
                    )
                }
            }

            // 3. Adaptive Video-Only Streams (1080p, 720p, 480p, 360p) paired with best audio
            val videoOnlyStreams = streamInfo.videoOnlyStreams ?: emptyList<VideoStream>()
            if (bestAudioUrl != null) {
                for ((idx, vOnly) in videoOnlyStreams.withIndex()) {
                    val streamUrl = vOnly.content
                    if (!streamUrl.isNullOrBlank()) {
                        val res = vOnly.resolution ?: "${vOnly.height}p"
                        val formatName = vOnly.format?.name ?: "MP4"
                        streams.add(
                            MediaStream(
                                id = "np_${videoId}_adaptive_${idx}",
                                url = streamUrl,
                                quality = "$res ($formatName)",
                                format = formatName,
                                isVideo = true,
                                isAudio = false,
                                requiresAudioMerge = true,
                                audioUrl = bestAudioUrl,
                                audioHeaders = audioHeaders,
                                headers = audioHeaders
                            )
                        )
                    }
                }
            }

            // Deduplicate video streams by resolution, preferring progressive over adaptive if same resolution
            val distinctVideoStreams = mutableListOf<MediaStream>()
            val seenResolutions = mutableSetOf<String>()

            // Sort descending: 1080p, 720p, 480p, 360p...
            val sortedVideos = streams.filter { it.isVideo }.sortedByDescending { extractHeightNumber(it.quality) }
            for (v in sortedVideos) {
                val heightStr = extractHeightNumber(v.quality).toString()
                if (seenResolutions.add(heightStr)) {
                    distinctVideoStreams.add(v)
                }
            }

            // 4. Standalone Audio Tracks & MP3 option
            val distinctAudioStreams = mutableListOf<MediaStream>()
            for ((idx, audio) in audioStreams.withIndex()) {
                val aUrl = audio.content
                if (!aUrl.isNullOrBlank()) {
                    val bitrate = if (audio.averageBitrate > 0) audio.averageBitrate else 128
                    val fmt = audio.format?.name ?: "M4A"
                    val label = "Audio Track (${bitrate}kbps $fmt)"

                    val m4aStream = MediaStream(
                        id = "np_${videoId}_audio_${idx}",
                        url = aUrl,
                        quality = label,
                        format = fmt,
                        isVideo = false,
                        isAudio = true,
                        headers = audioHeaders
                    )
                    distinctAudioStreams.add(m4aStream)

                    // Add genuine MP3 conversion option
                    if (fmt.equals("M4A", ignoreCase = true) || fmt.equals("AAC", ignoreCase = true)) {
                        distinctAudioStreams.add(
                            m4aStream.copy(
                                id = "np_${videoId}_audio_${idx}_mp3",
                                quality = label.replace(fmt, "MP3"),
                                format = "MP3"
                            )
                        )
                    }
                }
            }

            val finalStreams = mutableListOf<MediaStream>()
            finalStreams.addAll(distinctVideoStreams)
            finalStreams.addAll(distinctAudioStreams)

            if (finalStreams.isNotEmpty()) {
                val metadata = MediaMetadata(
                    id = videoId,
                    title = title,
                    author = author,
                    thumbnailUrl = thumbUrl,
                    sourceUrl = url,
                    platform = Platform.YOUTUBE,
                    durationSeconds = duration
                )
                return@withContext ExtractionResult.Success(metadata, finalStreams)
            } else {
                return@withContext ExtractionResult.NoFormatsFound("No playable video or audio streams extracted from YouTube.")
            }
        } catch (e: Exception) {
            return@withContext ExtractionResult.Error("NewPipe extraction failed: ${e.message}", e)
        }
    }

    private fun extractHeightNumber(qualityLabel: String): Int {
        val matcher = Pattern.compile("(\\d{3,4})p").matcher(qualityLabel)
        return if (matcher.find()) {
            matcher.group(1)?.toIntOrNull() ?: 0
        } else 0
    }
}
