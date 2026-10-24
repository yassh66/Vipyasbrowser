package com.example

import com.example.core.media.MediaAnalyzer
import com.example.core.media.extraction.ExtractionResult
import com.example.core.media.extraction.InstagramMediaExtractor
import com.example.core.media.extraction.InstagramProfileMediaExtractor
import com.example.core.media.extraction.InstagramStoryMediaExtractor
import com.example.core.media.extraction.MediaExtractionManager
import com.example.core.media.extraction.Platform
import com.example.core.media.extraction.instagram.InstagramSessionBridge
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InstagramStoryAndProfileTest {

    private lateinit var storyExtractor: InstagramStoryMediaExtractor
    private lateinit var profileExtractor: InstagramProfileMediaExtractor
    private lateinit var postReelExtractor: InstagramMediaExtractor
    private lateinit var manager: MediaExtractionManager

    @Before
    fun setUp() {
        storyExtractor = InstagramStoryMediaExtractor()
        profileExtractor = InstagramProfileMediaExtractor()
        postReelExtractor = InstagramMediaExtractor()
        manager = MediaExtractionManager()
    }

    private fun createMockClient(code: Int, body: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_2)
                    .code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun testStoryUrlRecognition() {
        assertTrue(storyExtractor.canHandle("https://www.instagram.com/stories/natgeo/"))
        assertTrue(storyExtractor.canHandle("https://instagram.com/stories/username/3456789012345/"))
        assertTrue(storyExtractor.canHandle("https://www.instagram.com/stories/highlights/17912345678/"))
        assertTrue(storyExtractor.canHandle("https://www.instagram.com/stories/@talebyosefi44/"))

        // Must NOT match posts or reels
        assertFalse(storyExtractor.canHandle("https://www.instagram.com/p/CIxm6P2A823/"))
        assertFalse(storyExtractor.canHandle("https://www.instagram.com/reel/C-0xyz123/"))
        assertFalse(storyExtractor.canHandle("https://www.instagram.com/cristiano/"))
        assertFalse(storyExtractor.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun testStoryUsernameAndIdExtraction() {
        val parsed1 = storyExtractor.extractUsernameAndStoryId("https://www.instagram.com/stories/natgeo/3123456789012345678/")
        assertNotNull(parsed1)
        assertEquals("natgeo", parsed1!!.first)
        assertEquals("3123456789012345678", parsed1.second)

        val parsed2 = storyExtractor.extractUsernameAndStoryId("https://instagram.com/stories/travel_diaries/?utm_source=ig_story_share")
        assertNotNull(parsed2)
        assertEquals("travel_diaries", parsed2!!.first)
        assertNull(parsed2.second)

        // Story with leading @ in URL
        val parsedAt = storyExtractor.extractUsernameAndStoryId("https://www.instagram.com/stories/@talebyosefi44/")
        assertNotNull(parsedAt)
        assertEquals("talebyosefi44", parsedAt!!.first)
        assertNull(parsedAt.second)

        // Story highlight
        val parsedHighlight = storyExtractor.extractUsernameAndStoryId("https://www.instagram.com/stories/highlights/17912345678/")
        assertNotNull(parsedHighlight)
        assertEquals("highlights", parsedHighlight!!.first)
        assertEquals("17912345678", parsedHighlight.second)
    }

    @Test
    fun testStoryRequiresAuthenticationWhenNoCookies() = runBlocking {
        // Without active session cookies, story extraction must report LoginRequired honestly
        val result = storyExtractor.extract("https://www.instagram.com/stories/test_user/123456789/")
        assertTrue("Extraction without cookies should require login", result is ExtractionResult.LoginRequired)
        val loginReq = result as ExtractionResult.LoginRequired
        assertTrue(loginReq.message.contains("login", ignoreCase = true))
    }

    @Test
    fun testProfileUrlRecognition() {
        assertTrue(profileExtractor.canHandle("https://www.instagram.com/cristiano/"))
        assertTrue(profileExtractor.canHandle("https://instagram.com/selenagomez"))
        assertTrue(profileExtractor.canHandle("https://www.instagram.com/natgeo/?hl=en"))
        assertTrue(profileExtractor.canHandle("https://www.instagram.com/@talebyosefi44/"))
        assertTrue(profileExtractor.canHandle("@talebyosefi44"))

        // Must NOT match system or media paths
        assertFalse(profileExtractor.canHandle("https://www.instagram.com/p/CIxm6P2A823/"))
        assertFalse(profileExtractor.canHandle("https://www.instagram.com/reel/C-0xyz123/"))
        assertFalse(profileExtractor.canHandle("https://www.instagram.com/reels/C-0xyz123/"))
        assertFalse(profileExtractor.canHandle("https://www.instagram.com/tv/B123_abc/"))
        assertFalse(profileExtractor.canHandle("https://www.instagram.com/stories/natgeo/"))
        assertFalse(profileExtractor.canHandle("https://www.instagram.com/explore/"))
        assertFalse(profileExtractor.canHandle("https://www.instagram.com/accounts/login/"))
        assertFalse(profileExtractor.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun testProfileUsernameExtraction() {
        assertEquals("cristiano", profileExtractor.extractUsername("https://www.instagram.com/cristiano/"))
        assertEquals("selenagomez", profileExtractor.extractUsername("https://instagram.com/selenagomez"))
        assertEquals("travel.world_99", profileExtractor.extractUsername("https://www.instagram.com/travel.world_99/?hl=en"))

        // Leading @ and query parameters from "Copy Profile" action
        assertEquals("talebyosefi44", profileExtractor.extractUsername("@talebyosefi44"))
        assertEquals("talebyosefi44", profileExtractor.extractUsername("https://www.instagram.com/talebyosefi44?igsh=MWQ1ZGUxMzBkMA=="))
        assertEquals("talebyosefi44", profileExtractor.extractUsername("https://instagram.com/@talebyosefi44/"))

        assertNull(profileExtractor.extractUsername("https://www.instagram.com/p/CIxm6P2A823/"))
        assertNull(profileExtractor.extractUsername("https://www.instagram.com/reel/C-0xyz123/"))
        assertNull(profileExtractor.extractUsername("https://www.instagram.com/stories/test/"))
    }

    @Test
    fun testTargetProfileIdentificationAndViewerIsolation() = runBlocking {
        // Target username is "target_photographer"
        val targetJson = JSONObject().apply {
            put("data", JSONObject().apply {
                put("user", JSONObject().apply {
                    put("username", "target_photographer")
                    put("full_name", "Target Photographer")
                    put("is_private", false)
                    put("hd_profile_pic_url_info", JSONObject().apply {
                        put("url", "https://scontent.cdninstagram.com/v/t51.2885-19/target_1080.jpg")
                        put("width", 1080)
                        put("height", 1080)
                    })
                })
            })
        }

        val mockClient = createMockClient(200, targetJson.toString())
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        val result = isolatedExtractor.extract("https://www.instagram.com/target_photographer/")

        assertTrue("Target profile should be successfully resolved", result is ExtractionResult.Success)
        val success = result as ExtractionResult.Success
        assertEquals("https://scontent.cdninstagram.com/v/t51.2885-19/target_1080.jpg", success.streams.first().url)
        assertEquals("Profile Picture (HD JPG)", success.streams.first().quality)
        assertTrue(success.metadata.title.contains("target_photographer"))
    }

    @Test
    fun testExplicitRejectionOfMismatchedViewerUser() = runBlocking {
        // If response returns a user whose username does not match the requested target, it must be rejected
        val mismatchedJson = JSONObject().apply {
            put("data", JSONObject().apply {
                put("user", JSONObject().apply {
                    put("username", "current_logged_in_viewer") // Different from requested target!
                    put("full_name", "Logged In Viewer")
                    put("hd_profile_pic_url_info", JSONObject().apply {
                        put("url", "https://scontent.cdninstagram.com/v/t51.2885-19/viewer_avatar.jpg")
                        put("width", 1080)
                        put("height", 1080)
                    })
                })
            })
        }

        val mockClient = createMockClient(200, mismatchedJson.toString())
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        val result = isolatedExtractor.extract("https://www.instagram.com/someone_else/")

        // Must reject mismatched viewer and never download viewer's avatar
        assertFalse("Mismatched viewer avatar must NOT be returned as success", result is ExtractionResult.Success)
    }

    @Test
    fun testGenuineResolutionSelectionDoesNotFalselyClaimHdForThumbnails() = runBlocking {
        // When only a thumbnail is genuinely available (e.g. 320x320), it should be labeled with genuine dimensions
        val thumbnailJson = JSONObject().apply {
            put("data", JSONObject().apply {
                put("user", JSONObject().apply {
                    put("username", "art_gallery")
                    put("full_name", "Art Gallery")
                    put("profile_pic_url", "https://scontent.cdninstagram.com/v/t51.2885-19/s320x320/art.jpg")
                })
            })
        }

        val mockClient = createMockClient(200, thumbnailJson.toString())
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        val result = isolatedExtractor.extract("https://www.instagram.com/art_gallery/")

        assertTrue(result is ExtractionResult.Success)
        val success = result as ExtractionResult.Success
        // Must report genuine resolution, NOT false "HD"
        assertEquals("Profile Picture (320x320 JPG)", success.streams.first().quality)
    }

    @Test
    fun testHttp429HandlingWithoutRapidRetries() = runBlocking {
        val mockClient = createMockClient(429, "{}")
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        val result = isolatedExtractor.extract("https://www.instagram.com/busy_user/")

        assertTrue(result is ExtractionResult.Error)
        val error = result as ExtractionResult.Error
        assertTrue("Error message should explicitly mention HTTP 429 rate-limiting", error.message.contains("429"))
    }

    @Test
    fun testPostAndReelExtractorIsNotAffected() {
        // Confirm original InstagramMediaExtractor handles posts/reels and rejects stories/profiles
        assertTrue(postReelExtractor.canHandle("https://www.instagram.com/reel/C-0xyz123/"))
        assertTrue(postReelExtractor.canHandle("https://www.instagram.com/p/CIxm6P2A823/"))
        assertFalse(postReelExtractor.canHandle("https://www.instagram.com/stories/natgeo/"))
        assertFalse(postReelExtractor.canHandle("https://www.instagram.com/cristiano/"))
    }

    @Test
    fun testManagerRoutesUrlsToCorrectExtractors() {
        assertEquals(Platform.INSTAGRAM, manager.detectPlatform("https://www.instagram.com/stories/user/123/"))
        assertEquals(Platform.INSTAGRAM, manager.detectPlatform("https://www.instagram.com/cristiano/"))
        assertEquals(Platform.INSTAGRAM, manager.detectPlatform("https://www.instagram.com/reel/C-0xyz123/"))
        assertEquals(Platform.INSTAGRAM, manager.detectPlatform("@talebyosefi44"))
        assertEquals(Platform.YOUTUBE, manager.detectPlatform("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))

        assertEquals("https://www.instagram.com/talebyosefi44/", manager.normalizeUrl("@talebyosefi44"))
    }

    @Test
    fun testDiagnosticsReportingDoesNotThrow() {
        val report = InstagramSessionBridge.DiagnosticReport(
            operation = "STORY",
            method = "ACTIVE_VIEW",
            webViewAlive = true,
            webViewAttached = true,
            webViewWidth = 1080,
            webViewHeight = 1920,
            currentUrlMatches = true,
            jsExecutionState = "COMPLETED",
            targetUsername = "talebyosefi44",
            targetMatched = true,
            viewerDetected = true,
            mediaCandidatesCount = 2,
            mediaTypes = listOf("VIDEO", "IMAGE"),
            dataSource = "RENDERED_DOM"
        )
        // Ensure diagnostic logging executes cleanly without throwing
        InstagramSessionBridge.recordDiagnostic(report)
        assertTrue(report.viewerDetected)
        assertEquals(2, report.mediaCandidatesCount)
    }

    @Test
    fun testProfileUrlComplexVariants() {
        // Various real-world copy profile link shapes
        assertEquals("talebyosefi44", profileExtractor.extractUsername("https://www.instagram.com/talebyosefi44/"))
        assertEquals("talebyosefi44", profileExtractor.extractUsername("https://www.instagram.com/talebyosefi44"))
        assertEquals("talebyosefi44", profileExtractor.extractUsername("https://instagram.com/talebyosefi44?utm_medium=copy_link"))
        assertEquals("talebyosefi44", profileExtractor.extractUsername("https://www.instagram.com/@talebyosefi44?igsh=MXRkZ3A="))
        assertEquals("talebyosefi44", profileExtractor.extractUsername("@talebyosefi44"))

        // Invalid or non-profile URLs
        assertNull(profileExtractor.extractUsername("https://www.instagram.com/stories/talebyosefi44/"))
        assertNull(profileExtractor.extractUsername("https://www.instagram.com/explore/"))
        assertNull(profileExtractor.extractUsername("https://www.instagram.com/p/C-0xyz/"))
    }

    @Test
    fun testProfile404AccountNotFound() = runBlocking {
        val mockClient = createMockClient(404, "{\"message\": \"Not Found\"}")
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        val result = isolatedExtractor.extract("https://www.instagram.com/non_existent_account_12345/")

        assertTrue("404 response should be reported as error", result is ExtractionResult.Error)
        val error = result as ExtractionResult.Error
        assertTrue("Error should mention account not found", error.message.contains("not found", ignoreCase = true))
    }

    @Test
    fun testProfileEmptyOrMissingPhotoReturnsNoFormats() = runBlocking {
        val noPhotoJson = JSONObject().apply {
            put("data", JSONObject().apply {
                put("user", JSONObject().apply {
                    put("username", "user_without_photo")
                    put("full_name", "No Photo User")
                    put("profile_pic_url", "")
                    put("profile_pic_url_hd", "")
                })
            })
        }
        val mockClient = createMockClient(200, noPhotoJson.toString())
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        val result = isolatedExtractor.extract("https://www.instagram.com/user_without_photo/")

        assertTrue("User with no photo should return NoFormatsFound", result is ExtractionResult.NoFormatsFound)
    }

    @Test
    fun testProfileCaseInsensitiveUsernameMatching() = runBlocking {
        val targetJson = JSONObject().apply {
            put("data", JSONObject().apply {
                put("user", JSONObject().apply {
                    put("username", "TalebYosefi44") // Mixed case in response
                    put("full_name", "Taleb Yosefi")
                    put("hd_profile_pic_url_info", JSONObject().apply {
                        put("url", "https://scontent.cdninstagram.com/v/t51.2885-19/hd_taleb.jpg")
                        put("width", 1080)
                        put("height", 1080)
                    })
                })
            })
        }
        val mockClient = createMockClient(200, targetJson.toString())
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        // Request in lowercase
        val result = isolatedExtractor.extract("https://www.instagram.com/talebyosefi44/")

        assertTrue("Case differences should match successfully", result is ExtractionResult.Success)
        val success = result as ExtractionResult.Success
        assertEquals("https://scontent.cdninstagram.com/v/t51.2885-19/hd_taleb.jpg", success.streams.first().url)
    }

    @Test
    fun testOrdinaryPersonalAccountProfilePhoto() = runBlocking {
        // Ordinary personal accounts often provide a standard 320x320 profile picture without 1080p HD
        val personalJson = JSONObject().apply {
            put("data", JSONObject().apply {
                put("user", JSONObject().apply {
                    put("username", "personal_blogger_42")
                    put("full_name", "Alex Personal")
                    put("is_private", false)
                    put("is_verified", false)
                    put("profile_pic_url", "https://scontent.cdninstagram.com/v/t51.2885-19/personal_320.jpg")
                })
            })
        }
        val mockClient = createMockClient(200, personalJson.toString())
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        val result = isolatedExtractor.extract("https://www.instagram.com/personal_blogger_42/")

        assertTrue("Ordinary personal account should extract successfully", result is ExtractionResult.Success)
        val success = result as ExtractionResult.Success
        assertEquals("https://scontent.cdninstagram.com/v/t51.2885-19/personal_320.jpg", success.streams.first().url)
        assertEquals("Profile Picture (320x320 JPG)", success.streams.first().quality)
        assertEquals("@personal_blogger_42", success.metadata.author)
    }

    @Test
    fun testPrivateAccountExposedProfilePhoto() = runBlocking {
        // Private accounts still expose their profile picture to the session
        val privateJson = JSONObject().apply {
            put("data", JSONObject().apply {
                put("user", JSONObject().apply {
                    put("username", "private_family_account")
                    put("full_name", "Smith Family")
                    put("is_private", true)
                    put("is_verified", false)
                    put("profile_pic_url", "https://scontent.cdninstagram.com/v/t51.2885-19/private_320.jpg")
                })
            })
        }
        val mockClient = createMockClient(200, privateJson.toString())
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        val result = isolatedExtractor.extract("https://www.instagram.com/private_family_account/")

        assertTrue("Private account's exposed photo should extract successfully", result is ExtractionResult.Success)
        val success = result as ExtractionResult.Success
        assertEquals("https://scontent.cdninstagram.com/v/t51.2885-19/private_320.jpg", success.streams.first().url)
        // Author label should indicate private status with lock icon
        assertTrue("Author should include lock icon for private account", success.metadata.author.contains("🔒"))
    }

    @Test
    fun testLoginChallengeHandling() = runBlocking {
        // When Instagram returns 401/403 or checkpoint challenge
        val mockClient = createMockClient(403, "{\"message\": \"checkpoint_required\", \"status\": \"fail\"}")
        val isolatedExtractor = InstagramProfileMediaExtractor(client = mockClient)
        val result = isolatedExtractor.extract("https://www.instagram.com/guarded_user/")

        assertTrue("Login challenge should be classified as LoginRequired", result is ExtractionResult.LoginRequired)
        val loginReq = result as ExtractionResult.LoginRequired
        assertTrue(loginReq.message.contains("login", ignoreCase = true))
    }

    @Test
    fun testBareUsernameExtraction() {
        assertEquals("talebyosefi44", profileExtractor.extractUsername("talebyosefi44"))
        assertEquals("cristiano", profileExtractor.extractUsername("cristiano"))
        assertEquals("alex.smith_99", profileExtractor.extractUsername("alex.smith_99"))
        assertTrue(profileExtractor.canHandle("talebyosefi44"))
        assertTrue(profileExtractor.canHandle("@talebyosefi44"))
    }
}

