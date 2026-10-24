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

object InstagramProfileFlow {

    private const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    data class ProfileData(
        val username: String,
        val fullName: String,
        val biography: String,
        val isPrivate: Boolean,
        val isVerified: Boolean,
        val picUrl: String,
        val width: Int,
        val height: Int,
        val dataSource: String = "RENDERED_DOM",
        val notFound: Boolean = false,
        val rateLimited: Boolean = false,
        val loginReq: Boolean = false,
        val mismatchedUser: String? = null
    )

    private data class ProbeResult(
        val isReady: Boolean,
        val notFound: Boolean,
        val rateLimited: Boolean,
        val loginReq: Boolean
    )

    suspend fun extractProfile(
        context: Context?,
        targetUsername: String,
        sourceUrl: String
    ): ExtractionResult? {
        val cleanTarget = targetUsername.removePrefix("@").trim()
        if (cleanTarget.isBlank()) {
            return ExtractionResult.Error("Invalid Instagram username.")
        }

        val activeView = InstagramSessionBridge.getActiveWebView()

        // Fast-path for JVM unit test environments without Android Context or active WebView
        if (context == null && activeView == null) {
            InstagramSessionBridge.recordDiagnostic(
                InstagramSessionBridge.DiagnosticReport(
                    operation = "PROFILE",
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
            return null
        }

        return withContext(Dispatchers.Main) {
            // 1. Entry Point A: Active visible WebView is available
            if (activeView != null) {
                val currentUrl = activeView.url ?: ""
                val isDirectlyViewingTarget = currentUrl.contains("instagram.com/$cleanTarget", ignoreCase = true) &&
                    !currentUrl.contains("/p/", ignoreCase = true) &&
                    !currentUrl.contains("/reel/", ignoreCase = true) &&
                    !currentUrl.contains("/stories/", ignoreCase = true)

                if (isDirectlyViewingTarget) {
                    val profile = extractFromWebView(activeView, cleanTarget)
                    if (profile != null) {
                        if (profile.rateLimited) {
                            InstagramSessionBridge.recordDiagnostic(
                                InstagramSessionBridge.DiagnosticReport(
                                    operation = "PROFILE",
                                    method = "ACTIVE_VIEW",
                                    webViewAlive = true,
                                    webViewAttached = activeView.isAttachedToWindow,
                                    webViewWidth = activeView.width,
                                    webViewHeight = activeView.height,
                                    currentUrlMatches = true,
                                    jsExecutionState = "COMPLETED",
                                    targetUsername = cleanTarget,
                                    targetMatched = false,
                                    viewerDetected = false,
                                    mediaCandidatesCount = 0,
                                    dataSource = "NONE",
                                    httpStatus = 429,
                                    failureCategory = "RATE_LIMITED",
                                    failureStage = "INSPECTION"
                                )
                            )
                            return@withContext ExtractionResult.Error("Instagram is temporarily rate-limiting requests (HTTP 429). Please wait a moment and try again.")
                        }
                        if (profile.loginReq) {
                            InstagramSessionBridge.recordDiagnostic(
                                InstagramSessionBridge.DiagnosticReport(
                                    operation = "PROFILE",
                                    method = "ACTIVE_VIEW",
                                    webViewAlive = true,
                                    webViewAttached = activeView.isAttachedToWindow,
                                    webViewWidth = activeView.width,
                                    webViewHeight = activeView.height,
                                    currentUrlMatches = true,
                                    jsExecutionState = "COMPLETED",
                                    targetUsername = cleanTarget,
                                    targetMatched = false,
                                    viewerDetected = false,
                                    mediaCandidatesCount = 0,
                                    dataSource = "NONE",
                                    failureCategory = "LOGIN_REQUIRED",
                                    failureStage = "INSPECTION"
                                )
                            )
                            return@withContext ExtractionResult.LoginRequired("Instagram requires an active login session in YAS Browser. Please log in to Instagram in the browser and try again.")
                        }
                        if (profile.notFound) {
                            InstagramSessionBridge.recordDiagnostic(
                                InstagramSessionBridge.DiagnosticReport(
                                    operation = "PROFILE",
                                    method = "ACTIVE_VIEW",
                                    webViewAlive = true,
                                    webViewAttached = activeView.isAttachedToWindow,
                                    webViewWidth = activeView.width,
                                    webViewHeight = activeView.height,
                                    currentUrlMatches = true,
                                    jsExecutionState = "COMPLETED",
                                    targetUsername = cleanTarget,
                                    targetMatched = false,
                                    viewerDetected = false,
                                    mediaCandidatesCount = 0,
                                    dataSource = "NONE",
                                    failureCategory = "ACCOUNT_NOT_FOUND",
                                    failureStage = "INSPECTION"
                                )
                            )
                            return@withContext ExtractionResult.Error("Instagram account '@$cleanTarget' was not found.")
                        }
                        if (profile.picUrl.isNotBlank()) {
                            InstagramSessionBridge.recordDiagnostic(
                                InstagramSessionBridge.DiagnosticReport(
                                    operation = "PROFILE",
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
                                    mediaCandidatesCount = 1,
                                    mediaTypes = listOf("IMAGE"),
                                    dataSource = profile.dataSource
                                )
                            )
                            return@withContext buildProfileResult(cleanTarget, sourceUrl, profile)
                        }
                    }
                } else if (currentUrl.contains("instagram.com", ignoreCase = true)) {
                    // Active view is on another Instagram page (e.g. feed). Attempt fast in-page fetch
                    val profile = extractFromWebView(activeView, cleanTarget)
                    if (profile != null && profile.picUrl.isNotBlank()) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "PROFILE",
                                method = "ACTIVE_VIEW_FETCH",
                                webViewAlive = true,
                                webViewAttached = activeView.isAttachedToWindow,
                                webViewWidth = activeView.width,
                                webViewHeight = activeView.height,
                                currentUrlMatches = false,
                                jsExecutionState = "COMPLETED",
                                targetUsername = cleanTarget,
                                targetMatched = true,
                                viewerDetected = true,
                                mediaCandidatesCount = 1,
                                mediaTypes = listOf("IMAGE"),
                                dataSource = profile.dataSource
                            )
                        )
                        return@withContext buildProfileResult(cleanTarget, sourceUrl, profile)
                    }
                    // If not resolved from active view, seamlessly fall through to Entry Point B!
                }
            }

            // 2. Entry Point B: Session-aware background WebView with mobile profile loading & coroutine polling
            if (context != null) {
                val profile = loadProfileInSessionWebView(context, cleanTarget)
                if (profile != null) {
                    if (profile.rateLimited) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "PROFILE",
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
                                failureStage = "PROBE"
                            )
                        )
                        return@withContext ExtractionResult.Error("Instagram is temporarily rate-limiting requests (HTTP 429). Please wait a moment and try again.")
                    }
                    if (profile.loginReq) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "PROFILE",
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
                                failureCategory = "LOGIN_REQUIRED",
                                failureStage = "PROBE"
                            )
                        )
                        return@withContext ExtractionResult.LoginRequired("Instagram requires an active login session in YAS Browser. Please log in to Instagram in the browser and try again.")
                    }
                    if (profile.notFound) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "PROFILE",
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
                                failureStage = "PROBE"
                            )
                        )
                        return@withContext ExtractionResult.Error("Instagram account '@$cleanTarget' was not found.")
                    }
                    if (profile.picUrl.isNotBlank()) {
                        InstagramSessionBridge.recordDiagnostic(
                            InstagramSessionBridge.DiagnosticReport(
                                operation = "PROFILE",
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
                                mediaCandidatesCount = 1,
                                mediaTypes = listOf("IMAGE"),
                                dataSource = profile.dataSource
                            )
                        )
                        return@withContext buildProfileResult(cleanTarget, sourceUrl, profile)
                    }
                }
            }

            InstagramSessionBridge.recordDiagnostic(
                InstagramSessionBridge.DiagnosticReport(
                    operation = "PROFILE",
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

            ExtractionResult.NoFormatsFound("No profile picture found for '@$cleanTarget'. Account may be unavailable or has no custom profile photo.")
        }
    }

    private suspend fun loadProfileInSessionWebView(
        context: Context,
        targetUsername: String
    ): ProfileData? = withContext(Dispatchers.Main) {
        val profileUrl = "https://www.instagram.com/$targetUsername/"

        withTimeoutOrNull(10000L) {
            var webView: WebView? = null
            try {
                var isPageFinished = false
                webView = WebView(context).apply {
                    layout(0, 0, 1080, 1920)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = MOBILE_USER_AGENT
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isPageFinished = true
                        }
                    }
                    loadUrl(profileUrl)
                }

                val currentView = webView ?: return@withTimeoutOrNull null

                var attempts = 0
                val maxAttempts = 24 // 24 * 250ms = 6s max
                var foundData: ProfileData? = null

                while (attempts < maxAttempts) {
                    delay(250)
                    attempts++

                    val probeScript = buildProbeScript()
                    val rawProbe = suspendCancellableCoroutine<String?> { cont ->
                        currentView.evaluateJavascript(probeScript) { cont.resume(it) }
                    }
                    val probe = parseProbeResult(rawProbe)

                    if (probe.rateLimited) {
                        foundData = ProfileData(targetUsername, targetUsername, "", false, false, "", 0, 0, rateLimited = true)
                        break
                    }
                    if (probe.loginReq) {
                        foundData = ProfileData(targetUsername, targetUsername, "", false, false, "", 0, 0, loginReq = true)
                        break
                    }
                    if (probe.notFound) {
                        foundData = ProfileData(targetUsername, targetUsername, "", false, false, "", 0, 0, notFound = true)
                        break
                    }

                    if (probe.isReady || attempts >= 3) {
                        val extracted = extractFromWebView(currentView, targetUsername)
                        if (extracted != null && extracted.picUrl.isNotBlank()) {
                            foundData = extracted
                            break
                        }
                    }
                }

                try { currentView.destroy() } catch (_: Throwable) {}
                foundData
            } catch (_: Throwable) {
                try { webView?.destroy() } catch (_: Throwable) {}
                null
            }
        }
    }

    private suspend fun extractFromWebView(webView: WebView, targetUsername: String): ProfileData? = withContext(Dispatchers.Main) {
        val safeUser = targetUsername.replace("'", "\\'")
        val script = buildExtractScript(targetUsername)

        withTimeoutOrNull(4500L) {
            // Step 1: Execute extraction script which checks meta tags, DOM, embedded scripts, and triggers fetch
            val initialRaw = suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(script) { rawResult ->
                    if (cont.isActive) cont.resume(rawResult)
                }
            }
            val initialData = parseProfileDataResult(targetUsername, initialRaw)

            // If initial data has genuine HD (>= 640), rate-limiting, or login requirement, return immediately
            if (initialData != null && (initialData.rateLimited || initialData.loginReq || (initialData.picUrl.isNotBlank() && initialData.width >= 640))) {
                return@withTimeoutOrNull initialData
            }

            // Step 2: Poll in-WebView cache for async fetch results (up to 1200ms in 150ms intervals)
            val pollScript = "(function() { return JSON.stringify((window.__yas_profile_cache && window.__yas_profile_cache['$safeUser'.toLowerCase()]) || null); })();"
            var elapsed = 0L
            val maxWait = 1200L
            val step = 150L

            while (elapsed < maxWait) {
                delay(step)
                elapsed += step

                val pollRaw = suspendCancellableCoroutine<String?> { cont ->
                    webView.evaluateJavascript(pollScript) { rawResult ->
                        if (cont.isActive) cont.resume(rawResult)
                    }
                }
                val polledData = parseProfileDataResult(targetUsername, pollRaw)
                if (polledData != null) {
                    if (polledData.rateLimited) {
                        return@withTimeoutOrNull polledData
                    }
                    if (polledData.picUrl.isNotBlank()) {
                        return@withTimeoutOrNull polledData
                    }
                }
            }

            // Return initial data (from meta tag or DOM) if available
            initialData
        }
    }

    private fun buildProbeScript(): String {
        return """
            (function() {
                const bodyText = document.body ? document.body.innerText : '';
                const notFound = bodyText.includes("Sorry, this page isn't available") || 
                                 bodyText.includes('Page Not Found') || 
                                 bodyText.includes('The link you followed may be broken');
                const rateLimited = bodyText.includes('Please wait a few minutes') || 
                                    bodyText.includes('rate limited');
                const loginReq = document.location.pathname.startsWith('/accounts/login') || 
                                 document.location.pathname.startsWith('/challenge') || 
                                 bodyText.includes('Log In to Instagram') || 
                                 bodyText.includes('Welcome back to Instagram');

                const metaImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
                const profileImg = document.querySelector('main img, [role="main"] img, header img');
                const hasImg = !!(metaImg && metaImg.content && metaImg.content.startsWith('http')) || 
                               !!(profileImg && (profileImg.currentSrc || profileImg.src));

                return JSON.stringify({
                    ready: hasImg || notFound || rateLimited || loginReq,
                    notFound: notFound,
                    rateLimited: rateLimited,
                    loginReq: loginReq
                });
            })();
        """.trimIndent()
    }

    private fun buildExtractScript(targetUsername: String): String {
        val safeUser = targetUsername.replace("'", "\\'")
        return """
            (function(targetUser) {
                targetUser = (targetUser || '').trim();
                if (!targetUser) return JSON.stringify({ error: 'No user' });

                // Helper: Strictly exclude viewer's avatar located in bottom nav bar, side nav, or footer!
                function isNav(el) {
                    if (!el) return false;
                    return !!el.closest('nav, footer, aside, [role="navigation"], [role="tablist"]');
                }

                let bestUrl = null;
                let bestWidth = 0;
                let bestHeight = 0;
                let fullName = targetUser;
                let isPrivate = false;
                let isVerified = false;
                let biography = '';
                let foundTarget = false;
                let source = 'NONE';
                let rateLimited = false;
                let notFound = false;
                let loginReq = false;

                // 1. Status inspection from page URL and body text
                const path = document.location ? document.location.pathname : '';
                if (path.startsWith('/accounts/login') || path.startsWith('/challenge')) {
                    loginReq = true;
                }

                const bodyText = document.body ? document.body.innerText : '';
                if (bodyText.includes("Sorry, this page isn't available") || 
                    bodyText.includes('Page Not Found') || 
                    bodyText.includes('The link you followed may be broken')) {
                    notFound = true;
                }
                if (bodyText.includes('Please wait a few minutes') || bodyText.includes('rate limited')) {
                    rateLimited = true;
                }
                if (bodyText.includes('Log In to Instagram') || bodyText.includes('Welcome back to Instagram')) {
                    loginReq = true;
                }
                if (bodyText.includes('This account is private') || 
                    bodyText.includes('This Account is Private') || 
                    bodyText.includes('Follow to see their photos and videos')) {
                    isPrivate = true;
                }

                // 2. Open Graph & Twitter Meta Tags (present on both public and private profile pages)
                const ogImage = document.querySelector('meta[property="og:image"]')?.getAttribute('content');
                const twitterImage = document.querySelector('meta[name="twitter:image"]')?.getAttribute('content');
                const ogTitle = (document.querySelector('meta[property="og:title"]')?.getAttribute('content') || '').toLowerCase();
                const metaCandidate = ogImage || twitterImage;

                const isRealMetaAvatar = metaCandidate && 
                                         metaCandidate.startsWith('http') && 
                                         !metaCandidate.includes('rsrc.php') && 
                                         !metaCandidate.includes('static.cdninstagram.com');
                const metaTitleMatches = !ogTitle || ogTitle.includes(targetUser.toLowerCase());

                if (isRealMetaAvatar && metaTitleMatches && !notFound && !rateLimited && !loginReq) {
                    bestUrl = metaCandidate;
                    bestWidth = 320;
                    bestHeight = 320;
                    source = 'META_TAG';
                    foundTarget = true;
                }

                // 3. Embedded JSON scripts inspection (Relay, window.__initialData, window._sharedData)
                const scripts = document.querySelectorAll('script');
                for (let i = 0; i < scripts.length; i++) {
                    const text = scripts[i].textContent;
                    if (text && text.toLowerCase().includes(targetUser.toLowerCase()) && (text.includes('profile_pic') || text.includes('profile_pic_url'))) {
                        try {
                            let json = null;
                            if (scripts[i].type === 'application/json' || text.trim().startsWith('{')) {
                                try { json = JSON.parse(text); } catch(e) {}
                            }
                            if (json) {
                                function searchObj(obj) {
                                    if (!obj || typeof obj !== 'object') return;
                                    if (obj.username && obj.username.toLowerCase() === targetUser.toLowerCase()) {
                                        foundTarget = true;
                                        fullName = obj.full_name || fullName;
                                        isPrivate = isPrivate || !!obj.is_private;
                                        isVerified = isVerified || !!obj.is_verified;
                                        biography = obj.biography || biography;

                                        if (obj.hd_profile_pic_url_info && obj.hd_profile_pic_url_info.url) {
                                            bestUrl = obj.hd_profile_pic_url_info.url;
                                            bestWidth = obj.hd_profile_pic_url_info.width || 1080;
                                            bestHeight = obj.hd_profile_pic_url_info.height || 1080;
                                            source = 'EMBEDDED_JSON';
                                        } else if (obj.profile_pic_url_hd && (!bestUrl || bestWidth < 1080)) {
                                            bestUrl = obj.profile_pic_url_hd;
                                            bestWidth = 1080;
                                            bestHeight = 1080;
                                            source = 'EMBEDDED_JSON';
                                        } else if (obj.profile_pic_url && !bestUrl) {
                                            bestUrl = obj.profile_pic_url;
                                            bestWidth = 320;
                                            bestHeight = 320;
                                            source = 'EMBEDDED_JSON';
                                        }
                                    }
                                    for (const k in obj) {
                                        if (Object.prototype.hasOwnProperty.call(obj, k)) {
                                            searchObj(obj[k]);
                                        }
                                    }
                                }
                                searchObj(json);
                            }
                        } catch(e) {}
                    }
                }

                // 4. Rendered DOM Inspection (Circular profile header avatar)
                if (!notFound && !rateLimited && !loginReq) {
                    const candidates = [];
                    const imgs = document.querySelectorAll('main img, [role="main"] img, header img');
                    for (let i = 0; i < imgs.length; i++) {
                        const img = imgs[i];
                        if (isNav(img)) continue;

                        const alt = (img.getAttribute('alt') || '').toLowerCase();
                        const src = img.currentSrc || img.src || '';
                        if (!src || !src.startsWith('http') || src.startsWith('data:')) continue;
                        if (src.includes('rsrc.php') || src.includes('static.cdninstagram.com')) continue;

                        const isTargetAlt = alt.includes(targetUser.toLowerCase()) || 
                                            alt.includes('profile picture') || 
                                            alt.includes('profile photo') ||
                                            alt.includes('change profile photo') ||
                                            alt.includes('change profile picture');
                        const isProfileCdn = src.includes('t51.2885-19') || src.includes('s150x150') || src.includes('s320x320');

                        let w = img.naturalWidth || img.width || 0;
                        let h = img.naturalHeight || img.height || 0;
                        let candidateUrl = src;

                        if (img.srcset) {
                            const parts = img.srcset.split(',');
                            let maxW = 0;
                            for (let p of parts) {
                                const m = p.trim().match(/^(\S+)\s+(\d+)w$/);
                                if (m) {
                                    const parsedW = parseInt(m[2], 10);
                                    if (parsedW > maxW) {
                                        maxW = parsedW;
                                        candidateUrl = m[1];
                                        w = parsedW;
                                        h = parsedW;
                                    }
                                }
                            }
                        }

                        if (isTargetAlt || isProfileCdn) {
                            candidates.push({
                                url: candidateUrl,
                                width: w,
                                height: h,
                                targetNamed: alt.includes(targetUser.toLowerCase()),
                                isProfileCdn: isProfileCdn
                            });
                        }
                    }

                    candidates.sort((a, b) => {
                        if (a.targetNamed && !b.targetNamed) return -1;
                        if (!a.targetNamed && b.targetNamed) return 1;
                        if (a.isProfileCdn && !b.isProfileCdn) return -1;
                        if (!a.isProfileCdn && b.isProfileCdn) return 1;
                        return (b.width * b.height) - (a.width * a.height);
                    });

                    if (candidates.length > 0) {
                        const chosen = candidates[0];
                        if (chosen.width * chosen.height > bestWidth * bestHeight || !bestUrl) {
                            bestUrl = chosen.url;
                            bestWidth = chosen.width;
                            bestHeight = chosen.height;
                            foundTarget = true;
                            source = 'RENDERED_DOM';
                        }
                    }
                }

                // 5. Authenticated in-WebView fetch (attempt HD upgrade if not already genuine HD)
                const needsFetch = !bestUrl || bestWidth < 640;
                if (needsFetch && !notFound && !rateLimited && !loginReq) {
                    if (!window.__yas_profile_cache) window.__yas_profile_cache = {};
                    if (!window.__yas_profile_cache[targetUser.toLowerCase()]) {
                        window.__yas_profile_cache[targetUser.toLowerCase()] = { status: 'loading' };

                        fetch('/api/v1/users/web_profile_info/?username=' + encodeURIComponent(targetUser), {
                            headers: {
                                'x-ig-app-id': '936619743392459',
                                'x-asbd-id': '129477',
                                'x-requested-with': 'XMLHttpRequest'
                            }
                        }).then(function(r) {
                            if (r.status === 429) {
                                window.__yas_profile_cache[targetUser.toLowerCase()] = { rateLimited: true, status: 429 };
                                return;
                            }
                            if (r.status === 200) {
                                return r.json().then(function(j) {
                                    const u = j && j.data && j.data.user;
                                    if (u && u.username && u.username.toLowerCase() === targetUser.toLowerCase()) {
                                        const hd = u.hd_profile_pic_url_info;
                                        const url = (hd && hd.url) || u.profile_pic_url_hd || u.profile_pic_url;
                                        const w = (hd && hd.width) || (u.profile_pic_url_hd ? 1080 : 320);
                                        const h = (hd && hd.height) || (u.profile_pic_url_hd ? 1080 : 320);
                                        window.__yas_profile_cache[targetUser.toLowerCase()] = {
                                            found: true,
                                            url: url,
                                            width: w,
                                            height: h,
                                            fullName: u.full_name || targetUser,
                                            isPrivate: !!u.is_private,
                                            isVerified: !!u.is_verified,
                                            biography: u.biography || '',
                                            source: 'AUTHENTICATED_WEBVIEW_FETCH'
                                        };
                                    } else {
                                        // Do not mark notFound! Personal and private accounts fail web_profile_info
                                        window.__yas_profile_cache[targetUser.toLowerCase()] = { fetchFailed: true };
                                    }
                                });
                            } else {
                                // 400, 403, etc. - Do not mark notFound! Preserve DOM/Meta tag data
                                window.__yas_profile_cache[targetUser.toLowerCase()] = { fetchFailed: true, status: r.status };
                            }
                        }).catch(function(err) {
                            window.__yas_profile_cache[targetUser.toLowerCase()] = { fetchFailed: true, error: String(err) };
                        });
                    }
                }

                return JSON.stringify({
                    found: foundTarget || !!bestUrl,
                    url: bestUrl,
                    width: bestWidth,
                    height: bestHeight,
                    fullName: fullName,
                    isPrivate: isPrivate,
                    isVerified: isVerified,
                    biography: biography,
                    source: source,
                    rateLimited: rateLimited,
                    notFound: notFound && !bestUrl,
                    loginReq: loginReq && !bestUrl
                });
            })('$safeUser');
        """.trimIndent()
    }

    private fun parseProbeResult(raw: String?): ProbeResult {
        if (raw.isNullOrBlank() || raw == "null") return ProbeResult(false, false, false, false)
        return try {
            val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                org.json.JSONTokener(raw).nextValue().toString()
            } else raw
            val json = JSONObject(unquoted)
            ProbeResult(
                isReady = json.optBoolean("ready", false),
                notFound = json.optBoolean("notFound", false),
                rateLimited = json.optBoolean("rateLimited", false),
                loginReq = json.optBoolean("loginReq", false)
            )
        } catch (_: Throwable) {
            ProbeResult(false, false, false, false)
        }
    }

    private fun parseProfileDataResult(targetUsername: String, raw: String?): ProfileData? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return null
        return try {
            val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                org.json.JSONTokener(raw).nextValue().toString()
            } else raw
            val json = JSONObject(unquoted)

            if (json.optBoolean("rateLimited", false) || json.optInt("status", 0) == 429) {
                return ProfileData(targetUsername, targetUsername, "", false, false, "", 0, 0, rateLimited = true)
            }
            if (json.optBoolean("loginReq", false)) {
                return ProfileData(targetUsername, targetUsername, "", false, false, "", 0, 0, loginReq = true)
            }
            if (json.optBoolean("mismatched", false)) {
                return null // Strictly reject mismatched viewer user
            }

            val url = json.optString("url")
            val notFound = json.optBoolean("notFound", false) || json.optInt("status", 0) == 404

            if (url.isBlank() || !url.startsWith("http")) {
                if (notFound) {
                    return ProfileData(targetUsername, targetUsername, "", false, false, "", 0, 0, notFound = true)
                }
                return null
            }

            ProfileData(
                username = targetUsername,
                fullName = json.optString("fullName", targetUsername).ifBlank { targetUsername },
                biography = json.optString("biography", ""),
                isPrivate = json.optBoolean("isPrivate", false),
                isVerified = json.optBoolean("isVerified", false),
                picUrl = url,
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                dataSource = json.optString("source", "RENDERED_DOM")
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildProfileResult(
        username: String,
        sourceUrl: String,
        data: ProfileData
    ): ExtractionResult.Success {
        val streamHeaders = mapOf(
            "User-Agent" to MOBILE_USER_AGENT,
            "Referer" to "https://www.instagram.com/",
            "Origin" to "https://www.instagram.com"
        )

        val isGenuineHd = data.width >= 640 && data.height >= 640
        val qualityLabel = if (isGenuineHd) {
            "Profile Picture (HD JPG)"
        } else if (data.width > 0 && data.height > 0) {
            "Profile Picture (${data.width}x${data.height} JPG)"
        } else {
            "Profile Picture (JPG)"
        }

        val stream = MediaStream(
            id = "ig_profile_pic_$username",
            url = data.picUrl,
            quality = qualityLabel,
            format = "JPG",
            isVideo = false,
            isAudio = false,
            headers = streamHeaders
        )

        val lockIndicator = if (data.isPrivate) " 🔒" else ""
        val verifiedIndicator = if (data.isVerified) " ✓" else ""

        val metadata = MediaMetadata(
            id = "ig_profile_$username",
            title = "${data.fullName} (@$username) Profile Picture",
            author = "@$username$verifiedIndicator$lockIndicator",
            thumbnailUrl = data.picUrl,
            sourceUrl = sourceUrl,
            platform = Platform.INSTAGRAM,
            description = data.biography
        )

        return ExtractionResult.Success(metadata, listOf(stream))
    }
}
