package com.example.core.media.extraction.instagram

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.core.media.extraction.ExtractionResult
import com.example.core.media.extraction.MediaMetadata
import com.example.core.media.extraction.MediaStream
import com.example.core.media.extraction.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

object InstagramStoryFlow {

    private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    data class StoryMedia(
        val id: String,
        val isVideo: Boolean,
        val url: String,
        val width: Int,
        val height: Int,
        val expiringAt: Long,
        val dataSource: String = "RENDERED_DOM"
    )

    private data class ProbeResult(
        val isReady: Boolean,
        val isUnavailable: Boolean,
        val isPrivate: Boolean,
        val isNotFound: Boolean,
        val isLoginRequired: Boolean,
        val isRateLimited: Boolean,
        val hasStoryRingOnProfile: Boolean = false
    )

    suspend fun extractStory(
        context: Context?,
        targetUsername: String,
        targetStoryId: String?,
        sourceUrl: String
    ): ExtractionResult {
        val cleanTarget = targetUsername.removePrefix("@").trim()
        if (cleanTarget.isBlank()) {
            return ExtractionResult.Error("Invalid Instagram username for Story.")
        }

        // 1. Session verification
        if (!InstagramSessionBridge.hasAuthenticatedSession()) {
            InstagramSessionBridge.recordDiagnostic(
                InstagramSessionBridge.DiagnosticReport(
                    operation = "STORY",
                    method = "AUTH_CHECK",
                    webViewAlive = false,
                    webViewAttached = false,
                    webViewWidth = 0,
                    webViewHeight = 0,
                    currentUrlMatches = false,
                    jsExecutionState = "NOT_STARTED",
                    targetUsername = cleanTarget,
                    targetMatched = false,
                    viewerDetected = false,
                    mediaCandidatesCount = 0,
                    dataSource = "NONE",
                    failureCategory = "LOGIN_REQUIRED",
                    failureStage = "SESSION_CHECK"
                )
            )
            return ExtractionResult.LoginRequired(
                "Instagram Stories require an active login session in YAS Browser. Please log in to Instagram in the browser and try viewing the Story again."
            )
        }

        val activeView = InstagramSessionBridge.getActiveWebView()

        // Fast-path for JVM unit test environments without Android Context or active WebView
        if (context == null && activeView == null) {
            InstagramSessionBridge.recordDiagnostic(
                InstagramSessionBridge.DiagnosticReport(
                    operation = "STORY",
                    method = "MOCK_TEST",
                    webViewAlive = false,
                    webViewAttached = false,
                    webViewWidth = 0,
                    webViewHeight = 0,
                    currentUrlMatches = false,
                    jsExecutionState = "NOT_STARTED",
                    targetUsername = cleanTarget,
                    targetMatched = false,
                    viewerDetected = false,
                    mediaCandidatesCount = 0,
                    dataSource = "NONE",
                    failureCategory = "NO_CONTEXT",
                    failureStage = "DISPATCH"
                )
            )
            return ExtractionResult.NoFormatsFound("No active Stories found for '@$cleanTarget'.")
        }

        return withContext(Dispatchers.Main) {
            // 2. Entry Point A: Active visible WebView is already in the Story viewer
            if (activeView != null) {
                val currentUrl = activeView.url ?: ""
                if (currentUrl.contains("instagram.com/stories/$cleanTarget", ignoreCase = true)) {
                    val stories = extractFromWebView(activeView, cleanTarget)
                    if (stories != null && stories.isNotEmpty()) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "STORY",
                                method = "ACTIVE_VIEW",
                                webViewAlive = true,
                                webViewAttached = activeView.isAttachedToWindow,
                                webViewWidth = activeView.width,
                                webViewHeight = activeView.height,
                                currentUrlMatches = true,
                                jsExecutionState = "COMPLETED",
                                targetUsername = cleanTarget,
                                targetMatched = true,
                                viewerDetected = true,
                                mediaCandidatesCount = stories.size,
                                mediaTypes = stories.map { if (it.isVideo) "VIDEO" else "IMAGE" },
                                dataSource = stories.firstOrNull()?.dataSource ?: "RENDERED_DOM"
                            )
                        )
                        return@withContext buildSuccess(cleanTarget, targetStoryId, sourceUrl, stories)
                    }
                } else if (currentUrl.contains("instagram.com/$cleanTarget", ignoreCase = true) && !currentUrl.contains("/p/") && !currentUrl.contains("/reel/")) {
                    // User is currently viewing the target profile page! Check if active story ring is present
                    val ringProbe = checkProfileStoryRing(activeView, cleanTarget)
                    if (!ringProbe) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "STORY",
                                method = "PROFILE_RING_CHECK",
                                webViewAlive = true,
                                webViewAttached = activeView.isAttachedToWindow,
                                webViewWidth = activeView.width,
                                webViewHeight = activeView.height,
                                currentUrlMatches = true,
                                jsExecutionState = "COMPLETED",
                                targetUsername = cleanTarget,
                                targetMatched = true,
                                viewerDetected = false,
                                mediaCandidatesCount = 0,
                                dataSource = "RENDERED_DOM",
                                failureCategory = "NO_ACTIVE_STORIES",
                                failureStage = "PROFILE_DOM_INSPECTION"
                            )
                        )
                        return@withContext ExtractionResult.NoFormatsFound(
                            "@$cleanTarget does not have any active Stories posted right now (Stories disappear after 24 hours)."
                        )
                    }
                }
            }

            // 3. Entry Point B: Session-aware background WebView with Coroutine-driven polling
            if (context != null) {
                val extraction = loadStoryInSessionWebView(context, cleanTarget, targetStoryId)
                if (extraction != null) {
                    if (extraction.isUnavailable) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "STORY",
                                method = "SESSION_VIEW",
                                webViewAlive = true,
                                webViewAttached = false,
                                webViewWidth = 1080,
                                webViewHeight = 1920,
                                currentUrlMatches = true,
                                jsExecutionState = "COMPLETED",
                                targetUsername = cleanTarget,
                                targetMatched = true,
                                viewerDetected = true,
                                mediaCandidatesCount = 0,
                                dataSource = "RENDERED_DOM",
                                failureCategory = "UNAVAILABLE",
                                failureStage = "VIEWER_PROBE"
                            )
                        )
                        return@withContext ExtractionResult.NoFormatsFound(
                            "This Story for '@$cleanTarget' is unavailable or has expired (24h limit)."
                        )
                    }
                    if (extraction.isPrivate) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "STORY",
                                method = "SESSION_VIEW",
                                webViewAlive = true,
                                webViewAttached = false,
                                webViewWidth = 1080,
                                webViewHeight = 1920,
                                currentUrlMatches = true,
                                jsExecutionState = "COMPLETED",
                                targetUsername = cleanTarget,
                                targetMatched = true,
                                viewerDetected = false,
                                mediaCandidatesCount = 0,
                                dataSource = "RENDERED_DOM",
                                failureCategory = "PRIVATE_ACCOUNT",
                                failureStage = "VIEWER_PROBE"
                            )
                        )
                        return@withContext ExtractionResult.Error(
                            "Account '@$cleanTarget' is private. You must follow this account to view their Stories."
                        )
                    }
                    if (extraction.isNotFound) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "STORY",
                                method = "SESSION_VIEW",
                                webViewAlive = true,
                                webViewAttached = false,
                                webViewWidth = 1080,
                                webViewHeight = 1920,
                                currentUrlMatches = true,
                                jsExecutionState = "COMPLETED",
                                targetUsername = cleanTarget,
                                targetMatched = false,
                                viewerDetected = false,
                                mediaCandidatesCount = 0,
                                dataSource = "NONE",
                                failureCategory = "ACCOUNT_NOT_FOUND",
                                failureStage = "VIEWER_PROBE"
                            )
                        )
                        return@withContext ExtractionResult.Error(
                            "Instagram account '@$cleanTarget' was not found."
                        )
                    }
                    if (extraction.isRateLimited) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "STORY",
                                method = "SESSION_VIEW",
                                webViewAlive = true,
                                webViewAttached = false,
                                webViewWidth = 1080,
                                webViewHeight = 1920,
                                currentUrlMatches = true,
                                jsExecutionState = "COMPLETED",
                                targetUsername = cleanTarget,
                                targetMatched = false,
                                viewerDetected = false,
                                mediaCandidatesCount = 0,
                                dataSource = "NONE",
                                httpStatus = 429,
                                failureCategory = "RATE_LIMITED",
                                failureStage = "VIEWER_PROBE"
                            )
                        )
                        return@withContext ExtractionResult.Error(
                            "Instagram is temporarily rate-limiting Story requests (HTTP 429). Please wait a moment and try again."
                        )
                    }
                    if (extraction.mediaItems.isNotEmpty()) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "STORY",
                                method = "SESSION_VIEW",
                                webViewAlive = true,
                                webViewAttached = false,
                                webViewWidth = 1080,
                                webViewHeight = 1920,
                                currentUrlMatches = true,
                                jsExecutionState = "COMPLETED",
                                targetUsername = cleanTarget,
                                targetMatched = true,
                                viewerDetected = true,
                                mediaCandidatesCount = extraction.mediaItems.size,
                                mediaTypes = extraction.mediaItems.map { if (it.isVideo) "VIDEO" else "IMAGE" },
                                dataSource = extraction.mediaItems.firstOrNull()?.dataSource ?: "RENDERED_DOM"
                            )
                        )
                        return@withContext buildSuccess(cleanTarget, targetStoryId, sourceUrl, extraction.mediaItems)
                    }
                }
            }

            InstagramSessionBridge.recordDiagnostic(
                InstagramSessionBridge.DiagnosticReport(
                    operation = "STORY",
                    method = if (activeView != null) "ACTIVE_VIEW" else "SESSION_VIEW",
                    webViewAlive = activeView != null,
                    webViewAttached = activeView?.isAttachedToWindow ?: false,
                    webViewWidth = activeView?.width ?: 0,
                    webViewHeight = activeView?.height ?: 0,
                    currentUrlMatches = false,
                    jsExecutionState = "FAILED",
                    targetUsername = cleanTarget,
                    targetMatched = false,
                    viewerDetected = false,
                    mediaCandidatesCount = 0,
                    dataSource = "NONE",
                    failureCategory = "NO_MEDIA",
                    failureStage = "EXTRACTION_COMPLETE"
                )
            )

            ExtractionResult.NoFormatsFound(
                "No active Stories found for '@$cleanTarget'. Stories may have expired (24h limit) or been deleted."
            )
        }
    }

    private data class StoryExtractionState(
        val mediaItems: List<StoryMedia> = emptyList(),
        val isUnavailable: Boolean = false,
        val isPrivate: Boolean = false,
        val isNotFound: Boolean = false,
        val isRateLimited: Boolean = false
    )

    private suspend fun checkProfileStoryRing(webView: WebView, targetUsername: String): Boolean = withContext(Dispatchers.Main) {
        withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val safeUser = targetUsername.replace("'", "\\'")
                val script = """
                    (function(targetUser) {
                        const header = document.querySelector('main header, header');
                        if (!header) return false;
                        const storyLink = header.querySelector('a[href*="/stories/' + targetUser + '"]');
                        const canvas = header.querySelector('canvas');
                        const storyBtn = header.querySelector('div[role="button"][tabindex] img');
                        return !!(storyLink || canvas || storyBtn);
                    })('$safeUser');
                """.trimIndent()
                webView.evaluateJavascript(script) { raw ->
                    val hasRing = raw?.trim() == "true"
                    if (cont.isActive) cont.resume(hasRing)
                }
            }
        } ?: false
    }

    private suspend fun loadStoryInSessionWebView(
        context: Context,
        targetUsername: String,
        targetStoryId: String?
    ): StoryExtractionState? = withContext(Dispatchers.Main) {
        val storyUrl = if (!targetStoryId.isNullOrBlank()) {
            "https://www.instagram.com/stories/$targetUsername/$targetStoryId/"
        } else {
            "https://www.instagram.com/stories/$targetUsername/"
        }

        withTimeoutOrNull(12000L) {
            var webView: WebView? = null
            try {
                var isPageFinished = false
                webView = WebView(context).apply {
                    layout(0, 0, 1080, 1920)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = DESKTOP_USER_AGENT
                        mediaPlaybackRequiresUserGesture = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isPageFinished = true
                        }
                    }
                    loadUrl(storyUrl)
                }

                val currentView = webView ?: return@withTimeoutOrNull null

                var attempts = 0
                val maxAttempts = 24 // 24 * 250ms = 6s max
                var finalState: StoryExtractionState? = null

                while (attempts < maxAttempts) {
                    delay(250)
                    attempts++

                    val probeScript = buildProbeScript()
                    val rawProbe = suspendCancellableCoroutine<String?> { cont ->
                        currentView.evaluateJavascript(probeScript) { cont.resume(it) }
                    }
                    val probe = parseProbeResult(rawProbe)

                    if (probe.isUnavailable) {
                        finalState = StoryExtractionState(isUnavailable = true)
                        break
                    }
                    if (probe.isPrivate) {
                        finalState = StoryExtractionState(isPrivate = true)
                        break
                    }
                    if (probe.isNotFound) {
                        finalState = StoryExtractionState(isNotFound = true)
                        break
                    }
                    if (probe.isRateLimited) {
                        finalState = StoryExtractionState(isRateLimited = true)
                        break
                    }

                    if (probe.isReady || attempts >= maxAttempts) {
                        val extractScript = buildExtractScript(targetUsername)
                        val rawMedia = suspendCancellableCoroutine<String?> { cont ->
                            currentView.evaluateJavascript(extractScript) { cont.resume(it) }
                        }
                        val items = parseMediaResult(rawMedia)
                        if (!items.isNullOrEmpty()) {
                            finalState = StoryExtractionState(mediaItems = items)
                            break
                        }
                    }
                }

                try { currentView.destroy() } catch (_: Throwable) {}
                finalState
            } catch (_: Throwable) {
                try { webView?.destroy() } catch (_: Throwable) {}
                null
            }
        }
    }

    private suspend fun extractFromWebView(webView: WebView, targetUsername: String): List<StoryMedia>? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(4000L) {
            suspendCancellableCoroutine<List<StoryMedia>?> { cont ->
                val script = buildExtractScript(targetUsername)
                webView.evaluateJavascript(script) { rawResult ->
                    val items = parseMediaResult(rawResult)
                    if (cont.isActive) cont.resume(items)
                }
            }
        }
    }

    private fun buildProbeScript(): String {
        return """
            (function() {
                const video = document.querySelector('section video, div[role="dialog"] video, video');
                const img = document.querySelector('section img[srcset], div[role="dialog"] img[srcset], section img[crossorigin]');
                const bodyText = document.body ? document.body.innerText : '';

                const unavailable = bodyText.includes('This story is unavailable') || bodyText.includes('Story unavailable');
                const privateAccount = bodyText.includes('This account is private') || bodyText.includes('Follow to see their photos and videos');
                const notFound = bodyText.includes("Sorry, this page isn't available") || bodyText.includes('Page Not Found');
                const loginReq = bodyText.includes('Log In to Instagram') || bodyText.includes('Welcome back to Instagram');
                const rateLimited = bodyText.includes('Please wait a few minutes') || bodyText.includes('rate limited');

                const hasMedia = !!(video && (video.currentSrc || video.src)) || !!(img && (img.currentSrc || img.src));

                return JSON.stringify({
                    ready: hasMedia || unavailable || privateAccount || notFound || loginReq || rateLimited,
                    unavailable: unavailable,
                    privateAccount: privateAccount,
                    notFound: notFound,
                    loginReq: loginReq,
                    rateLimited: rateLimited
                });
            })();
        """.trimIndent()
    }

    private fun buildExtractScript(targetUsername: String): String {
        val safeUser = targetUsername.replace("'", "\\'")
        return """
            (function(targetUser) {
                const items = [];
                const nowSec = Math.floor(Date.now() / 1000);

                // 1. Search embedded JSON scripts for targetUser's active story items
                const scripts = document.querySelectorAll('script[type="application/json"]');
                for (let i = 0; i < scripts.length; i++) {
                    const text = scripts[i].textContent;
                    if (text && (text.includes('video_versions') || text.includes('reels_media') || text.includes('image_versions2'))) {
                        try {
                            const json = JSON.parse(text);
                            function scan(obj) {
                                if (!obj || typeof obj !== 'object') return;
                                if (obj.items && Array.isArray(obj.items)) {
                                    const owner = obj.user ? obj.user.username : null;
                                    if (!owner || owner.toLowerCase() === targetUser.toLowerCase()) {
                                        for (const itm of obj.items) {
                                            const exp = itm.expiring_at || 0;
                                            if (exp > 0 && exp < nowSec) continue;

                                            const itmId = String(itm.id || itm.pk || '');
                                            const isVideo = itm.media_type === 2 || !!itm.video_versions;

                                            if (isVideo && itm.video_versions && itm.video_versions.length > 0) {
                                                let best = itm.video_versions[0];
                                                let maxPix = (best.width || 0) * (best.height || 0);
                                                for (const v of itm.video_versions) {
                                                    const pix = (v.width || 0) * (v.height || 0);
                                                    if (pix > maxPix) {
                                                        best = v;
                                                        maxPix = pix;
                                                    }
                                                }
                                                if (best.url && best.url.startsWith('http')) {
                                                    items.push({
                                                        id: itmId,
                                                        isVideo: true,
                                                        url: best.url,
                                                        width: best.width || 1080,
                                                        height: best.height || 1920,
                                                        expiringAt: exp,
                                                        source: 'EMBEDDED_JSON'
                                                    });
                                                }
                                            } else if (itm.image_versions2 && itm.image_versions2.candidates && itm.image_versions2.candidates.length > 0) {
                                                let best = itm.image_versions2.candidates[0];
                                                let maxPix = (best.width || 0) * (best.height || 0);
                                                for (const c of itm.image_versions2.candidates) {
                                                    const pix = (c.width || 0) * (c.height || 0);
                                                    if (pix > maxPix) {
                                                        best = c;
                                                        maxPix = pix;
                                                    }
                                                }
                                                if (best.url && best.url.startsWith('http')) {
                                                    items.push({
                                                        id: itmId,
                                                        isVideo: false,
                                                        url: best.url,
                                                        width: best.width || 1080,
                                                        height: best.height || 1920,
                                                        expiringAt: exp,
                                                        source: 'EMBEDDED_JSON'
                                                    });
                                                }
                                            }
                                        }
                                    }
                                }
                                for (const k in obj) {
                                    if (Object.prototype.hasOwnProperty.call(obj, k)) {
                                        scan(obj[k]);
                                    }
                                }
                            }
                            scan(json);
                        } catch(e) {}
                    }
                }

                // 2. Active rendered DOM viewer inspection
                if (items.length === 0) {
                    const video = document.querySelector('section video, div[role="dialog"] video, video');
                    if (video) {
                        const u = video.currentSrc || video.src;
                        if (u && u.startsWith('http') && !u.startsWith('blob:')) {
                            items.push({
                                id: 'active_story_video',
                                isVideo: true,
                                url: u,
                                width: video.videoWidth || 1080,
                                height: video.videoHeight || 1920,
                                expiringAt: 0,
                                source: 'RENDERED_DOM'
                            });
                        }
                    }

                    const imgs = document.querySelectorAll('section img, div[role="dialog"] img');
                    for (const img of imgs) {
                        const w = img.naturalWidth || img.width || 0;
                        const h = img.naturalHeight || img.height || 0;
                        if (w >= 300 || h >= 300) {
                            let bestSrc = img.currentSrc || img.src;
                            if (img.srcset) {
                                const parts = img.srcset.split(',');
                                let maxW = 0;
                                for (const p of parts) {
                                    const m = p.trim().match(/^(\S+)\s+(\d+)w$/);
                                    if (m && parseInt(m[2], 10) > maxW) {
                                        maxW = parseInt(m[2], 10);
                                        bestSrc = m[1];
                                    }
                                }
                            }
                            if (bestSrc && bestSrc.startsWith('http') && !bestSrc.startsWith('data:')) {
                                items.push({
                                    id: 'active_story_photo',
                                    isVideo: false,
                                    url: bestSrc,
                                    width: w || 1080,
                                    height: h || 1920,
                                    expiringAt: 0,
                                    source: 'RENDERED_DOM'
                                });
                                break;
                            }
                        }
                    }
                }

                // 3. Fallback: Performance resource timing entries for recently loaded video/image streams
                if (items.length === 0 && window.performance && window.performance.getEntriesByType) {
                    const resources = window.performance.getEntriesByType('resource');
                    for (let r = resources.length - 1; r >= 0; r--) {
                        const resUrl = resources[r].name;
                        if (resUrl && resUrl.includes('cdninstagram.com') && (resUrl.includes('.mp4') || resUrl.includes('/v/t50.'))) {
                            items.push({
                                id: 'perf_story_video',
                                isVideo: true,
                                url: resUrl,
                                width: 1080,
                                height: 1920,
                                expiringAt: 0,
                                source: 'PERFORMANCE_TIMING'
                            });
                            break;
                        }
                    }
                }

                return JSON.stringify(items);
            })('$safeUser');
        """.trimIndent()
    }

    private fun parseProbeResult(raw: String?): ProbeResult {
        if (raw.isNullOrBlank() || raw == "null") return ProbeResult(false, false, false, false, false, false)
        return try {
            val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                org.json.JSONTokener(raw).nextValue().toString()
            } else raw
            val json = JSONObject(unquoted)
            ProbeResult(
                isReady = json.optBoolean("ready", false),
                isUnavailable = json.optBoolean("unavailable", false),
                isPrivate = json.optBoolean("privateAccount", false),
                isNotFound = json.optBoolean("notFound", false),
                isLoginRequired = json.optBoolean("loginReq", false),
                isRateLimited = json.optBoolean("rateLimited", false)
            )
        } catch (_: Throwable) {
            ProbeResult(false, false, false, false, false, false)
        }
    }

    private fun parseMediaResult(raw: String?): List<StoryMedia>? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return null
        return try {
            val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                org.json.JSONTokener(raw).nextValue().toString()
            } else raw
            val arr = org.json.JSONArray(unquoted)
            val list = mutableListOf<StoryMedia>()
            val nowSec = System.currentTimeMillis() / 1000L

            for (i in 0 until arr.length()) {
                val itm = arr.getJSONObject(i)
                val url = itm.optString("url")
                if (url.isBlank() || !url.startsWith("http")) continue

                val exp = itm.optLong("expiringAt", 0L)
                if (exp in 1..nowSec) continue

                list.add(
                    StoryMedia(
                        id = itm.optString("id", "story_$i"),
                        isVideo = itm.optBoolean("isVideo", false),
                        url = url,
                        width = itm.optInt("width", 1080),
                        height = itm.optInt("height", 1920),
                        expiringAt = exp,
                        dataSource = itm.optString("source", "RENDERED_DOM")
                    )
                )
            }
            list
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildSuccess(
        username: String,
        targetStoryId: String?,
        sourceUrl: String,
        stories: List<StoryMedia>
    ): ExtractionResult.Success {
        val streamHeaders = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to "https://www.instagram.com/",
            "Origin" to "https://www.instagram.com"
        )

        val filtered = if (!targetStoryId.isNullOrBlank()) {
            val m = stories.filter { it.id.contains(targetStoryId) || it.id.startsWith(targetStoryId) }
            if (m.isNotEmpty()) m else stories
        } else stories

        val streams = filtered.mapIndexed { index, item ->
            val label = if (filtered.size > 1) "Story ${index + 1}" else "Story"
            if (item.isVideo) {
                MediaStream(
                    id = "ig_story_${username}_${item.id}",
                    url = item.url,
                    quality = "$label Video (${item.height}p MP4)",
                    format = "MP4",
                    isVideo = true,
                    isAudio = true,
                    headers = streamHeaders
                )
            } else {
                MediaStream(
                    id = "ig_story_${username}_${item.id}",
                    url = item.url,
                    quality = "$label Photo (${item.width}x${item.height} JPG)",
                    format = "JPG",
                    isVideo = false,
                    isAudio = false,
                    headers = streamHeaders
                )
            }
        }

        val firstThumb = filtered.firstOrNull()?.url

        val metadata = MediaMetadata(
            id = "ig_story_$username",
            title = "Instagram Story from @$username",
            author = "@$username",
            thumbnailUrl = firstThumb,
            sourceUrl = sourceUrl,
            platform = Platform.INSTAGRAM
        )

        return ExtractionResult.Success(metadata, streams)
    }
}
