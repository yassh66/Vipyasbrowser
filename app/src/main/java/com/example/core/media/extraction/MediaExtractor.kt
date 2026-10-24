package com.example.core.media.extraction

enum class Platform(val displayName: String) {
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    TIKTOK("TikTok"),
    TWITTER("X (Twitter)"),
    DIRECT("Direct Link"),
    UNKNOWN("Web Media")
}

data class MediaStream(
    val id: String,
    val url: String,
    val quality: String,
    val format: String,
    val isVideo: Boolean,
    val isAudio: Boolean,
    val contentLength: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val requiresAudioMerge: Boolean = false,
    val audioUrl: String? = null,
    val audioHeaders: Map<String, String> = emptyMap()
) {
    val isAudioOnly: Boolean get() = isAudio && !isVideo
    val isVideoOnly: Boolean get() = isVideo && !isAudio
    val isCombined: Boolean get() = isVideo && isAudio
}

data class MediaMetadata(
    val id: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String?,
    val sourceUrl: String,
    val platform: Platform,
    val durationSeconds: Long? = null,
    val description: String? = null,
    val isCarousel: Boolean = false,
    val carouselCount: Int = 1
)

sealed class ExtractionResult {
    data class Success(
        val metadata: MediaMetadata,
        val streams: List<MediaStream>
    ) : ExtractionResult()

    data class NoFormatsFound(
        val reason: String = "No playable formats or streams found for this url"
    ) : ExtractionResult()

    data class LoginRequired(
        val message: String
    ) : ExtractionResult()

    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : ExtractionResult()
}

interface MediaExtractor {
    fun canHandle(url: String): Boolean
    suspend fun extract(url: String): ExtractionResult
}
