package com.example.vip

import com.example.core.vip.download.VIPDownloadEngine
import com.example.core.vip.download.VIPDownloadStatus
import com.example.core.vip.extraction.VIPMediaMetadata
import com.example.core.vip.extraction.VIPMediaStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class VIPDownloadEngineTest {

    private lateinit var tempDir: File
    private val sampleMetadata = VIPMediaMetadata(
        id = "ph_test_123",
        title = "Test Video Title",
        author = "Test Creator",
        thumbnailUrl = "https://example.com/thumb.jpg",
        sourceUrl = "https://www.pornhub.com/view_video.php?viewkey=ph_test_123",
        siteName = "Pornhub"
    )

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("vip_engine_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testSuccessfulByteForByteStreaming() = runBlocking {
        val totalSize = 64 * 1024
        val syntheticBytes = ByteArray(totalSize) { index -> (index % 251).toByte() }

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Length", totalSize.toString())
                    .header("Content-Type", "video/mp4")
                    .body(syntheticBytes.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/720p.mp4",
            quality = "720p",
            format = "MP4",
            contentLength = totalSize.toLong()
        )

        val startResult = engine.startDownload(sampleMetadata, stream)
        assertTrue(startResult.isSuccess)
        val taskId = startResult.getOrThrow()

        val completedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.COMPLETED }
        }

        assertNotNull(completedItem)
        assertEquals(VIPDownloadStatus.COMPLETED, completedItem!!.status)
        assertEquals(totalSize.toLong(), completedItem.downloadedBytes)
        assertEquals(totalSize.toLong(), completedItem.totalBytes)
        assertEquals(1.0f, completedItem.progress, 0.001f)

        val tempFile = completedItem.tempFile
        assertNotNull(tempFile)
        assertTrue(tempFile!!.exists())
        assertEquals(totalSize.toLong(), tempFile.length())

        val writtenBytes = tempFile.readBytes()
        assertTrue("Downloaded bytes must match synthetic stream byte-for-byte", syntheticBytes.contentEquals(writtenBytes))
    }

    @Test
    fun testIndeterminateContentLengthStreaming() = runBlocking {
        val payloadSize = 32 * 1024
        val syntheticBytes = ByteArray(payloadSize) { index -> (index % 127).toByte() }

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = object : ResponseBody() {
                    override fun contentType() = "video/mp4".toMediaType()
                    override fun contentLength() = -1L // Unknown / chunked
                    override fun source() = okio.Buffer().write(syntheticBytes)
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "1080p",
            url = "https://cv.phncdn.com/videos/test/1080p.mp4",
            quality = "1080p",
            format = "MP4",
            contentLength = null // Unknown
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        val completedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.COMPLETED }
        }

        assertNotNull(completedItem)
        assertEquals(VIPDownloadStatus.COMPLETED, completedItem!!.status)
        assertEquals(payloadSize.toLong(), completedItem.downloadedBytes)
        assertEquals(payloadSize.toLong(), completedItem.totalBytes)
        assertEquals(1.0f, completedItem.progress, 0.001f)

        val tempFile = completedItem.tempFile
        assertNotNull(tempFile)
        assertTrue(tempFile!!.exists())
        assertEquals(payloadSize.toLong(), tempFile.length())
    }

    @Test
    fun testHttpErrorRejectionAndCleanup() = runBlocking {
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
        val stream = VIPMediaStream(
            id = "480p",
            url = "https://cv.phncdn.com/videos/test/480p.mp4",
            quality = "480p",
            format = "MP4"
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        val failedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.FAILED }
        }

        assertNotNull(failedItem)
        assertEquals(VIPDownloadStatus.FAILED, failedItem!!.status)
        assertTrue(failedItem.errorMessage?.contains("403") == true)

        val expectedTempFile = File(tempDir, "vip_dl_${taskId}.tmp")
        assertFalse("Temporary file must be deleted upon HTTP error", expectedTempFile.exists())
    }

    @Test
    fun testForbiddenContentTypeHtmlRejectionAndCleanup() = runBlocking {
        val htmlCaptcha = "<html><body><h1>Cloudflare Verification Required</h1></body></html>"
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/html; charset=UTF-8")
                    .body(htmlCaptcha.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/captcha.mp4",
            quality = "720p",
            format = "MP4"
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        val failedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.FAILED }
        }

        assertNotNull(failedItem)
        assertEquals(VIPDownloadStatus.FAILED, failedItem!!.status)
        assertTrue("Error message must indicate invalid content type", failedItem.errorMessage?.contains("Invalid media content type") == true)

        val expectedTempFile = File(tempDir, "vip_dl_${taskId}.tmp")
        assertFalse("Temporary file must be cleaned up on invalid content type", expectedTempFile.exists())
    }

    @Test
    fun testEmptyPayloadRejectionAndCleanup() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .body("".toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/empty.mp4",
            quality = "720p",
            format = "MP4"
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        val failedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.FAILED }
        }

        assertNotNull(failedItem)
        assertEquals(VIPDownloadStatus.FAILED, failedItem!!.status)
        assertTrue(failedItem.errorMessage?.contains("Empty media payload") == true)

        val expectedTempFile = File(tempDir, "vip_dl_${taskId}.tmp")
        assertFalse("Temporary file must be deleted when payload is empty", expectedTempFile.exists())
    }

    @Test
    fun testCooperativeCancellationDeletesTempFile() = runBlocking {
        val readStartedLatch = CountDownLatch(1)
        val cancelTriggerLatch = CountDownLatch(1)

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val blockingStream = object : InputStream() {
                    private var hasReadFirstByte = false
                    override fun read(): Int {
                        if (!hasReadFirstByte) {
                            hasReadFirstByte = true
                            readStartedLatch.countDown()
                            cancelTriggerLatch.await(5, TimeUnit.SECONDS)
                            return 42
                        }
                        return -1
                    }
                }

                val body = object : ResponseBody() {
                    override fun contentType() = "video/mp4".toMediaType()
                    override fun contentLength() = 1000L
                    override fun source() = blockingStream.source().buffer()
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/cancel_test.mp4",
            quality = "720p",
            format = "MP4"
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        assertTrue(readStartedLatch.await(2, TimeUnit.SECONDS))

        engine.cancelDownload(taskId)
        cancelTriggerLatch.countDown()

        val cancelledItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.CANCELLED }
        }

        assertNotNull(cancelledItem)
        assertEquals(VIPDownloadStatus.CANCELLED, cancelledItem!!.status)

        val expectedTempFile = File(tempDir, "vip_dl_${taskId}.tmp")
        assertFalse("Temporary file must be deleted immediately upon cancellation", expectedTempFile.exists())
    }

    @Test
    fun testPreventDuplicateActiveDownload() = runBlocking {
        val streamUrl = "https://cv.phncdn.com/videos/test/dup.mp4"
        val hangLatch = CountDownLatch(1)

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                hangLatch.await(2, TimeUnit.SECONDS)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("test".toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = streamUrl,
            quality = "720p",
            format = "MP4"
        )

        val firstResult = engine.startDownload(sampleMetadata, stream)
        assertTrue(firstResult.isSuccess)
        val firstTaskId = firstResult.getOrThrow()

        val duplicateResult = engine.startDownload(sampleMetadata, stream)
        assertTrue("Duplicate download attempt must fail", duplicateResult.isFailure)
        assertTrue(duplicateResult.exceptionOrNull() is IllegalStateException)
        assertTrue(duplicateResult.exceptionOrNull()!!.message!!.contains("Download already active"))

        engine.cancelDownload(firstTaskId)
        hangLatch.countDown()
    }

    @Test
    fun testInvalidStreamUrlRejected() {
        val engine = VIPDownloadEngine(downloadDir = tempDir)

        val blankStream = VIPMediaStream(id = "1", url = "   ", quality = "720p", format = "MP4")
        val blankResult = engine.startDownload(sampleMetadata, blankStream)
        assertTrue(blankResult.isFailure)
        assertTrue(blankResult.exceptionOrNull() is IllegalArgumentException)

        val ftpStream = VIPMediaStream(id = "2", url = "ftp://example.com/video.mp4", quality = "720p", format = "MP4")
        val ftpResult = engine.startDownload(sampleMetadata, ftpStream)
        assertTrue(ftpResult.isFailure)
        assertTrue(ftpResult.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testSiteSpecificHeadersForwarded() = runBlocking {
        val capturedRequest = AtomicReference<Request>()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedRequest.set(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .body("data".toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/headers.mp4",
            quality = "720p",
            format = "MP4",
            headers = mapOf(
                "Referer" to "https://www.pornhub.com/view_video.php?viewkey=ph_test",
                "X-VIP-Custom" to "HeaderValue123"
            )
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.COMPLETED }
        }

        val sentRequest = capturedRequest.get()
        assertNotNull(sentRequest)
        assertEquals("https://www.pornhub.com/view_video.php?viewkey=ph_test", sentRequest.header("Referer"))
        assertEquals("HeaderValue123", sentRequest.header("X-VIP-Custom"))
        assertNotNull(sentRequest.header("User-Agent"))
    }

    @Test
    fun testUrlSanitizationStripsTokensAndQueries() {
        val rawUrl = "https://cv.phncdn.com/videos/2026/01/video.mp4?token=secret12345&expire=1710000000&sig=abcdef"
        val sanitized = VIPDownloadEngine.sanitizeUrl(rawUrl)

        assertEquals("https://cv.phncdn.com/videos/2026/01/video.mp4", sanitized)
        assertFalse(sanitized.contains("token"))
        assertFalse(sanitized.contains("secret12345"))
        assertFalse(sanitized.contains("sig"))
    }

    // =========================================================================
    // Phase 3A.2 Security, Concurrency, and Integrity Tests
    // =========================================================================

    @Test
    fun testSameHostRedirectPreservesHeaders() = runBlocking {
        val capturedRequests = CopyOnWriteArrayList<Request>()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                capturedRequests.add(request)

                if (request.url.encodedPath.contains("initial")) {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://cv.phncdn.com/videos/test/final.mp4")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "video/mp4")
                        .body("video-payload".toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/initial.mp4",
            quality = "720p",
            format = "MP4",
            headers = mapOf(
                "Cookie" to "session=abc123",
                "Referer" to "https://www.pornhub.com/",
                "X-Custom-Site" to "CustomValue"
            )
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.COMPLETED }
        }

        assertEquals(2, capturedRequests.size)
        val secondRequest = capturedRequests[1]
        assertEquals("session=abc123", secondRequest.header("Cookie"))
        assertEquals("https://www.pornhub.com/", secondRequest.header("Referer"))
        assertEquals("CustomValue", secondRequest.header("X-Custom-Site"))
    }

    @Test
    fun testCrossHostRedirectStripsSensitiveHeaders() = runBlocking {
        val capturedRequests = CopyOnWriteArrayList<Request>()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                capturedRequests.add(request)

                if (request.url.host == "pornhub.com") {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://external-cdn.example.com/videos/media.mp4")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "video/mp4")
                        .body("video-payload".toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://pornhub.com/stream/initial.mp4",
            quality = "720p",
            format = "MP4",
            headers = mapOf(
                "Cookie" to "secret_token=9999",
                "Referer" to "https://pornhub.com/watch",
                "X-Custom-Auth" to "supersecret",
                "User-Agent" to "CustomUserAgent/1.0"
            )
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.COMPLETED }
        }

        assertEquals(2, capturedRequests.size)
        val crossHostRequest = capturedRequests[1]
        assertEquals("external-cdn.example.com", crossHostRequest.url.host)
        // Sensitive and site-specific headers must be stripped
        assertNull("Cookie must be stripped on cross-host redirect", crossHostRequest.header("Cookie"))
        assertNull("Referer must be stripped on cross-host redirect", crossHostRequest.header("Referer"))
        assertNull("Custom auth header must be stripped on cross-host redirect", crossHostRequest.header("X-Custom-Auth"))
        // Safe generic headers are preserved
        assertEquals("CustomUserAgent/1.0", crossHostRequest.header("User-Agent"))
    }

    @Test
    fun testHttpsToHttpDowngradeRejected() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "http://insecure.example.com/downgrade.mp4")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://secure.example.com/stream.mp4",
            quality = "720p",
            format = "MP4"
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        val failedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.FAILED }
        }

        assertNotNull(failedItem)
        assertEquals(VIPDownloadStatus.FAILED, failedItem!!.status)
        assertTrue(failedItem.errorMessage?.contains("HTTPS-to-HTTP redirect downgrade rejected") == true)
    }

    @Test
    fun testRedirectLoopAndExcessiveRedirectsFailSafely() = runBlocking {
        var callCount = 0
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount++
                // Cycle between url-a and url-b
                val nextUrl = if (chain.request().url.toString().contains("url-a")) {
                    "https://cv.phncdn.com/url-b"
                } else {
                    "https://cv.phncdn.com/url-a"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", nextUrl)
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/url-a",
            quality = "720p",
            format = "MP4"
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        val failedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.FAILED }
        }

        assertNotNull(failedItem)
        assertEquals(VIPDownloadStatus.FAILED, failedItem!!.status)
        assertTrue(
            "Must fail with loop detection or excessive redirects",
            failedItem.errorMessage?.contains("Redirect loop detected") == true ||
                    failedItem.errorMessage?.contains("Too many redirects") == true
        )
    }

    @Test
    fun testIntermediateResponsesAreClosed() = runBlocking {
        val closedIntermediates = AtomicInteger(0)

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath.contains("step1")) {
                    val body = object : ResponseBody() {
                        override fun contentType() = "text/plain".toMediaType()
                        override fun contentLength() = 0L
                        override fun source() = okio.Buffer()
                        override fun close() {
                            closedIntermediates.incrementAndGet()
                            super.close()
                        }
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://cv.phncdn.com/videos/step2.mp4")
                        .body(body)
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "video/mp4")
                        .body("final".toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/step1.mp4",
            quality = "720p",
            format = "MP4"
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.COMPLETED }
        }

        assertTrue("Intermediate redirect response body must be closed", closedIntermediates.get() >= 1)
    }

    @Test
    fun testSimultaneousDuplicateCallsAtomic() = runBlocking {
        val concurrency = 10
        val hangLatch = CountDownLatch(1)

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                hangLatch.await(3, TimeUnit.SECONDS)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .body("data".toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/concurrent_atomic.mp4",
            quality = "720p",
            format = "MP4"
        )

        val startBarrier = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Result<String>>()

        // Launch 10 simultaneous coroutines attempting to start the exact same stream
        val deferreds = List(concurrency) {
            async(Dispatchers.IO) {
                startBarrier.await()
                engine.startDownload(sampleMetadata, stream)
            }
        }

        startBarrier.countDown()
        val allResults = deferreds.awaitAll()

        val successes = allResults.filter { it.isSuccess }
        val failures = allResults.filter { it.isFailure }

        assertEquals("Exactly one simultaneous request must succeed", 1, successes.size)
        assertEquals("All other concurrent requests must fail atomically", concurrency - 1, failures.size)

        failures.forEach {
            assertTrue(it.exceptionOrNull() is IllegalStateException)
            assertTrue(it.exceptionOrNull()!!.message!!.contains("Download already active"))
        }

        // Release network and clean up
        engine.cancelDownload(successes.first().getOrThrow())
        hangLatch.countDown()
    }

    @Test
    fun testTruncatedDownloadFailsAndDeletesTempFile() = runBlocking {
        val declaredLength = 50000L
        val actualBytes = ByteArray(25000) { 1 } // Only sends 25000 bytes

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = object : ResponseBody() {
                    override fun contentType() = "video/mp4".toMediaType()
                    override fun contentLength() = declaredLength
                    override fun source() = okio.Buffer().write(actualBytes)
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Length", declaredLength.toString())
                    .body(body)
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/truncated.mp4",
            quality = "720p",
            format = "MP4"
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        val failedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.FAILED }
        }

        assertNotNull(failedItem)
        assertEquals(VIPDownloadStatus.FAILED, failedItem!!.status)
        assertTrue("Error message must state truncation", failedItem.errorMessage?.contains("Download truncated") == true)

        val expectedTempFile = File(tempDir, "vip_dl_${taskId}.tmp")
        assertFalse("Truncated download temporary file must be deleted", expectedTempFile.exists())
    }

    @Test
    fun testMismatchedExtractorEstimateDoesNotRejectValidServerStream() = runBlocking {
        val serverBytes = ByteArray(40000) { 2 }

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Length", "40000")
                    .body(serverBytes.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        // Extractor estimated 60000, but server returned 40000
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/estimate_mismatch.mp4",
            quality = "720p",
            format = "MP4",
            contentLength = 60000L
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        val completedItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.status == VIPDownloadStatus.COMPLETED }
        }

        assertNotNull(completedItem)
        assertEquals(VIPDownloadStatus.COMPLETED, completedItem!!.status)
        assertEquals(40000L, completedItem.downloadedBytes)
        assertEquals(40000L, completedItem.totalBytes)
        assertEquals(1.0f, completedItem.progress, 0.001f)
    }

    @Test
    fun testCancellationNearCompletionCannotBeOverwrittenByCompleted() = runBlocking {
        // Stress-test cancellation at the tail of a download
        val finishLatch = CountDownLatch(1)
        val payload = ByteArray(1024) { 42 }

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val stream = object : InputStream() {
                    private var index = 0
                    override fun read(): Int {
                        if (index < payload.size) {
                            return payload[index++].toInt() and 0xFF
                        }
                        // At EOF, pause slightly to simulate race
                        finishLatch.await(500, TimeUnit.MILLISECONDS)
                        return -1
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .body(stream.source().buffer().let {
                        object : ResponseBody() {
                            override fun contentType() = "video/mp4".toMediaType()
                            override fun contentLength() = payload.size.toLong()
                            override fun source() = it
                        }
                    })
                    .build()
            }
            .build()

        val engine = VIPDownloadEngine(downloadDir = tempDir, client = mockClient)
        val stream = VIPMediaStream(
            id = "720p",
            url = "https://cv.phncdn.com/videos/test/race_test.mp4",
            quality = "720p",
            format = "MP4"
        )

        val taskId = engine.startDownload(sampleMetadata, stream).getOrThrow()

        // Give it 50ms to read bytes, then cancel before it unblocks finishLatch
        Thread.sleep(50)
        engine.cancelDownload(taskId)
        finishLatch.countDown()

        val finalItem = withTimeout(5000) {
            engine.observeDownload(taskId).first { it?.isTerminal == true }
        }

        assertNotNull(finalItem)
        // If cancelled, it must stay CANCELLED and never become COMPLETED
        assertEquals(VIPDownloadStatus.CANCELLED, finalItem!!.status)
        val tempFile = File(tempDir, "vip_dl_${taskId}.tmp")
        assertFalse("Cancelled download must have its temp file deleted", tempFile.exists())
    }
}
