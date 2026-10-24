package com.example.core.media

import android.content.Context
import com.example.core.media.extraction.ExtractionResult
import com.example.core.media.extraction.MediaExtractionManager
import com.example.core.media.extraction.MediaMetadata
import com.example.core.media.extraction.MediaStream
import com.example.core.media.extraction.Platform

data class StreamMatrix(
    val videoStreams: List<MediaStream> = emptyList(),
    val audioStreams: List<MediaStream> = emptyList(),
    val adaptiveAudioStreams: List<MediaStream> = emptyList()
)

class MediaAnalyzer {
    private val manager: MediaExtractionManager

    constructor(manager: MediaExtractionManager) {
        this.manager = manager
    }

    constructor(context: Context? = null) {
        this.manager = MediaExtractionManager(context)
    }

    suspend fun analyzeUrl(url: String): ExtractionResult {
        return manager.extractMedia(url)
    }

    fun buildStreamMatrix(streams: List<MediaStream>): StreamMatrix {
        val videoStreams = streams.filter { it.isVideo || (!it.isAudio && !it.isVideo) }
        val audioStreams = streams.filter { it.isAudio }
        val adaptiveAudio = streams.filter { it.isAudioOnly }

        return StreamMatrix(
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            adaptiveAudioStreams = if (adaptiveAudio.isNotEmpty()) adaptiveAudio else audioStreams
        )
    }

    fun extractFileExtension(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', "")
        return if (ext.length in 2..5) ext.lowercase() else "mp4"
    }

    fun formatFileSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return "Unknown size"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> String.format(java.util.Locale.US, "%.0f KB", kb)
        }
    }
}
