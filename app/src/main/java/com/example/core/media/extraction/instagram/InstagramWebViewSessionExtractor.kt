package com.example.core.media.extraction.instagram

import android.content.Context
import android.webkit.WebView
import com.example.core.media.extraction.ExtractionResult

/**
 * Backward compatibility facade delegating to modular InstagramSessionBridge,
 * InstagramStoryFlow, and InstagramProfileFlow.
 */
object InstagramWebViewSessionExtractor {

    fun registerActiveWebView(webView: WebView) {
        InstagramSessionBridge.registerActiveWebView(webView)
    }

    fun clearActiveWebView(webView: WebView) {
        InstagramSessionBridge.clearActiveWebView(webView)
    }

    fun getAuthenticatedCookies(): String? {
        return InstagramSessionBridge.getAuthenticatedCookies()
    }

    fun hasValidSession(): Boolean {
        return InstagramSessionBridge.hasAuthenticatedSession()
    }

    fun getViewerUserId(): String? {
        return InstagramSessionBridge.getViewerUserId()
    }

    suspend fun extractProfile(
        context: Context?,
        targetUsername: String,
        sourceUrl: String
    ): ExtractionResult {
        return InstagramProfileFlow.extractProfile(context, targetUsername, sourceUrl)
            ?: ExtractionResult.NoFormatsFound("Could not resolve profile picture for '@$targetUsername'.")
    }

    suspend fun extractStories(
        context: Context?,
        targetUsername: String,
        targetStoryId: String?,
        sourceUrl: String
    ): ExtractionResult {
        return InstagramStoryFlow.extractStory(context, targetUsername, targetStoryId, sourceUrl)
    }
}
