package com.example.core.vip.extraction

interface VIPMediaExtractor {
    val siteName: String
    fun canHandle(url: String): Boolean
    suspend fun extract(url: String): VIPExtractionResult
}
