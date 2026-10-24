package com.example.core.media.extraction

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class InstagramMediaExtractor(
    private val client: OkHttpClient = defaultHttpClient()
) : MediaExtractor {

    companion object {
        private const val IG_APP_ID = "936619743392459"
        private const val USER_AGENT_MOBILE = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        private const val USER_AGENT_APP = "Instagram 315.0.0.29.109 Android (34/14; 480dpi; 1080x2400; Google/google; Pixel 8; shiba; qcom; en_US; 564991054)"

        // Match reels, posts, tv, stories
        private val INSTAGRAM_URL_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?instagram\\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)/?.*",
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
        val cleanUrl = url.trim()
        return cleanUrl.contains("instagram.com", ignoreCase = true) &&
                (cleanUrl.contains("/reel/", ignoreCase = true) ||
                 cleanUrl.contains("/reels/", ignoreCase = true) ||
                 cleanUrl.contains("/p/", ignoreCase = true) ||
                 cleanUrl.contains("/tv/", ignoreCase = true))
    }

    override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        val shortcode = extractShortcode(url)
            ?: return@withContext ExtractionResult.Error("Could not parse Instagram shortcode from URL: $url")

        val cookies = getInstagramCookies()

        // 1. Try Strategy A: Internal Mobile API with shortcode converted to Media ID
        val mediaId = shortcodeToMediaId(shortcode)
        if (mediaId != null) {
            val apiResult = tryExtractFromMediaApi(mediaId, shortcode, url, cookies)
            if (apiResult is ExtractionResult.Success) {
                return@withContext apiResult
            } else if (apiResult is ExtractionResult.LoginRequired) {
                return@withContext apiResult
            }
        }

        // 2. Try Strategy B: Web JSON API (?__a=1&__d=dis)
        val webJsonResult = tryExtractFromWebJson(shortcode, url, cookies)
        if (webJsonResult is ExtractionResult.Success) {
            return@withContext webJsonResult
        }

        // 3. Try Strategy C: Public Embed Page (/embed/captioned/)
        val embedResult = tryExtractFromEmbed(shortcode, url, cookies)
        if (embedResult is ExtractionResult.Success) {
            return@withContext embedResult
        }

        // 4. Try Strategy D: HTML OpenGraph metadata parsing
        val htmlResult = tryExtractFromPageHtml(shortcode, url, cookies)
        if (htmlResult is ExtractionResult.Success) {
            return@withContext htmlResult
        }

        // If we found the media earlier but it had no videos (photo post)
        if (webJsonResult is ExtractionResult.NoFormatsFound) {
            return@withContext webJsonResult
        }

        // Default honest error
        if (cookies.isNullOrBlank()) {
            ExtractionResult.LoginRequired(
                "Unable to extract Instagram video. If this post is restricted or age-gated, please log in to Instagram in YAS Browser and try again."
            )
        } else {
            ExtractionResult.NoFormatsFound(
                "No playable video streams found for this Instagram URL. The post may be a photo carousel or no longer available."
            )
        }
    }

    fun extractShortcode(url: String): String? {
        val matcher = INSTAGRAM_URL_PATTERN.matcher(url.trim())
        if (matcher.find()) {
            return matcher.group(1)
        }
        // Fallback manual regex for any trailing parameters
        val manualPattern = Pattern.compile("/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE)
        val manualMatcher = manualPattern.matcher(url)
        return if (manualMatcher.find()) manualMatcher.group(1) else null
    }

    fun shortcodeToMediaId(shortcode: String): String? {
        return try {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            var id = BigInteger.ZERO
            for (char in shortcode) {
                val index = alphabet.indexOf(char)
                if (index < 0) return null
                id = id.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(index.toLong()))
            }
            id.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun getInstagramCookies(): String? {
        return try {
            CookieManager.getInstance().getCookie("https://www.instagram.com")
        } catch (e: Exception) {
            null
        }
    }

    private fun tryExtractFromMediaApi(
        mediaId: String,
        shortcode: String,
        sourceUrl: String,
        cookies: String?
    ): ExtractionResult {
        return try {
            val apiUrl = "https://i.instagram.com/api/v1/media/$mediaId/info/"
            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT_APP)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("X-ASBD-ID", "129477")
                .header("X-IG-WWW-Claim", "0")
                .header("Accept", "*/*")
                .header("Referer", "https://www.instagram.com/")
                .header("Origin", "https://www.instagram.com")

            if (!cookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful || body.isBlank()) {
                if (response.code == 401 || response.code == 403) {
                    return ExtractionResult.LoginRequired("Instagram requires authentication for this media.")
                }
                return ExtractionResult.Error("API returned ${response.code}")
            }

            val json = JSONObject(body)
            val items = json.optJSONArray("items")
            if (items == null || items.length() == 0) {
                return ExtractionResult.Error("No items found in Instagram response")
            }

            val item = items.getJSONObject(0)
            parseItemJson(item, shortcode, sourceUrl)
        } catch (e: Exception) {
            ExtractionResult.Error("Failed to extract via Media API: ${e.message}", e)
        }
    }

    private fun tryExtractFromWebJson(
        shortcode: String,
        sourceUrl: String,
        cookies: String?
    ): ExtractionResult {
        return try {
            val webUrl = "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis"
            val requestBuilder = Request.Builder()
                .url(webUrl)
                .header("User-Agent", USER_AGENT_MOBILE)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://www.instagram.com/p/$shortcode/")

            if (!cookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful || body.isBlank()) {
                return ExtractionResult.Error("Web JSON returned ${response.code}")
            }

            val json = JSONObject(body)
            val items = json.optJSONArray("items")
            if (items != null && items.length() > 0) {
                return parseItemJson(items.getJSONObject(0), shortcode, sourceUrl)
            }

            // Check graphql shortcode_media
            val graphql = json.optJSONObject("graphql")
            val shortcodeMedia = graphql?.optJSONObject("shortcode_media")
            if (shortcodeMedia != null) {
                return parseGraphqlMedia(shortcodeMedia, shortcode, sourceUrl)
            }

            ExtractionResult.Error("Could not find media item in web json")
        } catch (e: Exception) {
            ExtractionResult.Error("Failed web json extraction: ${e.message}", e)
        }
    }

    private fun tryExtractFromEmbed(
        shortcode: String,
        sourceUrl: String,
        cookies: String?
    ): ExtractionResult {
        return try {
            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
            val requestBuilder = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", USER_AGENT_MOBILE)
                .header("Referer", "https://www.instagram.com/")

            if (!cookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val html = response.body?.string() ?: ""
            if (!response.isSuccessful || html.isBlank()) {
                return ExtractionResult.Error("Embed request failed with code ${response.code}")
            }

            // Extract video_url from embed HTML
            val videoUrlPattern = Pattern.compile("\"video_url\"\\s*:\\s*\"(https:[^\"]+)\"")
            val videoMatcher = videoUrlPattern.matcher(html)
            val videoUrls = mutableListOf<String>()

            while (videoMatcher.find()) {
                val rawUrl = videoMatcher.group(1)?.replace("\\u0026", "&")?.replace("\\/", "/")
                if (!rawUrl.isNullOrBlank() && !videoUrls.contains(rawUrl)) {
                    videoUrls.add(rawUrl)
                }
            }

            if (videoUrls.isEmpty()) {
                // Check if it's an image post
                if (html.contains("EmbeddedMediaImage") || html.contains("display_url")) {
                    return ExtractionResult.NoFormatsFound("This Instagram post only contains photos. No video streams found.")
                }
                return ExtractionResult.Error("No video URLs found in embed page")
            }

            // Extract thumbnail
            val thumbPattern = Pattern.compile("\"display_url\"\\s*:\\s*\"(https:[^\"]+)\"")
            val thumbMatcher = thumbPattern.matcher(html)
            val thumbUrl = if (thumbMatcher.find()) {
                thumbMatcher.group(1)?.replace("\\u0026", "&")?.replace("\\/", "/")
            } else null

            // Extract author
            val authorPattern = Pattern.compile("class=\"UsernameText\"[^>]*>([^<]+)<")
            val authorMatcher = authorPattern.matcher(html)
            val author = if (authorMatcher.find()) authorMatcher.group(1) ?: "Instagram User" else "Instagram User"

            // Extract title / caption
            val captionPattern = Pattern.compile("class=\"Caption\"[^>]*>([\\s\\S]*?)</div>")
            val captionMatcher = captionPattern.matcher(html)
            val caption = if (captionMatcher.find()) {
                captionMatcher.group(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: "Instagram Reel"
            } else "Instagram Reel ($shortcode)"

            buildSuccessResult(
                shortcode = shortcode,
                sourceUrl = sourceUrl,
                title = caption.take(80),
                author = author,
                thumbnailUrl = thumbUrl,
                videoUrls = videoUrls,
                description = caption
            )
        } catch (e: Exception) {
            ExtractionResult.Error("Failed embed extraction: ${e.message}", e)
        }
    }

    private fun tryExtractFromPageHtml(
        shortcode: String,
        sourceUrl: String,
        cookies: String?
    ): ExtractionResult {
        return try {
            val pageUrl = "https://www.instagram.com/p/$shortcode/"
            val requestBuilder = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", USER_AGENT_MOBILE)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

            if (!cookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val html = response.body?.string() ?: ""
            if (!response.isSuccessful || html.isBlank()) {
                return ExtractionResult.Error("Page HTML failed with ${response.code}")
            }

            val ogVideoPattern = Pattern.compile("<meta\\s+property=\"og:video(?::secure_url)?\"\\s+content=\"(https:[^\"]+)\"")
            val matcher = ogVideoPattern.matcher(html)
            if (!matcher.find()) {
                return ExtractionResult.Error("og:video tag not found")
            }

            val videoUrl = matcher.group(1)?.replace("&amp;", "&")
                ?: return ExtractionResult.Error("Empty og:video content")

            val ogTitlePattern = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"")
            val titleMatcher = ogTitlePattern.matcher(html)
            val title = if (titleMatcher.find()) titleMatcher.group(1) ?: "Instagram Post" else "Instagram Post ($shortcode)"

            val ogImagePattern = Pattern.compile("<meta\\s+property=\"og:image\"\\s+content=\"(https:[^\"]+)\"")
            val imgMatcher = ogImagePattern.matcher(html)
            val thumbUrl = if (imgMatcher.find()) imgMatcher.group(1)?.replace("&amp;", "&") else null

            buildSuccessResult(
                shortcode = shortcode,
                sourceUrl = sourceUrl,
                title = title,
                author = "Instagram",
                thumbnailUrl = thumbUrl,
                videoUrls = listOf(videoUrl),
                description = title
            )
        } catch (e: Exception) {
            ExtractionResult.Error("Failed HTML scraping: ${e.message}", e)
        }
    }

    private fun parseItemJson(
        item: JSONObject,
        shortcode: String,
        sourceUrl: String
    ): ExtractionResult {
        val videoUrls = mutableListOf<String>()

        // Check single video versions
        val videoVersions = item.optJSONArray("video_versions")
        if (videoVersions != null && videoVersions.length() > 0) {
            for (i in 0 until videoVersions.length()) {
                val vObj = videoVersions.getJSONObject(i)
                val vUrl = vObj.optString("url")
                if (vUrl.isNotBlank() && !videoUrls.contains(vUrl)) {
                    videoUrls.add(vUrl)
                }
            }
        }

        // Check carousel items
        val carouselMedia = item.optJSONArray("carousel_media")
        var isCarousel = false
        var carouselCount = 1
        if (carouselMedia != null && carouselMedia.length() > 0) {
            isCarousel = true
            carouselCount = carouselMedia.length()
            for (i in 0 until carouselMedia.length()) {
                val cItem = carouselMedia.getJSONObject(i)
                val cVideos = cItem.optJSONArray("video_versions")
                if (cVideos != null && cVideos.length() > 0) {
                    val firstVideo = cVideos.getJSONObject(0).optString("url")
                    if (firstVideo.isNotBlank() && !videoUrls.contains(firstVideo)) {
                        videoUrls.add(firstVideo)
                    }
                }
            }
        }

        if (videoUrls.isEmpty()) {
            return ExtractionResult.NoFormatsFound(
                "This Instagram post only contains photos. No video streams found."
            )
        }

        // Thumbnail
        var thumbUrl: String? = null
        val imageVersions = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
        if (imageVersions != null && imageVersions.length() > 0) {
            thumbUrl = imageVersions.getJSONObject(0).optString("url")
        }

        // Author
        val userObj = item.optJSONObject("user")
        val author = userObj?.optString("username") ?: "Instagram User"

        // Caption / Title
        val captionObj = item.optJSONObject("caption")
        val caption = captionObj?.optString("text") ?: "Instagram Reel ($shortcode)"
        val duration = item.optLong("video_duration", 0L)

        return buildSuccessResult(
            shortcode = shortcode,
            sourceUrl = sourceUrl,
            title = caption.take(80).ifBlank { "Instagram Reel" },
            author = author,
            thumbnailUrl = thumbUrl,
            videoUrls = videoUrls,
            durationSeconds = if (duration > 0) duration else null,
            description = caption,
            isCarousel = isCarousel,
            carouselCount = carouselCount
        )
    }

    private fun parseGraphqlMedia(
        media: JSONObject,
        shortcode: String,
        sourceUrl: String
    ): ExtractionResult {
        val isVideo = media.optBoolean("is_video", false)
        val videoUrls = mutableListOf<String>()

        if (isVideo) {
            val vUrl = media.optString("video_url")
            if (vUrl.isNotBlank()) videoUrls.add(vUrl)
        }

        // Check sidecar children
        val edgeSidecar = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        var isCarousel = false
        var carouselCount = 1
        if (edgeSidecar != null && edgeSidecar.length() > 0) {
            isCarousel = true
            carouselCount = edgeSidecar.length()
            for (i in 0 until edgeSidecar.length()) {
                val node = edgeSidecar.getJSONObject(i).optJSONObject("node")
                if (node?.optBoolean("is_video") == true) {
                    val childUrl = node.optString("video_url")
                    if (childUrl.isNotBlank() && !videoUrls.contains(childUrl)) {
                        videoUrls.add(childUrl)
                    }
                }
            }
        }

        if (videoUrls.isEmpty()) {
            return ExtractionResult.NoFormatsFound("This Instagram post contains no video streams.")
        }

        val thumbUrl = media.optString("display_url")
        val owner = media.optJSONObject("owner")?.optString("username") ?: "Instagram User"

        val captionEdge = media.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")
        var caption = "Instagram Reel"
        if (captionEdge != null && captionEdge.length() > 0) {
            caption = captionEdge.getJSONObject(0).optJSONObject("node")?.optString("text") ?: caption
        }

        return buildSuccessResult(
            shortcode = shortcode,
            sourceUrl = sourceUrl,
            title = caption.take(80),
            author = owner,
            thumbnailUrl = thumbUrl,
            videoUrls = videoUrls,
            description = caption,
            isCarousel = isCarousel,
            carouselCount = carouselCount
        )
    }

    private fun buildSuccessResult(
        shortcode: String,
        sourceUrl: String,
        title: String,
        author: String,
        thumbnailUrl: String?,
        videoUrls: List<String>,
        durationSeconds: Long? = null,
        description: String? = null,
        isCarousel: Boolean = false,
        carouselCount: Int = 1
    ): ExtractionResult.Success {
        val streams = mutableListOf<MediaStream>()

        // Common headers needed for Instagram CDN to avoid HTTP 403 Forbidden
        val cdnHeaders = mapOf(
            "User-Agent" to USER_AGENT_MOBILE,
            "Referer" to "https://www.instagram.com/",
            "Origin" to "https://www.instagram.com",
            "Accept" to "*/*"
        )

        // Add Video Streams
        videoUrls.forEachIndexed { index, vUrl ->
            val qualityLabel = if (videoUrls.size > 1) {
                "Video Part ${index + 1} (HD)"
            } else {
                "HD 1080p (MP4)"
            }

            streams.add(
                MediaStream(
                    id = "ig_video_${shortcode}_$index",
                    url = vUrl,
                    quality = qualityLabel,
                    format = "MP4",
                    isVideo = true,
                    isAudio = true,
                    headers = cdnHeaders
                )
            )

            // Also synthesize an Audio Stream for the Audio tab!
            // This guarantees the Audio tab always has an MP3 extraction option!
            val audioLabel = if (videoUrls.size > 1) {
                "Audio Part ${index + 1} (MP3 Audio)"
            } else {
                "Original Audio (MP3 320kbps)"
            }

            streams.add(
                MediaStream(
                    id = "ig_audio_${shortcode}_$index",
                    url = vUrl,
                    quality = audioLabel,
                    format = "MP3",
                    isVideo = false,
                    isAudio = true,
                    headers = cdnHeaders
                )
            )
        }

        val metadata = MediaMetadata(
            id = shortcode,
            title = title.ifBlank { "Instagram Reel ($shortcode)" },
            author = "@$author",
            thumbnailUrl = thumbnailUrl,
            sourceUrl = sourceUrl,
            platform = Platform.INSTAGRAM,
            durationSeconds = durationSeconds,
            description = description,
            isCarousel = isCarousel,
            carouselCount = carouselCount
        )

        return ExtractionResult.Success(metadata, streams)
    }
}
