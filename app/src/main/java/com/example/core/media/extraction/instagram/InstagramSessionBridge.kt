package com.example.core.media.extraction.instagram

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.regex.Pattern

object InstagramSessionBridge {

    private const val TAG = "InstagramDiagnostics"
    private var activeWebViewRef: WeakReference<WebView>? = null

    data class DiagnosticReport(
        val operation: String, // "STORY" or "PROFILE"
        val method: String, // "ACTIVE_VIEW", "SESSION_VIEW", "PROFILE_RING_NAVIGATION", "MOCK_TEST"
        val webViewAlive: Boolean,
        val webViewAttached: Boolean,
        val webViewWidth: Int,
        val webViewHeight: Int,
        val currentUrlMatches: Boolean,
        val jsExecutionState: String, // "NOT_STARTED", "STARTED", "COMPLETED", "TIMED_OUT", "FAILED"
        val targetUsername: String,
        val targetMatched: Boolean,
        val viewerDetected: Boolean,
        val mediaCandidatesCount: Int,
        val mediaTypes: List<String> = emptyList(),
        val dataSource: String, // "RENDERED_DOM", "EMBEDDED_JSON", "PERFORMANCE_TIMING", "MOCK_DATA", "NONE"
        val httpStatus: Int? = null,
        val retryAfterSeconds: Int? = null,
        val failureCategory: String? = null,
        val failureStage: String? = null
    )

    fun registerActiveWebView(webView: WebView) {
        activeWebViewRef = WeakReference(webView)
        try {
            val attached = webView.isAttachedToWindow
            val url = webView.url ?: "empty"
            val safeUrl = if (url.contains("?")) url.substringBefore("?") else url
            Log.d(
                TAG,
                "[REGISTER_VIEW] Source: BrowserScreen | Attached: $attached | Dimensions: ${webView.width}x${webView.height} | Url: $safeUrl"
            )
        } catch (_: Throwable) {}
    }

    fun clearActiveWebView(webView: WebView) {
        if (activeWebViewRef?.get() == webView) {
            activeWebViewRef = null
            try {
                Log.d(TAG, "[CLEAR_VIEW] Source: BrowserScreen | Result: Reference cleared")
            } catch (_: Throwable) {}
        }
    }

    fun getActiveWebView(): WebView? {
        return activeWebViewRef?.get()
    }

    fun getActiveUrl(): String? {
        return getActiveWebView()?.url
    }

    fun getAuthenticatedCookies(): String? {
        return try {
            val cm = CookieManager.getInstance()
            val raw = cm.getCookie("https://www.instagram.com")
            if (raw.isNullOrBlank()) null else raw
        } catch (_: Throwable) {
            null
        }
    }

    fun hasAuthenticatedSession(): Boolean {
        val cookies = getAuthenticatedCookies() ?: return false
        return cookies.contains("sessionid=")
    }

    fun getViewerUserId(): String? {
        val cookies = getAuthenticatedCookies() ?: return null
        val match = Pattern.compile("ds_user_id=([^;]+)").matcher(cookies)
        return if (match.find()) match.group(1) else null
    }

    /**
     * Privacy-safe runtime diagnostics.
     * Records operation, execution path, WebView dimensions, readiness state,
     * candidate counts, and failure stages without leaking credentials or tokens.
     */
    fun recordDiagnostic(report: DiagnosticReport) {
        try {
            val statusStr = if (report.httpStatus != null) " | HTTP: ${report.httpStatus}" else ""
            val retryStr = if (report.retryAfterSeconds != null) " | Retry-After: ${report.retryAfterSeconds}s" else ""
            val failureStr = if (report.failureCategory != null) " | Failure: ${report.failureCategory} [Stage: ${report.failureStage}]" else " | Success"
            val mediaStr = if (report.mediaCandidatesCount > 0) " | MediaCount: ${report.mediaCandidatesCount} (${report.mediaTypes.joinToString(",")})" else " | MediaCount: 0"

            Log.d(
                TAG,
                "[${report.operation}] Method: ${report.method} | Target: ${report.targetUsername} (Matched: ${report.targetMatched}) | " +
                "WebViewAlive: ${report.webViewAlive} (Attached: ${report.webViewAttached}, Dims: ${report.webViewWidth}x${report.webViewHeight}, UrlMatches: ${report.currentUrlMatches}) | " +
                "JS: ${report.jsExecutionState} | ViewerFound: ${report.viewerDetected} | Source: ${report.dataSource}$mediaStr$statusStr$retryStr$failureStr"
            )
        } catch (_: Throwable) {}
    }

    fun logDiagnostic(
        stage: String,
        source: String,
        target: String,
        result: String,
        status: Int? = null,
        errorCategory: String? = null
    ) {
        try {
            val statusStr = if (status != null) " [HTTP $status]" else ""
            val categoryStr = if (errorCategory != null) " Category: $errorCategory" else ""
            Log.d(TAG, "[$stage] Source: $source | Target: $target$statusStr | Result: $result$categoryStr")
        } catch (_: Throwable) {}
    }
}
