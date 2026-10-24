package com.example.core.media.extraction

import android.content.Context
import android.webkit.CookieManager
import com.example.core.media.extraction.instagram.InstagramSessionBridge
import com.example.core.media.extraction.instagram.InstagramStoryFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class InstagramStoryMediaExtractor(
    private val context: Context? = null,
    private val client: OkHttpClient = defaultHttpClient()
) : MediaExtractor {

    companion object {
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private val STORY_URL_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?instagram\\.com/stories/@?([A-Za-z0-9._]+)(?:/(\\d+))?/?.*",
            Pattern.CASE_INSENSITIVE
        )

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    override fun canHandle(url: String): Boolean {
        if (url.isBlank()) return false
        val clean = url.trim()
        return clean.contains("instagram.com", ignoreCase = true) &&
               clean.contains("/stories/", ignoreCase = true)
    }

    fun extractUsernameAndStoryId(url: String): Pair<String, String?>? {
        val clean = url.trim().substringBefore('?').substringBefore('#')
        val matcher = STORY_URL_PATTERN.matcher(clean)
        return if (matcher.find()) {
            val username = (matcher.group(1) ?: return null).removePrefix("@").trim()
            val storyId = matcher.group(2)
            Pair(username, storyId)
        } else null
    }

    private fun getInstagramCookies(): String? {
        return try {
            val cookieManager = CookieManager.getInstance()
            val raw = cookieManager.getCookie("https://www.instagram.com")
            if (raw.isNullOrBlank()) null else raw
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        val (rawUsername, targetStoryId) = extractUsernameAndStoryId(url)
            ?: return@withContext ExtractionResult.Error("Could not parse Instagram Story URL: $url")

        val username = rawUsername.removePrefix("@").trim()

        // 1. Session verification: Stories strictly require an active login session
        val cookies = getInstagramCookies()
        val hasSession = (cookies != null && cookies.contains("sessionid=")) || InstagramSessionBridge.hasAuthenticatedSession()
        if (!hasSession) {
            return@withContext ExtractionResult.LoginRequired(
                "Instagram Stories require an active login session. Please log in to Instagram in YAS Browser and try viewing the Story again."
            )
        }

        // 2. Isolated WebView-session extraction pipeline: renders Story page using authenticated session
        val webViewResult = InstagramStoryFlow.extractStory(
            context = context,
            targetUsername = username,
            targetStoryId = targetStoryId,
            sourceUrl = url
        )

        return@withContext webViewResult
    }
}
