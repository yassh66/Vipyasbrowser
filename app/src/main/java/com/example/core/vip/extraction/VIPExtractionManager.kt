package com.example.core.vip.extraction

import com.example.core.vip.extraction.sites.PornhubExtractor

class VIPExtractionManager(
    private val extractors: List<VIPMediaExtractor> = listOf(
        PornhubExtractor()
    )
) {
    fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.isNotBlank() -> "https://$trimmed"
            else -> ""
        }
    }

    fun canHandle(rawUrl: String): Boolean {
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isBlank()) return false
        return extractors.any { it.canHandle(normalized) }
    }

    fun getExtractor(rawUrl: String): VIPMediaExtractor? {
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isBlank()) return null
        return extractors.firstOrNull { it.canHandle(normalized) }
    }

    suspend fun extractMedia(rawUrl: String): VIPExtractionResult {
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isBlank()) {
            return VIPExtractionResult.Error("URL cannot be empty")
        }

        val extractor = getExtractor(normalized)
            ?: return VIPExtractionResult.NoFormatsFound(
                "No supported VIP extractor found for URL: $normalized"
            )

        return extractor.extract(normalized)
    }
}
