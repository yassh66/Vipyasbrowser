package com.example.core.media.extraction

import android.content.Context

class MediaExtractionManager(
    context: Context? = null,
    private val extractors: List<MediaExtractor> = listOf(
        InstagramMediaExtractor(),
        InstagramStoryMediaExtractor(context),
        InstagramProfileMediaExtractor(context),
        YouTubeMediaExtractor(),
        DirectMediaExtractor()
    )
) {
    fun normalizeUrl(inputUrl: String): String {
        var clean = inputUrl.trim()
        if (clean.startsWith("@")) {
            clean = "https://www.instagram.com/${clean.removePrefix("@")}/"
        } else if (clean.startsWith("http://", ignoreCase = true)) {
            // Keep http or upgrade to https if appropriate
        } else if (!clean.startsWith("https://", ignoreCase = true)) {
            clean = "https://$clean"
        }
        return clean
    }

    fun detectPlatform(url: String): Platform {
        val lower = url.trim().lowercase()
        return when {
            lower.startsWith("@") || lower.contains("instagram.com") -> Platform.INSTAGRAM
            lower.contains("youtube.com") || lower.contains("youtu.be") -> Platform.YOUTUBE
            lower.contains("tiktok.com") -> Platform.TIKTOK
            lower.contains("twitter.com") || lower.contains("x.com") -> Platform.TWITTER
            lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".webm") || lower.endsWith(".m4a") -> Platform.DIRECT
            else -> Platform.UNKNOWN
        }
    }

    suspend fun extractMedia(rawUrl: String): ExtractionResult {
        val url = normalizeUrl(rawUrl)
        if (url.isBlank()) {
            return ExtractionResult.Error("URL cannot be empty")
        }

        val extractor = extractors.firstOrNull { it.canHandle(url) }
        return if (extractor != null) {
            extractor.extract(url)
        } else {
            // Fallback to direct extractor if no specialized extractor matched
            val direct = DirectMediaExtractor()
            if (direct.canHandle(url)) {
                direct.extract(url)
            } else {
                ExtractionResult.NoFormatsFound(
                    "No playable formats or streams found for this url. Supported platforms: Instagram (Reels, Posts, Stories, Profiles), YouTube, Direct MP4/MP3."
                )
            }
        }
    }
}
