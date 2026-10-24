package com.example.vip

import com.example.core.vip.download.MediaStoreWriter
import com.example.core.vip.download.VIPDownloadItem
import com.example.core.vip.download.VIPDownloadStatus
import com.example.core.vip.download.VIPMediaPublisher
import com.example.core.vip.download.VIPMediaValidator
import com.example.core.vip.download.VIPPublishResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

class VIPMediaPublisherTest {

    private lateinit var tempDir: File

    private class FakeMediaStoreWriter : MediaStoreWriter {
        val insertedValues = mutableMapOf<String, String>()
        val writtenBytes = ByteArrayOutputStream()
        var shouldFailInsert = false
        var shouldFailOpenStream = false
        var shouldFailFinalize = false
        var finalizedUris = mutableSetOf<String>()
        var deletedUris = mutableSetOf<String>()
        var nextUriString = "content://media/external/video/media/101"

        override fun insertPending(displayName: String, mimeType: String, relativePath: String): String? {
            if (shouldFailInsert) return null
            insertedValues["displayName"] = displayName
            insertedValues["mimeType"] = mimeType
            insertedValues["relativePath"] = relativePath
            return nextUriString
        }

        override fun openOutputStream(uriString: String): OutputStream? {
            if (shouldFailOpenStream) throw IOException("Simulated disk full or I/O error")
            return writtenBytes
        }

        override fun finalizePending(uriString: String): Boolean {
            if (shouldFailFinalize) return false
            finalizedUris.add(uriString)
            return true
        }

        override fun delete(uriString: String): Boolean {
            deletedUris.add(uriString)
            return true
        }
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("vip_publisher_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testSanitizeDisplayNameRemovesIllegalChars() {
        val raw = "Test: Title / With? * <Illegal> | Characters! \n\r\t"
        val clean = VIPMediaPublisher.sanitizeDisplayName(raw, "mp4")

        assertEquals("Test_ Title _ With_ _ _Illegal_ _ Characters!.mp4", clean)
        assertFalse(clean.contains(":"))
        assertFalse(clean.contains("/"))
        assertFalse(clean.contains("?"))
        assertFalse(clean.contains("*"))
        assertFalse(clean.contains("<"))
        assertFalse(clean.contains(">"))
        assertFalse(clean.contains("|"))
    }

    @Test
    fun testSanitizeDisplayNameDefaultsEmptyToVipMedia() {
        val blank = "   ::: /// ???   "
        val clean = VIPMediaPublisher.sanitizeDisplayName(blank, "webm")

        assertEquals("VIP_Media.webm", clean)
    }

    @Test
    fun testSanitizeDisplayNameTruncatesExcessiveLength() {
        val veryLong = "A".repeat(120)
        val clean = VIPMediaPublisher.sanitizeDisplayName(veryLong, "mp4")

        assertTrue(clean.startsWith("A".repeat(80)))
        assertEquals(80 + ".mp4".length, clean.length)
    }

    @Test
    fun testResolveMimeAndExtension() {
        assertEquals(Pair("video/mp4", "mp4"), VIPMediaPublisher.resolveMimeAndExtension("MP4"))
        assertEquals(Pair("video/mp4", "mp4"), VIPMediaPublisher.resolveMimeAndExtension("mp4"))
        assertEquals(Pair("video/webm", "webm"), VIPMediaPublisher.resolveMimeAndExtension("WEBM"))
        assertEquals(Pair("video/x-matroska", "mkv"), VIPMediaPublisher.resolveMimeAndExtension("MKV"))
        assertEquals(Pair("video/3gpp", "3gp"), VIPMediaPublisher.resolveMimeAndExtension("3GP"))
        assertEquals(Pair("video/mp4", "mp4"), VIPMediaPublisher.resolveMimeAndExtension("UNKNOWN_FORMAT"))
    }

