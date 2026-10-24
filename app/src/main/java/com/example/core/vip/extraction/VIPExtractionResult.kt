package com.example.core.vip.extraction

data class VIPMediaMetadata(
    val id: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String?,
    val sourceUrl: String,
    val siteName: String,
    val durationSeconds: Long? = null,
    val description: String? = null
)

data class VIPMediaStream(
    val id: String,
    val url: String,
    val quality: String,
    val format: String = "MP4",
    val isVideo: Boolean = true,
    val isAudio: Boolean = true,
    val contentLength: Long? = null,
    val headers: Map<String, String> = emptyMap()
)

sealed class VIPExtractionResult {
    data class Success(
        val metadata: VIPMediaMetadata,
        val streams: List<VIPMediaStream>
    ) : VIPExtractionResult()

    data class NoFormatsFound(
        val reason: String
    ) : VIPExtractionResult()

    data class AccessRestricted(
        val reason: String
    ) : VIPExtractionResult()

    data class ChallengeDetected(
        val challengeType: String,
        val message: String
    ) : VIPExtractionResult()

    data class HttpError(
        val code: Int,
        val message: String
    ) : VIPExtractionResult()

    data class ParseError(
        val message: String,
        val throwable: Throwable? = null
    ) : VIPExtractionResult()

    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : VIPExtractionResult()
}
