package com.example.core.vip.download

import java.io.File

/**
 * Status of an isolated VIP media download task.
 */
enum class VIPDownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Observable model representing the live state of a VIP media download.
 *
 * @property id Unique identifier for the download task.
 * @property mediaTitle Clean human-readable title of the media.
 * @property author Creator or channel name.
 * @property quality Video resolution or stream quality label (e.g., "1080p", "720p").
 * @property format Media container or codec format (e.g., "MP4").
 * @property streamUrl Direct media stream URL being downloaded.
 * @property tempFile In-progress or completed temporary file on disk.
 * @property downloadedBytes Total bytes written to disk so far.
 * @property totalBytes Expected size in bytes from Content-Length, or -1 if unknown.
 * @property progress Float between 0.0f and 1.0f, or -1f if total size is unknown.
 * @property speedBytesPerSec Estimated instantaneous download speed in bytes/second.
 * @property status Current lifecycle status of the download task.
 * @property errorMessage Human-readable, token-sanitized failure description if failed.
 */
data class VIPDownloadItem(
    val id: String,
    val mediaTitle: String,
    val author: String,
    val quality: String,
    val format: String,
    val streamUrl: String,
    val tempFile: File? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val progress: Float = -1f,
    val speedBytesPerSec: Long = 0L,
    val status: VIPDownloadStatus = VIPDownloadStatus.QUEUED,
    val errorMessage: String? = null
) {
    /** True if the total size is unknown, indicating an indeterminate progress bar. */
    val isIndeterminate: Boolean get() = totalBytes <= 0L

    /** True if the task has reached a terminal state and will not change. */
    val isTerminal: Boolean
        get() = status == VIPDownloadStatus.COMPLETED ||
                status == VIPDownloadStatus.FAILED ||
                status == VIPDownloadStatus.CANCELLED

    /** True if the download is actively reading bytes from the network. */
    val isActive: Boolean
        get() = status == VIPDownloadStatus.QUEUED || status == VIPDownloadStatus.DOWNLOADING
}
