package com.example

import com.example.core.media.download.DownloadItem
import com.example.core.media.download.DownloadStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DownloadManagementTest {

    @Test
    fun testActiveDownloadProtectionDuringBulkDeletion() {
        val completedItem = DownloadItem(
            id = "dl_completed_1",
            title = "Completed Video",
            author = "Creator",
            url = "https://example.com/video.mp4",
            format = "MP4",
            quality = "1080p",
            destinationPath = "Movies/YAS Browser/video.mp4",
            status = DownloadStatus.COMPLETED
        )

        val activeItem = DownloadItem(
            id = "dl_active_2",
            title = "In Progress Reel",
            author = "Creator",
            url = "https://example.com/reel.mp4",
            format = "MP4",
            quality = "720p",
            destinationPath = "Movies/YAS Browser/reel.mp4",
            status = DownloadStatus.DOWNLOADING
        )

        val failedItem = DownloadItem(
            id = "dl_failed_3",
            title = "Failed Download",
            author = "Creator",
            url = "https://example.com/clip.mp4",
            format = "MP4",
            quality = "720p",
            destinationPath = "Movies/YAS Browser/clip.mp4",
            status = DownloadStatus.FAILED
        )

        val currentList = listOf(completedItem, activeItem, failedItem)

        // Simulate bulk deletion logic with active protection
        val selectedIds = setOf("dl_completed_1", "dl_active_2", "dl_failed_3")
        val deleted = mutableListOf<String>()
        val protected = mutableListOf<String>()

        selectedIds.forEach { id ->
            val item = currentList.firstOrNull { it.id == id }
            if (item != null) {
                if (item.status == DownloadStatus.DOWNLOADING) {
                    protected.add(id)
                } else {
                    deleted.add(id)
                }
            }
        }

        assertEquals(2, deleted.size)
        assertTrue(deleted.contains("dl_completed_1"))
        assertTrue(deleted.contains("dl_failed_3"))

        assertEquals(1, protected.size)
        assertTrue(protected.contains("dl_active_2"))
    }

    @Test
    fun testSingleItemDeletionDistinction() {
        val item = DownloadItem(
            id = "dl_test_100",
            title = "Test Music",
            author = "Musician",
            url = "https://example.com/song.mp3",
            format = "MP3",
            quality = "320 kbps",
            destinationPath = "Music/YAS Browser/song.mp3",
            contentUriString = "content://media/external/audio/media/100",
            status = DownloadStatus.COMPLETED
        )

        // Case A: Remove from list only (deleteFileFromStorage = false)
        val deleteStorageOptionA = false
        val uriDeletedA = if (deleteStorageOptionA) item.contentUriString else null
        assertNull("When deleteFileFromStorage is false, MediaStore URI should NOT be deleted", uriDeletedA)

        // Case B: Delete file from storage (deleteFileFromStorage = true)
        val deleteStorageOptionB = true
        val uriDeletedB = if (deleteStorageOptionB) item.contentUriString else null
        assertNotNull("When deleteFileFromStorage is true, MediaStore URI MUST be targeted for deletion", uriDeletedB)
        assertEquals("content://media/external/audio/media/100", uriDeletedB)
    }

    @Test
    fun testSelectionStateToggling() {
        val items = listOf(
            DownloadItem(id = "1", title = "V1", author = "A", url = "", format = "", quality = "", destinationPath = "", isSelected = false),
            DownloadItem(id = "2", title = "V2", author = "A", url = "", format = "", quality = "", destinationPath = "", isSelected = false)
        )

        // Select All
        val allSelected = items.map { it.copy(isSelected = true) }
        assertTrue(allSelected.all { it.isSelected })

        // Deselect All
        val noneSelected = allSelected.map { it.copy(isSelected = false) }
        assertTrue(noneSelected.none { it.isSelected })

        // Toggle Single
        val toggled = noneSelected.map { if (it.id == "1") it.copy(isSelected = !it.isSelected) else it }
        assertTrue(toggled.first { it.id == "1" }.isSelected)
        assertFalse(toggled.first { it.id == "2" }.isSelected)
    }

    @Test
    fun testDownloadHistoryJsonSerializationAndRecovery() {
        val originalItem = DownloadItem(
            id = "uuid_save_test",
            title = "Saved Video",
            author = "Author 1",
            url = "https://instagram.com/reel/123",
            format = "MP4",
            quality = "1080p (MP4)",
            destinationPath = "Movies/YAS Browser/saved.mp4",
            contentUriString = "content://media/external/video/media/42",
            totalBytes = 25000000L,
            downloadedBytes = 25000000L,
            progress = 1.0f,
            status = DownloadStatus.COMPLETED,
            isAudio = false
        )

        // Serialize to JSON
        val obj = JSONObject().apply {
            put("id", originalItem.id)
            put("title", originalItem.title)
            put("author", originalItem.author)
            put("url", originalItem.url)
            put("format", originalItem.format)
            put("quality", originalItem.quality)
            put("destinationPath", originalItem.destinationPath)
            put("contentUriString", originalItem.contentUriString ?: "")
            put("totalBytes", originalItem.totalBytes)
            put("downloadedBytes", originalItem.downloadedBytes)
            put("progress", originalItem.progress.toDouble())
            put("status", originalItem.status.name)
            put("isAudio", originalItem.isAudio)
        }
        val jsonArray = JSONArray().apply { put(obj) }
        val serializedString = jsonArray.toString()

        // Deserialize back
        val parsedArray = JSONArray(serializedString)
        assertEquals(1, parsedArray.length())
        val recoveredObj = parsedArray.getJSONObject(0)

        assertEquals("uuid_save_test", recoveredObj.getString("id"))
        assertEquals("Saved Video", recoveredObj.getString("title"))
        assertEquals("1080p (MP4)", recoveredObj.getString("quality"))
        assertEquals("content://media/external/video/media/42", recoveredObj.getString("contentUriString"))
        assertEquals(25000000L, recoveredObj.getLong("totalBytes"))
        assertEquals("COMPLETED", recoveredObj.getString("status"))
        assertFalse(recoveredObj.getBoolean("isAudio"))
    }
}
