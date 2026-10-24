package com.example.vip

import com.example.core.vip.extraction.VIPExtractionManager
import com.example.core.vip.extraction.VIPExtractionResult
import com.example.core.vip.extraction.sites.PornhubExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PornhubExtractorTest {

    private lateinit var extractor: PornhubExtractor
    private lateinit var manager: VIPExtractionManager

    @Before
    fun setUp() {
        extractor = PornhubExtractor()
        manager = VIPExtractionManager(listOf(extractor))
    }

    @Test
    fun testValidPornhubUrlRecognition() {
        assertTrue(extractor.canHandle("https://www.pornhub.com/view_video.php?viewkey=ph60abc12345"))
        assertTrue(extractor.canHandle("https://m.pornhub.com/view_video.php?viewkey=ph60abc12345&pkey=test"))
        assertTrue(extractor.canHandle("https://pornhub.org/view_video.php?viewkey=ph60abc12345"))
        assertTrue(extractor.canHandle("https://www.pornhubpremium.com/view_video.php?viewkey=ph60abc12345"))
        assertTrue(extractor.canHandle("https://www.pornhub.com/embed/ph60abc12345"))
        assertTrue(extractor.canHandle("https://phub.me/ph60abc12345"))
        // Language subdomains
        assertTrue(extractor.canHandle("https://de.pornhub.com/view_video.php?viewkey=ph60abc12345"))
        assertTrue(extractor.canHandle("https://fr.pornhub.com/view_video.php?viewkey=ph60abc12345"))
        assertTrue(extractor.canHandle("https://es.pornhub.com/view_video.php?viewkey=ph60abc12345"))
        assertTrue(extractor.canHandle("https://rt.pornhub.com/view_video.php?viewkey=ph60abc12345"))

        assertEquals("ph60abc12345", extractor.extractViewKey("https://www.pornhub.com/view_video.php?viewkey=ph60abc12345"))
        assertEquals("ph60abc12345", extractor.extractViewKey("https://m.pornhub.com/view_video.php?viewkey=ph60abc12345&sub=1"))
        assertEquals("ph60abc12345", extractor.extractViewKey("https://pornhub.com/embed/ph60abc12345"))
        assertEquals("ph60abc12345", extractor.extractViewKey("https://phub.me/ph60abc12345"))
        assertEquals("ph60abc12345", extractor.extractViewKey("https://de.pornhub.com/view_video.php?viewkey=ph60abc12345"))
    }

    @Test
    fun testInvalidUrlsRejected() {
        assertFalse(extractor.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(extractor.canHandle("https://www.instagram.com/reel/C-0xyz123/"))
        assertFalse(extractor.canHandle("https://www.xvideos.com/video12345/sample"))
        assertFalse(extractor.canHandle("https://www.pornhub.com/"))
        assertFalse(extractor.canHandle("https://www.pornhub.com/categories"))
        assertFalse(extractor.canHandle("https://www.pornhub.com/channels"))
        assertFalse(extractor.canHandle("https://google.com"))
        assertFalse(extractor.canHandle(""))
        assertNull(extractor.extractViewKey("https://www.pornhub.com/categories"))

        // Deceptive lookalike hosts must be rejected
        assertFalse(extractor.canHandle("https://pornhub.com.evil.example/view_video.php?viewkey=ph60abc12345"))
        assertFalse(extractor.canHandle("https://evilpornhub.com/view_video.php?viewkey=ph60abc12345"))
        assertFalse(extractor.canHandle("https://pornhub.com@evil.example/view_video.php?viewkey=ph60abc12345"))
        assertFalse(extractor.canHandle("https://notpornhub.com/view_video.php?viewkey=ph60abc12345"))
        assertFalse(extractor.canHandle("https://pornhub.com.attacker.com/view_video.php?viewkey=ph60abc12345"))

        // Unexpected ports must be rejected
        assertFalse(extractor.canHandle("https://pornhub.com:8080/view_video.php?viewkey=ph60abc12345"))
        assertFalse(extractor.canHandle("https://www.pornhub.com:8443/view_video.php?viewkey=ph60abc12345"))

        // Invalid schemes
        assertFalse(extractor.canHandle("ftp://www.pornhub.com/view_video.php?viewkey=ph60abc12345"))
        assertFalse(extractor.canHandle("javascript:alert(1)"))
    }

    @Test
    fun testExtractionManagerRouting() {
        assertTrue(manager.canHandle("https://www.pornhub.com/view_video.php?viewkey=ph60abc12345"))
        assertFalse(manager.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("Pornhub", manager.getExtractor("https://www.pornhub.com/view_video.php?viewkey=ph60abc12345")?.siteName)
        assertNull(manager.getExtractor("https://example.com/unsupported"))
    }

    @Test
    fun testParseSyntheticFixtureWithMultipleQualities() {
        val syntheticHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Sample Test Video Title - Pornhub.com" />
                <meta property="og:image" content="https://ci.phncdn.com/videos/test_thumb.jpg" />
                <meta property="video:duration" content="360" />
            </head>
            <body>
                <span class="usernameBadgesWrapper"><a href="/model/teststar">StarCreator</a></span>
                <script>
                    var flashvars_12345 = {
                        "video_title": "Sample Test Video Title",
                        "mediaDefinitions": [
                            {"defaultQuality": false, "format": "mp4", "videoUrl": "https:\/\/cv.phncdn.com\/videos\/1080p.mp4?token=123", "quality": "1080"},
                            {"defaultQuality": true, "format": "mp4", "videoUrl": "https:\/\/cv.phncdn.com\/videos\/720p.mp4?token=123", "quality": "720"},
                            {"defaultQuality": false, "format": "mp4", "videoUrl": "https:\/\/cv.phncdn.com\/videos\/480p.mp4?token=123", "quality": "480"}
                        ]
                    };
                </script>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseHtml(syntheticHtml, "ph60test", "https://www.pornhub.com/view_video.php?viewkey=ph60test")

        assertTrue("Expected Success result", result is VIPExtractionResult.Success)
        val success = result as VIPExtractionResult.Success

        assertEquals("ph60test", success.metadata.id)
        assertEquals("Sample Test Video Title", success.metadata.title)
        assertEquals("StarCreator", success.metadata.author)
        assertEquals("https://ci.phncdn.com/videos/test_thumb.jpg", success.metadata.thumbnailUrl)
        assertEquals(360L, success.metadata.durationSeconds)
        assertEquals("Pornhub", success.metadata.siteName)

        assertEquals(3, success.streams.size)
        // Verify streams sorted descending by resolution
        assertEquals("1080p (MP4)", success.streams[0].quality)
        assertEquals("https://cv.phncdn.com/videos/1080p.mp4?token=123", success.streams[0].url)
        assertEquals("720p (MP4)", success.streams[1].quality)
        assertEquals("https://cv.phncdn.com/videos/720p.mp4?token=123", success.streams[1].url)
        assertEquals("480p (MP4)", success.streams[2].quality)

        // Verify CDN headers present
        assertEquals("https://www.pornhub.com/", success.streams[0].headers["Referer"])
        assertEquals("https://www.pornhub.com", success.streams[0].headers["Origin"])
        assertNotNull(success.streams[0].headers["User-Agent"])
    }

    @Test
    fun testParseSyntheticFixtureWithoutHighQualityVariants() {
        val lowQualityOnlyHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Classic Archive Clip" />
            </head>
            <body>
                <script>
                    var playerConfig = {
                        "mediaDefinitions": [
                            {"defaultQuality": true, "format": "mp4", "videoUrl": "https://cv.phncdn.com/videos/360p.mp4", "quality": "360"},
                            {"defaultQuality": false, "format": "mp4", "videoUrl": "https://cv.phncdn.com/videos/240p.mp4", "quality": "240"}
                        ]
                    };
                </script>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseHtml(lowQualityOnlyHtml, "ph_low_res", "https://www.pornhub.com/view_video.php?viewkey=ph_low_res")

        assertTrue(result is VIPExtractionResult.Success)
        val success = result as VIPExtractionResult.Success

        assertEquals(2, success.streams.size)
        assertEquals("360p (MP4)", success.streams[0].quality)
        assertEquals("240p (MP4)", success.streams[1].quality)
        // High quality (720p/1080p) must not be invented
        assertFalse(success.streams.any { it.quality.contains("720") || it.quality.contains("1080") })
    }

    @Test
    fun testCloudflareChallengeDetection() {
        val challengeHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Just a moment...</title>
            </head>
            <body>
                <div id="cf-browser-verification">Please stand by, while we are checking your browser...</div>
                <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></script>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseHtml(challengeHtml, "ph_challenge", "https://www.pornhub.com/view_video.php?viewkey=ph_challenge")

        assertTrue("Expected ChallengeDetected result", result is VIPExtractionResult.ChallengeDetected)
        val challenge = result as VIPExtractionResult.ChallengeDetected
        assertEquals("CLOUDFLARE_CHALLENGE", challenge.challengeType)
        assertTrue(challenge.message.contains("challenge detected"))
    }

    @Test
    fun testAgeVerificationOrGeoRestrictionDetection() {
        val restrictedHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Access Restricted</title>
            </head>
            <body>
                <div class="legal-age-gate">
                    <h1>Age-Verification Required</h1>
                    <p>Access to this website is restricted in your area without identity verification.</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseHtml(restrictedHtml, "ph_age", "https://www.pornhub.com/view_video.php?viewkey=ph_age")

        assertTrue(result is VIPExtractionResult.AccessRestricted)
        val restricted = result as VIPExtractionResult.AccessRestricted
        assertTrue(restricted.reason.contains("regional or age-verification gate"))
    }

    @Test
    fun testComplianceAgeVerificationLinksDoNotFalsePositiveWhenStreamsExist() {
        val htmlWithComplianceFooter = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Valid Video With Legal Compliance Links" />
                <meta property="og:image" content="https://ci.phncdn.com/videos/thumb.jpg" />
                <meta property="video:duration" content="180" />
            </head>
            <body>
                <script>
                    var flashvars_789 = {
                        "video_title": "Valid Video With Legal Compliance Links",
                        "mediaDefinitions": [
                            {"defaultQuality": true, "format": "mp4", "videoUrl": "https://cv.phncdn.com/videos/720p.mp4", "quality": "720"}
                        ]
                    };
                </script>
                <footer>
                    <div class="compliance-links">
                        <a href="/information#compliance">2257 Record-Keeping Requirements</a>
                        <a href="/compliance/age-verification">Age-Verification Compliance</a>
                        <a href="/terms">Terms of Service</a>
                    </div>
                </footer>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseHtml(
            htmlWithComplianceFooter,
            "ph_compliance_test",
            "https://www.pornhub.com/view_video.php?viewkey=ph_compliance_test"
        )

        assertTrue("Expected Success even though page contains footer age-verification links", result is VIPExtractionResult.Success)
        val success = result as VIPExtractionResult.Success
        assertEquals("Valid Video With Legal Compliance Links", success.metadata.title)
        assertEquals(1, success.streams.size)
        assertEquals("720p (MP4)", success.streams[0].quality)
    }

    @Test
    fun testPremiumOnlyDetection() {
        val premiumHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Premium Exclusive</title></head>
            <body>
                <div class="premiumNotice">
                    <h2>This video is for premium members only</h2>
                    <a href="/premium">Join Premium to watch</a>
                </div>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseHtml(premiumHtml, "ph_prem", "https://www.pornhub.com/view_video.php?viewkey=ph_prem")

        assertTrue(result is VIPExtractionResult.AccessRestricted)
        val restricted = result as VIPExtractionResult.AccessRestricted
        assertTrue(restricted.reason.contains("premium"))
    }

    @Test
    fun testRemovedOrUnavailableVideoDetection() {
        val removedHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Video Unavailable</title></head>
            <body>
                <div class="video-notice">This video has been removed for terms of service violations.</div>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseHtml(removedHtml, "ph_removed", "https://www.pornhub.com/view_video.php?viewkey=ph_removed")

        assertTrue(result is VIPExtractionResult.NoFormatsFound)
        val noFormats = result as VIPExtractionResult.NoFormatsFound
        assertTrue(noFormats.reason.contains("removed or is unavailable"))
    }

    @Test
    fun testAdaptiveOnlyMediaReturnsTypedFailure() {
        val adaptiveOnlyHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Adaptive Stream Test</title></head>
            <body>
                <script>
                    var config = {
                        "mediaDefinitions": [
                            {"defaultQuality": false, "format": "hls", "videoUrl": "https://cv.phncdn.com/videos/master.m3u8", "quality": "1080"}
                        ]
                    };
                </script>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseHtml(adaptiveOnlyHtml, "ph_adaptive", "https://www.pornhub.com/view_video.php?viewkey=ph_adaptive")

        assertTrue(result is VIPExtractionResult.NoFormatsFound)
        val failure = result as VIPExtractionResult.NoFormatsFound
        assertTrue(failure.reason.contains("Unsupported adaptive-only media"))
    }

    @Test
    fun testMalformedHtmlHandling() {
        val malformedHtml = "<html><head></head><body>Broken unclosed tags and random text</body>"

        val result = extractor.parseHtml(malformedHtml, "ph_broken", "https://www.pornhub.com/view_video.php?viewkey=ph_broken")

        assertTrue(result is VIPExtractionResult.NoFormatsFound)
        val failure = result as VIPExtractionResult.NoFormatsFound
        assertTrue(failure.reason.contains("No playable progressive MP4 video streams found"))
    }

    @Test
    fun testHttpErrorHandlingWithMockClient() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("Video Not Found".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val testExtractor = PornhubExtractor(mockClient)
        val result = testExtractor.extract("https://www.pornhub.com/view_video.php?viewkey=ph_not_found_123")

        assertTrue(result is VIPExtractionResult.HttpError)
        val httpError = result as VIPExtractionResult.HttpError
        assertEquals(404, httpError.code)
    }

    @Test
    fun testSuccessfulHttpExtractionWithMockClient() = runBlocking {
        val mockPayload = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Mock Successful Clip" />
            </head>
            <body>
                <script>
                    var flashvars_99 = {
                        "mediaDefinitions": [
                            {"format": "mp4", "videoUrl": "https://cv.phncdn.com/mock.mp4", "quality": "720"}
                        ]
                    };
                </script>
            </body>
            </html>
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockPayload.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val testExtractor = PornhubExtractor(mockClient)
        val result = testExtractor.extract("https://www.pornhub.com/view_video.php?viewkey=ph_success_99")

        assertTrue(result is VIPExtractionResult.Success)
        val success = result as VIPExtractionResult.Success
        assertEquals("Mock Successful Clip", success.metadata.title)
        assertEquals(1, success.streams.size)
        assertEquals("720p (MP4)", success.streams[0].quality)
    }

    @Test
    fun testOversizedResponseHeaderBlocked() = runBlocking {
        // Mock response with Content-Length header exceeding 2 MiB (e.g., 3 MiB)
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val oversizedContent = "A".repeat(1024)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Length", (3 * 1024 * 1024).toString())
                    .body(oversizedContent.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val testExtractor = PornhubExtractor(mockClient)
        val result = testExtractor.extract("https://www.pornhub.com/view_video.php?viewkey=ph_oversized_header")

        assertTrue("Expected Error for oversized Content-Length", result is VIPExtractionResult.Error)
        val error = result as VIPExtractionResult.Error
        assertTrue(error.message.contains("exceeds maximum allowed size of 2 MiB"))
    }

    @Test
    fun testOversizedStreamingResponseBodyBlocked() = runBlocking {
        // Mock streaming response exceeding 2 MiB without Content-Length
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val chunk = "0123456789ABCDEF".repeat(64) // 1024 bytes
                val bigPayload = chunk.repeat(2500) // ~2.5 MiB
                val streamingBody = object : okhttp3.ResponseBody() {
                    override fun contentType() = "text/html".toMediaType()
                    override fun contentLength() = -1L // Unknown Content-Length, forces streaming read
                    override fun source() = okio.Buffer().writeUtf8(bigPayload)
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(streamingBody)
                    .build()
            }
            .build()

        val testExtractor = PornhubExtractor(mockClient)
        val result = testExtractor.extract("https://www.pornhub.com/view_video.php?viewkey=ph_oversized_stream")

        assertTrue("Expected Error for oversized stream", result is VIPExtractionResult.Error)
        val error = result as VIPExtractionResult.Error
        assertTrue(error.message.contains("exceeds maximum allowed size of 2 MiB"))
    }

    @Test
    fun testRedirectToUnapprovedHostBlocked() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(PornhubExtractor.SafeRedirectInterceptor())
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://evil.example.com/phishing")
                    .body("".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val testExtractor = PornhubExtractor(mockClient)
        val result = testExtractor.extract("https://www.pornhub.com/view_video.php?viewkey=ph_unapproved_redirect")

        assertTrue("Expected Error for redirect to unapproved host", result is VIPExtractionResult.Error)
        val error = result as VIPExtractionResult.Error
        assertTrue(error.message.contains("Redirect to unapproved host blocked"))
    }

    @Test
    fun testRedirectWithinApprovedHostAllowed() = runBlocking {
        var callCount = 0
        val mockPayload = """
            <!DOCTYPE html><html><head><meta property="og:title" content="Redirect Target Video"/></head>
            <body><script>var flashvars_1 = {"mediaDefinitions": [{"format": "mp4", "videoUrl": "https://cv.phncdn.com/target.mp4", "quality": "480"}]};</script></body></html>
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(PornhubExtractor.SafeRedirectInterceptor())
            .addInterceptor { chain ->
                callCount++
                if (callCount == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://de.pornhub.com/view_video.php?viewkey=ph_approved_redirect")
                        .body("".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(mockPayload.toResponseBody("text/html".toMediaType()))
                        .build()
                }
            }
            .build()

        val testExtractor = PornhubExtractor(mockClient)
        val result = testExtractor.extract("https://www.pornhub.com/view_video.php?viewkey=ph_approved_redirect")

        assertTrue("Expected Success after following approved redirect", result is VIPExtractionResult.Success)
        val success = result as VIPExtractionResult.Success
        assertEquals("Redirect Target Video", success.metadata.title)
        assertEquals(2, callCount)
    }

    @Test
    fun testGuaranteedResponseClosureOnSuccessAndFailure() = runBlocking {
        var closedCount = 0
        val mockPayload = """
            <!DOCTYPE html><html><head><meta property="og:title" content="Closure Test"/></head>
            <body><script>var flashvars_1 = {"mediaDefinitions": [{"format": "mp4", "videoUrl": "https://cv.phncdn.com/close.mp4", "quality": "720"}]};</script></body></html>
        """.trimIndent()

        // 1. Test closure on 200 Success
        val successClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = mockPayload.toResponseBody("text/html".toMediaType())
                val trackingBody = object : okhttp3.ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = body.source()
                    override fun close() {
                        closedCount++
                        body.close()
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(trackingBody)
                    .build()
            }
            .build()

        val extractorSuccess = PornhubExtractor(successClient)
        extractorSuccess.extract("https://www.pornhub.com/view_video.php?viewkey=ph_close_success")
        assertEquals("Response body must be closed on success", 1, closedCount)

        // 2. Test closure on HTTP 500 Error
        val errorClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = "Internal Server Error".toResponseBody("text/html".toMediaType())
                val trackingBody = object : okhttp3.ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = body.source()
                    override fun close() {
                        closedCount++
                        body.close()
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Server Error")
                    .body(trackingBody)
                    .build()
            }
            .build()

        val extractorError = PornhubExtractor(errorClient)
        extractorError.extract("https://www.pornhub.com/view_video.php?viewkey=ph_close_error")
        assertEquals("Response body must be closed on HTTP error", 2, closedCount)
    }

    @Test
    fun testApiAndManifestEndpointsNotClassifiedAsProgressiveMp4() {
        val apiEndpointsHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Video Page</title></head>
            <body>
                <script>
                    var flashvars_12345 = {
                        "video_title": "Api Endpoint Video",
                        "mediaDefinitions": [
                            {"defaultQuality": false, "format": "mp4", "videoUrl": "https://www.pornhub.com/video/get_media?id=123", "quality": "720"},
                            {"defaultQuality": false, "format": "mp4", "videoUrl": "https://www.pornhub.com/api/playback.json", "quality": "480"}
                        ]
                    };
                </script>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseHtml(apiEndpointsHtml, "ph_api_test", "https://www.pornhub.com/view_video.php?viewkey=ph_api_test")
        assertTrue("API endpoints returning JSON must not be offered as playable MP4 streams", result is VIPExtractionResult.NoFormatsFound)
    }

    @Test
    fun testIsApiOrManifestUrlHelper() {
        assertTrue(PornhubExtractor.isApiOrManifestUrl("https://www.pornhub.com/video/get_media?id=456"))
        assertTrue(PornhubExtractor.isApiOrManifestUrl("https://pornhub.com/get_media?token=abc"))
        assertTrue(PornhubExtractor.isApiOrManifestUrl("https://pornhub.com/api/v1/source"))
        assertTrue(PornhubExtractor.isApiOrManifestUrl("https://pornhub.com/playback.json"))
        assertTrue(PornhubExtractor.isApiOrManifestUrl("https://pornhub.com/media?format=json"))

        assertFalse(PornhubExtractor.isApiOrManifestUrl("https://cv.phncdn.com/videos/2026/720p.mp4?token=123"))
        assertFalse(PornhubExtractor.isApiOrManifestUrl("https://cv.phncdn.com/videos/test/stream.webm"))
    }
}
