package com.example.vip

import com.example.Screen
import com.example.core.vip.download.MediaStoreWriter
import com.example.core.vip.download.VIPDownloadEngine
import com.example.core.vip.download.VIPDownloadItem
import com.example.core.vip.download.VIPDownloadStatus
import com.example.core.vip.download.VIPMediaPublisher
import com.example.core.vip.download.VIPPublishResult
import com.example.core.vip.extraction.VIPExtractionManager
import com.example.core.vip.extraction.VIPMediaMetadata
import com.example.core.vip.extraction.VIPMediaStream
import com.example.features.vip.VIPDownloadFormatting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class VIPToolsUiTest {

    private lateinit var tempDir: File
    private lateinit var fakeWriter: FakeMediaStoreWriter

    private val sampleMetadata = VIPMediaMetadata(
        id = "ph654321",
        title = "Test Public Media Title",
        author = "VerifiedAuthor",
        thumbnailUrl = "https://ci.phncdn.com/test_thumb.jpg",
        sourceUrl = "https://www.pornhub.com/view_video.php?viewkey=ph654321",
        siteName = "Pornhub",
        durationSeconds = 245
    )

    private val sampleStream = VIPMediaStream(
        id = "stream-1080p",
        url = "https://cv.phncdn.com/videos/test_1080p.mp4",
        quality = "1080p",
        format = "MP4",
        isVideo = true,
        isAudio = true,
        contentLength = 32768L
    )

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("vip_ui_test").toFile()
        fakeWriter = FakeMediaStoreWriter()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testScreenRoutingVIPToolsInstantiation() {
        val screenDefault = Screen.VIPTools()
        assertEquals("", screenDefault.targetUrl)

        val targetUrl = "https://www.pornhub.com/view_video.php?viewkey=ph123456"
        val screenWithUrl = Screen.VIPTools(targetUrl)
        assertEquals(targetUrl, screenWithUrl.targetUrl)
    }

    @Test
    fun testVIPExtractionManagerNormalizesAndRoutesSupportedUrls() {
        val manager = VIPExtractionManager()
        val rawInput = "  www.pornhub.com/view_video.php?viewkey=ph123456  "
        val normalized = manager.normalizeUrl(rawInput)
        assertEquals("https://www.pornhub.com/view_video.php?viewkey=ph123456", normalized)
        assertTrue(manager.canHandle(normalized))

        val extractor = manager.getExtractor(normalized)
        assertNotNull(extractor)
    }

    @Test
    fun testVIPExtractionManagerRejectsUnrelatedUrls() {
        val manager = VIPExtractionManager()
        assertFalse(manager.canHandle("https://www.youtube.com/watch?v=12345"))
        assertFalse(manager.canHandle("https://www.instagram.com/reel/12345/"))
        assertFalse(manager.canHandle("https://example.com/video.mp4"))
    }

    @Test
    fun testVIPMediaStreamMetadataIntegrity() {
        assertEquals("ph654321", sampleMetadata.id)
        assertEquals("Test Public Media Title", sampleMetadata.title)
        assertEquals("VerifiedAuthor", sampleMetadata.author)
        assertEquals(245L, sampleMetadata.durationSeconds)

        assertEquals("1080p", sampleStream.quality)
        assertEquals("MP4", sampleStream.format)
        assertTrue(sampleStream.isVideo)
        assertTrue(sampleStream.isAudio)
        assertEquals(32768L, sampleStream.contentLength)
    }

    @Test
    fun testFormattingUtilitiesFormatBytesAndSpeed() {
        assertEquals("0 B", VIPDownloadFormatting.formatBytes(0L))
        assertEquals("0 B", VIPDownloadFormatting.formatBytes(-1L))
        assertEquals("500 B", VIPDownloadFormatting.formatBytes(500L))
        assertEquals("1.0 KB", VIPDownloadFormatting.formatBytes(1024L))
        assertEquals("15.5 MB", VIPDownloadFormatting.formatBytes((15.5 * 1024 * 1024).toLong()))
        assertEquals("1.2 GB", VIPDownloadFormatting.formatBytes((1.2 * 1024 * 1024 * 1024).toLong()))

        assertEquals("0 KB/s", VIPDownloadFormatting.formatSpeed(0L))
        assertEquals("512 KB/s", VIPDownloadFormatting.formatSpeed(512 * 1024L))
        assertEquals("2.5 MB/s", VIPDownloadFormatting.formatSpeed((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun testDownloadTriggerStartsEngineAndGeneratesRealDownloadItem() {
        val mockClient = OkHttpClient.Builder().build()
        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)

        val result = engine.startDownload(sampleMetadata, sampleStream)
        assertTrue(result.isSuccess)
        val taskId = result.getOrThrow()
        assertTrue(taskId.isNotBlank())

        val item = engine.getDownload(taskId)
        assertNotNull(item)
        assertEquals(sampleMetadata.title, item?.mediaTitle)
        assertEquals(sampleStream.quality, item?.quality)
        assertEquals(sampleStream.format, item?.format)
        assertEquals(sampleStream.url, item?.streamUrl)
        assertEquals(32768L, item?.totalBytes)
    }

    @Test
    fun testDuplicateDownloadAttemptReturnsFailureOrPreventsDuplicateTask() {
        val mockClient = OkHttpClient.Builder().build()
        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)

        val firstStart = engine.startDownload(sampleMetadata, sampleStream)
        assertTrue(firstStart.isSuccess)

        val secondStart = engine.startDownload(sampleMetadata, sampleStream)
        assertTrue(secondStart.isFailure)
        assertTrue(secondStart.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun testObserveDownloadStreamsProgressAndReachesCompleted() = runBlocking {
        val payload = ByteArray(32768) { (it % 100).toByte() }
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val startResult = engine.startDownload(sampleMetadata, sampleStream)
        assertTrue(startResult.isSuccess)
        val taskId = startResult.getOrThrow()

        val completedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.COMPLETED }
        }

        assertNotNull(completedItem)
        assertEquals(VIPDownloadStatus.COMPLETED, completedItem?.status)
        assertEquals(32768L, completedItem?.downloadedBytes)
        assertEquals(1.0f, completedItem?.progress)
        assertTrue(completedItem?.tempFile?.exists() == true)
        assertEquals(32768L, completedItem?.tempFile?.length())
    }

    @Test
    fun testCompletedDownloadPublishesToMediaStoreViaPublisher() = runBlocking {
        val payload = ByteArray(16384) { (it % 200).toByte() }
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val publisher = VIPMediaPublisher(fakeWriter)

        val startResult = engine.startDownload(sampleMetadata, sampleStream.copy(contentLength = 16384L))
        val taskId = startResult.getOrThrow()

        val finishedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.COMPLETED }
        }

        assertNotNull(finishedItem)
        val publishResult = publisher.publishMedia(finishedItem!!, deleteSourceOnSuccess = true)
        assertTrue("Expected Success, got: $publishResult", publishResult is VIPPublishResult.Success)

        val success = publishResult as VIPPublishResult.Success
        assertEquals(16384L, success.sizeBytes)
        assertEquals("video/mp4", success.mimeType)
        assertTrue(fakeWriter.inserted.isNotEmpty())
        assertTrue(fakeWriter.finalized.isNotEmpty())
        assertEquals(16384, fakeWriter.outputStreams[success.contentUriString]?.size())
        assertFalse("Source temp file should be cleaned up on publish success", finishedItem!!.tempFile?.exists() == true)
    }

    @Test
    fun testPublisherRejectsNonCompletedOrTruncatedDownload() {
        val publisher = VIPMediaPublisher(fakeWriter)
        val queuedItem = VIPDownloadItem(
            id = "test-queued",
            mediaTitle = "Title",
            author = "Author",
            quality = "720p",
            format = "MP4",
            streamUrl = "https://example.com/video.mp4",
            status = VIPDownloadStatus.QUEUED
        )

        val result = publisher.publishMedia(queuedItem)
        assertTrue(result is VIPPublishResult.Failure)
        assertTrue((result as VIPPublishResult.Failure).reason.contains("non-completed"))
    }

    @Test
    fun testDownloadCancellationTransitionsStatusToCancelled() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Simulate slow blocking stream
                Thread.sleep(1000)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(1024).toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val taskId = engine.startDownload(sampleMetadata, sampleStream).getOrThrow()

        engine.cancelDownload(taskId)
        val item = engine.getDownload(taskId)
        assertEquals(VIPDownloadStatus.CANCELLED, item?.status)
    }

    @Test
    fun testSanitizedErrorDisplayOnEngineFailure() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .body("Access Denied".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val taskId = engine.startDownload(sampleMetadata, sampleStream).getOrThrow()

        val failedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.FAILED }
        }

        assertNotNull(failedItem)
        assertEquals(VIPDownloadStatus.FAILED, failedItem?.status)
        assertNotNull(failedItem?.errorMessage)
        assertFalse(failedItem?.errorMessage!!.contains("token="))
    }

    private class FakeMediaStoreWriter : MediaStoreWriter {
        val inserted = mutableListOf<String>()
        val finalized = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val outputStreams = mutableMapOf<String, ByteArrayOutputStream>()

        override fun insertPending(displayName: String, mimeType: String, relativePath: String): String? {
            val uri = "content://media/external/video/media/${inserted.size + 1}"
            inserted.add(uri)
            return uri
        }

        override fun openOutputStream(uriString: String): OutputStream? {
            val stream = ByteArrayOutputStream()
            outputStreams[uriString] = stream
            return stream
        }

        override fun finalizePending(uriString: String): Boolean {
            finalized.add(uriString)
            return true
        }

        override fun delete(uriString: String): Boolean {
            deleted.add(uriString)
            return true
        }
    }
}