    @Test
    fun testIsHtmlOrTextErrorDetectsHtml() {
        val htmlFile = File(tempDir, "error.tmp").apply {
            writeText("<!DOCTYPE html><html><head><title>Cloudflare Challenge</title></head><body>Verification</body></html>")
        }
        assertTrue(VIPMediaPublisher.isHtmlOrTextError(htmlFile))

        val htmlDocFile = File(tempDir, "error2.tmp").apply {
            writeText("<HTML><BODY>Access Denied</BODY></HTML>")
        }
        assertTrue(VIPMediaPublisher.isHtmlOrTextError(htmlDocFile))

        val binaryFile = File(tempDir, "video.tmp").apply {
            writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)) // ftyp header
        }
        assertFalse(VIPMediaPublisher.isHtmlOrTextError(binaryFile))
    }

    @Test
    fun testPublishRejectsNonCompletedItem() {
        val fakeWriter = FakeMediaStoreWriter()
        val publisher = VIPMediaPublisher(fakeWriter)

        val downloadingItem = VIPDownloadItem(
            id = "t1",
            mediaTitle = "Downloading Video",
            author = "Author",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            status = VIPDownloadStatus.DOWNLOADING
        )

        val result = publisher.publishMedia(downloadingItem)
        assertTrue(result is VIPPublishResult.Failure)
        assertTrue((result as VIPPublishResult.Failure).reason.contains("non-completed status"))
    }

    @Test
    fun testPublishRejectsMissingSourceFile() {
        val fakeWriter = FakeMediaStoreWriter()
        val publisher = VIPMediaPublisher(fakeWriter)

        val missingFileItem = VIPDownloadItem(
            id = "t2",
            mediaTitle = "Missing Video",
            author = "Author",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = File(tempDir, "non_existent.tmp"),
            status = VIPDownloadStatus.COMPLETED
        )

        val result = publisher.publishMedia(missingFileItem)
        assertTrue(result is VIPPublishResult.Failure)
        assertTrue((result as VIPPublishResult.Failure).reason.contains("does not exist"))
    }

    @Test
    fun testPublishRejectsEmptySourceFile() {
        val fakeWriter = FakeMediaStoreWriter()
        val publisher = VIPMediaPublisher(fakeWriter)

        val emptyFile = File(tempDir, "empty.tmp").apply { createNewFile() }
        val emptyItem = VIPDownloadItem(
            id = "t3",
            mediaTitle = "Empty Video",
            author = "Author",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = emptyFile,
            status = VIPDownloadStatus.COMPLETED
        )

        val result = publisher.publishMedia(emptyItem)
        assertTrue(result is VIPPublishResult.Failure)
        assertTrue((result as VIPPublishResult.Failure).reason.contains("is empty (0 bytes)"))
    }

    @Test
    fun testPublishRejectsTruncatedSourceFile() {
        val fakeWriter = FakeMediaStoreWriter()
        val publisher = VIPMediaPublisher(fakeWriter)

        val partialFile = File(tempDir, "partial.tmp").apply { writeBytes(ByteArray(2000) { 1 }) }
        val partialItem = VIPDownloadItem(
            id = "t4",
            mediaTitle = "Partial Video",
            author = "Author",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = partialFile,
            downloadedBytes = 2000L,
            totalBytes = 5000L, // Expected 5000, but only has 2000
            status = VIPDownloadStatus.COMPLETED
        )

        val result = publisher.publishMedia(partialItem)
        assertTrue(result is VIPPublishResult.Failure)
        assertTrue((result as VIPPublishResult.Failure).reason.contains("is incomplete"))
    }

    @Test
    fun testPublishRejectsHtmlSourceFile() {
        val fakeWriter = FakeMediaStoreWriter()
        val publisher = VIPMediaPublisher(fakeWriter)

        val htmlFile = File(tempDir, "challenge.tmp").apply {
            writeText("<!DOCTYPE html><html><body>Access Denied - Bot Detected</body></html>")
        }
        val htmlItem = VIPDownloadItem(
            id = "t5",
            mediaTitle = "HTML Challenge Video",
            author = "Author",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = htmlFile,
            status = VIPDownloadStatus.COMPLETED
        )

        val result = publisher.publishMedia(htmlItem)
        assertTrue(result is VIPPublishResult.Failure)
        assertTrue((result as VIPPublishResult.Failure).reason.contains("HTML or error markup"))
    }

    @Test
    fun testSuccessfulPublishCopiesBytesAndFinalizesPending() {
        val fakeWriter = FakeMediaStoreWriter()
        val publisher = VIPMediaPublisher(fakeWriter)

        val syntheticPayload = VIPMediaValidator.createSyntheticMp4(16 * 1024)
        val sourceFile = File(tempDir, "valid_video.tmp").apply { writeBytes(syntheticPayload) }

        val validItem = VIPDownloadItem(
            id = "t6",
            mediaTitle = "High Quality Video",
            author = "Creator",
            quality = "1080p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = sourceFile,
            downloadedBytes = syntheticPayload.size.toLong(),
            totalBytes = syntheticPayload.size.toLong(),
            status = VIPDownloadStatus.COMPLETED
        )

        val result = publisher.publishMedia(validItem, deleteSourceOnSuccess = true)
        assertTrue("Publish must succeed", result is VIPPublishResult.Success)
        val success = result as VIPPublishResult.Success

        assertEquals(fakeWriter.nextUriString, success.contentUriString)
        assertEquals("High Quality Video.mp4", success.displayName)
        assertEquals("video/mp4", success.mimeType)
        assertEquals(syntheticPayload.size.toLong(), success.sizeBytes)

        // Verify bytes copied byte-for-byte
        assertTrue(syntheticPayload.contentEquals(fakeWriter.writtenBytes.toByteArray()))

        // Verify MediaStore finalization
        assertTrue("Entry must be finalized", fakeWriter.finalizedUris.contains(fakeWriter.nextUriString))

        // Verify temporary source file was deleted after confirmed publish
        assertFalse("Source file must be deleted when deleteSourceOnSuccess is true", sourceFile.exists())

        // Verify ownership tracking
        assertTrue(publisher.isOwnedPublishedUri(success.contentUriString))
    }

    @Test
    fun testPublishPreservesSourceFileWhenConfigured() {
        val fakeWriter = FakeMediaStoreWriter()
        val publisher = VIPMediaPublisher(fakeWriter)

        val syntheticPayload = VIPMediaValidator.createSyntheticMp4(1024)
        val sourceFile = File(tempDir, "preserve_video.tmp").apply { writeBytes(syntheticPayload) }

        val validItem = VIPDownloadItem(
            id = "t7",
            mediaTitle = "Preserved Video",
            author = "Creator",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = sourceFile,
            status = VIPDownloadStatus.COMPLETED
        )

        val result = publisher.publishMedia(validItem, deleteSourceOnSuccess = false)
        assertTrue(result is VIPPublishResult.Success)

        // Source file must be retained
        assertTrue("Source file must NOT be deleted when deleteSourceOnSuccess is false", sourceFile.exists())
    }

    @Test
    fun testPublishFailureDuringCopyCleansUpMediaStoreEntryAndRetainsSource() {
        val fakeWriter = FakeMediaStoreWriter().apply { shouldFailOpenStream = true }
        val publisher = VIPMediaPublisher(fakeWriter)

        val payload = VIPMediaValidator.createSyntheticMp4(2048)
        val sourceFile = File(tempDir, "fail_copy.tmp").apply { writeBytes(payload) }

        val item = VIPDownloadItem(
            id = "t8",
            mediaTitle = "Failing Copy Video",
            author = "Creator",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = sourceFile,
            status = VIPDownloadStatus.COMPLETED
        )

        val result = publisher.publishMedia(item)
        assertTrue(result is VIPPublishResult.Failure)

        // Incomplete MediaStore row must be cleaned up
        assertTrue("Partial MediaStore entry must be deleted on copy failure", fakeWriter.deletedUris.contains(fakeWriter.nextUriString))

        // Source file must be preserved
        assertTrue("Source file must NOT be deleted if publication failed", sourceFile.exists())
    }

    @Test
    fun testPublishFailureDuringFinalizationCleansUpMediaStoreEntry() {
        val fakeWriter = FakeMediaStoreWriter().apply { shouldFailFinalize = true }
        val publisher = VIPMediaPublisher(fakeWriter)

        val payload = VIPMediaValidator.createSyntheticMp4(2048)
        val sourceFile = File(tempDir, "fail_finalize.tmp").apply { writeBytes(payload) }

        val item = VIPDownloadItem(
            id = "t9",
            mediaTitle = "Failing Finalize Video",
            author = "Creator",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = sourceFile,
            status = VIPDownloadStatus.COMPLETED
        )

        val result = publisher.publishMedia(item)
        assertTrue(result is VIPPublishResult.Failure)

        // Incomplete MediaStore row must be cleaned up
        assertTrue("Unfinalized MediaStore entry must be deleted", fakeWriter.deletedUris.contains(fakeWriter.nextUriString))
        assertTrue(sourceFile.exists())
    }

    @Test
    fun testDeletePublishedMediaOnlyAllowsOwnedUris() {
        val fakeWriter = FakeMediaStoreWriter()
        val publisher = VIPMediaPublisher(fakeWriter)

        val payload = VIPMediaValidator.createSyntheticMp4(512)
        val sourceFile = File(tempDir, "owned_test.tmp").apply { writeBytes(payload) }

        val item = VIPDownloadItem(
            id = "t10",
            mediaTitle = "Owned Video",
            author = "Creator",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = sourceFile,
            status = VIPDownloadStatus.COMPLETED
        )

        val publishResult = publisher.publishMedia(item, deleteSourceOnSuccess = false)
        assertTrue(publishResult is VIPPublishResult.Success)
        val ownedUriString = (publishResult as VIPPublishResult.Success).contentUriString

        val foreignUriString = "content://media/external/video/media/99999"

        // 1. Attempting to delete a foreign URI not published by this publisher MUST be refused
        val foreignDeleteResult = publisher.deletePublishedMedia(foreignUriString)
        assertFalse("Must refuse to delete foreign URI", foreignDeleteResult)
        assertFalse(fakeWriter.deletedUris.contains(foreignUriString))

        // 2. Deleting the owned published URI succeeds
        val ownedDeleteResult = publisher.deletePublishedMedia(ownedUriString)
        assertTrue("Must succeed deleting owned URI", ownedDeleteResult)
        assertTrue(fakeWriter.deletedUris.contains(ownedUriString))

        // 3. Second deletion attempt fails because it is no longer owned/tracked
        assertFalse(publisher.deletePublishedMedia(ownedUriString))
    }

    @Test
    fun testPublisherRejectsInvalidNonMediaContainerFile() {
        val fakeWriter = FakeMediaStoreWriter()
        val publisher = VIPMediaPublisher(fakeWriter)

        val randomGarbage = ByteArray(1024) { (it % 255).toByte() }
        val sourceFile = File(tempDir, "invalid_container.tmp").apply { writeBytes(randomGarbage) }

        val invalidItem = VIPDownloadItem(
            id = "t_invalid",
            mediaTitle = "Garbage Video",
            author = "Unknown",
            quality = "1080p",
            format = "MP4",
            streamUrl = "https://example.com/stream.mp4",
            tempFile = sourceFile,
            status = VIPDownloadStatus.COMPLETED
        )

        val result = publisher.publishMedia(invalidItem)
        assertTrue("Must reject invalid container", result is VIPPublishResult.Failure)
        val failure = result as VIPPublishResult.Failure
        assertTrue(failure.reason.contains("container signature"))
        assertTrue("No MediaStore row should be allocated", fakeWriter.insertedValues.isEmpty())
    }

    @Test
    fun testMediaValidatorRejectsJsonPayload() {
        val jsonFile = File(tempDir, "error.json").apply {
            writeText("{\"error\": \"Unauthorized\", \"message\": \"Access token expired\"}")
        }
        val result = VIPMediaValidator.validateMediaFile(jsonFile, "MP4")
        assertTrue(result is VIPMediaValidator.Result.Invalid)
        assertTrue((result as VIPMediaValidator.Result.Invalid).reason.contains("JSON"))
    }

    @Test
    fun testMediaValidatorRejectsHlsManifest() {
        val hlsFile = File(tempDir, "manifest.m3u8").apply {
            writeText("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nchunklist.m3u8")
        }
        val result = VIPMediaValidator.validateMediaFile(hlsFile, "MP4")
        assertTrue(result is VIPMediaValidator.Result.Invalid)
        assertTrue((result as VIPMediaValidator.Result.Invalid).reason.contains("HLS"))
    }

    @Test
    fun testMediaValidatorRejectsCorruptFtypBox() {
        val corruptBytes = VIPMediaValidator.createSyntheticMp4(1024)
        // Corrupt box size to 4 (smaller than minimal 8)
        corruptBytes[0] = 0; corruptBytes[1] = 0; corruptBytes[2] = 0; corruptBytes[3] = 4
        val corruptFile = File(tempDir, "corrupt.mp4").apply { writeBytes(corruptBytes) }

        val result = VIPMediaValidator.validateMediaFile(corruptFile, "MP4")
        assertTrue(result is VIPMediaValidator.Result.Invalid)
        assertTrue((result as VIPMediaValidator.Result.Invalid).reason.contains("Invalid MP4 ftyp box size"))
    }

    @Test
    fun testMediaValidatorAcceptsValidMp4AndWebmContainers() {
        val mp4Bytes = VIPMediaValidator.createSyntheticMp4(2048)
        val mp4File = File(tempDir, "valid.mp4").apply { writeBytes(mp4Bytes) }
        val mp4Result = VIPMediaValidator.validateMediaFile(mp4File, "MP4")
        assertTrue("Valid MP4 must pass validation", mp4Result is VIPMediaValidator.Result.Valid)

        val webmBytes = VIPMediaValidator.createSyntheticWebm(2048)
        val webmFile = File(tempDir, "valid.webm").apply { writeBytes(webmBytes) }
        val webmResult = VIPMediaValidator.validateMediaFile(webmFile, "WEBM")
        assertTrue("Valid WebM must pass validation", webmResult is VIPMediaValidator.Result.Valid)
    }
}
