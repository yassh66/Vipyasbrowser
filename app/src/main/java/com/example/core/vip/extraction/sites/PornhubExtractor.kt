package com.example.core.vip.extraction.sites

import com.example.core.vip.extraction.VIPExtractionResult
import com.example.core.vip.extraction.VIPMediaExtractor
import com.example.core.vip.extraction.VIPMediaMetadata
import com.example.core.vip.extraction.VIPMediaStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class PornhubExtractor(
    private val client: OkHttpClient = defaultHttpClient()
) : VIPMediaExtractor {

    override val siteName: String = "Pornhub"

    class SafeRedirectInterceptor(
        private val maxRedirects: Int = 3
    ) : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            var request = chain.request()
            var response = chain.proceed(request)
            var redirectCount = 0

            while (response.isRedirect && redirectCount < maxRedirects) {
                val location = response.header("Location")
                if (location.isNullOrBlank()) break

                val newUrl = request.url.resolve(location)
                if (newUrl == null || !isAllowedHost(newUrl.host)) {
                    response.close()
                    throw java.io.IOException(
                        "Redirect to unapproved host blocked: ${newUrl?.host ?: location}"
                    )
                }

                response.close()
                redirectCount++
                request = request.newBuilder().url(newUrl).build()
                response = chain.proceed(request)
            }
            return response
        }
    }

    companion object {
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        const val REFERER_URL = "https://www.pornhub.com/"
        const val ORIGIN_URL = "https://www.pornhub.com"
        const val MAX_RESPONSE_SIZE_BYTES = 2 * 1024 * 1024L // 2 MiB limit

        private val VIEW_KEY_PARAM_PATTERN = Pattern.compile(
            "(?:[?&]|^)viewkey=([a-zA-Z0-9_-]+)",
            Pattern.CASE_INSENSITIVE
        )

        private val EMBED_OR_SHORT_PATTERN = Pattern.compile(
            "https?://(?:[a-zA-Z0-9.-]+\\.)?(?:pornhub\\.(?:com|org)/embed/|phub\\.me/)([a-zA-Z0-9_-]+)",
            Pattern.CASE_INSENSITIVE
        )

        fun isAllowedHost(host: String?): Boolean {
            if (host.isNullOrBlank()) return false
            val cleanHost = host.lowercase()
            val allowedExact = setOf(
                "pornhub.com",
                "pornhub.org",
                "pornhubpremium.com",
                "phub.me"
            )
            if (allowedExact.contains(cleanHost)) return true
            val allowedSuffixes = listOf(
                ".pornhub.com",
                ".pornhub.org",
                ".pornhubpremium.com"
            )
            return allowedSuffixes.any { cleanHost.endsWith(it) }
        }

        fun isApiOrManifestUrl(url: String): Boolean {
            if (url.isBlank()) return false
            val clean = url.lowercase()
            return clean.contains("/get_media") ||
                    clean.contains("video/get_media") ||
                    clean.contains("/api/") ||
                    clean.contains(".json") ||
                    clean.contains("format=json") ||
                    clean.contains("type=json") ||
                    clean.contains("response_type=json")
        }

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(SafeRedirectInterceptor())
                .build()
        }
    }

    override fun canHandle(url: String): Boolean {
        if (url.isBlank()) return false
        val clean = url.trim()

        val uri = try {
            java.net.URI(clean)
        } catch (_: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host ?: return false
        if (!isAllowedHost(host)) return false

        if (uri.port != -1 && uri.port != 80 && uri.port != 443) return false
        if (!uri.userInfo.isNullOrBlank()) return false

        return extractViewKey(clean) != null
    }

    fun extractViewKey(url: String): String? {
        if (url.isBlank()) return null
        val clean = url.trim()
        val uri = try {
            java.net.URI(clean)
        } catch (_: Exception) {
            return null
        }

        val host = uri.host ?: return null
        if (!isAllowedHost(host)) return null
        if (!uri.userInfo.isNullOrBlank()) return null
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) return null

        val path = uri.path ?: ""
        val query = uri.query ?: ""

        if (path.equals("/view_video.php", ignoreCase = true) || path.contains("view_video.php", ignoreCase = true)) {
            val paramMatcher = VIEW_KEY_PARAM_PATTERN.matcher(if (query.isNotBlank()) query else clean)
            if (paramMatcher.find()) {
                return paramMatcher.group(1)
            }
        }

        val embedMatcher = EMBED_OR_SHORT_PATTERN.matcher(clean)
        if (embedMatcher.find()) {
            return embedMatcher.group(1)
        }

        return null
    }

    override suspend fun extract(url: String): VIPExtractionResult = withContext(Dispatchers.IO) {
        val viewKey = extractViewKey(url)
            ?: return@withContext VIPExtractionResult.Error("Could not parse Pornhub viewkey from URL: $url")

        val canonicalWatchUrl = "https://www.pornhub.com/view_video.php?viewkey=$viewKey"

        try {
            val request = Request.Builder()
                .url(canonicalWatchUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", REFERER_URL)
                .header("Cookie", buildCookieHeader())
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                return@withContext VIPExtractionResult.Error("Network error accessing Pornhub: ${e.message}", e)
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val message = resp.message.ifBlank { "HTTP $code" }
                    return@withContext VIPExtractionResult.HttpError(code, message)
                }

                val body = resp.body
                if (body == null) {
                    return@withContext VIPExtractionResult.Error("Empty response body received from Pornhub")
                }

                val headerContentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val bodyContentLength = body.contentLength()
                val declaredLength = if (headerContentLength > 0L) headerContentLength else bodyContentLength
                if (declaredLength > MAX_RESPONSE_SIZE_BYTES) {
                    return@withContext VIPExtractionResult.Error(
                        "Response body exceeds maximum allowed size of 2 MiB (Content-Length: $declaredLength bytes)"
                    )
                }

                val output = java.io.ByteArrayOutputStream()
                val inputStream = body.byteStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    totalRead += bytesRead
                    if (totalRead > MAX_RESPONSE_SIZE_BYTES) {
                        return@withContext VIPExtractionResult.Error(
                            "Response body exceeds maximum allowed size of 2 MiB"
                        )
                    }
                    output.write(buffer, 0, bytesRead)
                }

                val html = output.toString("UTF-8")
                if (html.isBlank()) {
                    return@withContext VIPExtractionResult.Error("Empty response body received from Pornhub")
                }

                parseHtml(html, viewKey, canonicalWatchUrl)
            }
        } catch (e: Exception) {
            VIPExtractionResult.Error("Extraction failed: ${e.message}", e)
        }
    }

    private fun buildCookieHeader(): String {
        val defaultCookies = "accessAgeDisclaimerPH=1; age_verified=1; hasVisited=1"
        val webViewCookies = try {
            android.webkit.CookieManager.getInstance().getCookie("https://www.pornhub.com")
        } catch (_: Throwable) {
            null
        }
        return if (!webViewCookies.isNullOrBlank()) {
            "$defaultCookies; $webViewCookies"
        } else {
            defaultCookies
        }
    }

    fun parseHtml(html: String, viewKey: String, sourceUrl: String): VIPExtractionResult {
        // 1. Check for Challenge / Bot Protection pages
        if (html.contains("cf-browser-verification", ignoreCase = true) ||
            html.contains("challenge-platform", ignoreCase = true) ||
            html.contains("turnstile", ignoreCase = true) ||
            html.contains("cf-chl-", ignoreCase = true) ||
            html.contains("<title>Just a moment...</title>", ignoreCase = true)
        ) {
            return VIPExtractionResult.ChallengeDetected(
                challengeType = "CLOUDFLARE_CHALLENGE",
                message = "Cloudflare or bot protection challenge detected on page. Access cannot be automated."
            )
        }

        // 2. Extract Streams first to eliminate false positives from boilerplate footer/compliance text
        val (streams, hasAdaptiveOnly) = extractStreams(html, viewKey)

        if (streams.isNotEmpty()) {
            val title = extractTitle(html, viewKey)
            val author = extractAuthor(html)
            val thumbnailUrl = extractThumbnail(html)
            val durationSeconds = extractDuration(html)

            val metadata = VIPMediaMetadata(
                id = viewKey,
                title = title,
                author = author,
                thumbnailUrl = thumbnailUrl,
                sourceUrl = sourceUrl,
                siteName = siteName,
                durationSeconds = durationSeconds
            )

            return VIPExtractionResult.Success(metadata, streams)
        }

        // 3. When streams are absent, accurately classify genuine gates and access barriers

        // A. Genuine Regional Age Verification or Geo-blocking
        val hasLegalAgeGate = html.contains("class=\"legal-age-gate\"", ignoreCase = true) ||
                html.contains("class='legal-age-gate'", ignoreCase = true) ||
                html.contains("legal-age-gate", ignoreCase = true) ||
                html.contains("id=\"modal-age-verification\"", ignoreCase = true) ||
                html.contains("age-verification-modal", ignoreCase = true) ||
                html.contains("restricted in your area", ignoreCase = true) ||
                html.contains("not available in your country", ignoreCase = true) ||
                html.contains("blocked in your region", ignoreCase = true) ||
                html.contains("<title>Access Restricted</title>", ignoreCase = true) ||
                html.contains("verify your age to view", ignoreCase = true)

        if (hasLegalAgeGate) {
            return VIPExtractionResult.AccessRestricted(
                reason = "Page access is restricted by regional or age-verification gate. Please complete verification on the official website."
            )
        }

        // B. Check for Premium / Account Access Required
        if (html.contains("this video is for premium members only", ignoreCase = true) ||
            html.contains("join premium to watch", ignoreCase = true) ||
            html.contains("\"is_premium\":true", ignoreCase = true)
        ) {
            return VIPExtractionResult.AccessRestricted(
                reason = "This video requires a paid premium membership or account login."
            )
        }

        // C. Check for Removed / Unavailable Videos
        if (html.contains("video has been removed", ignoreCase = true) ||
            html.contains("flagged for review", ignoreCase = true) ||
            html.contains("this video is unavailable", ignoreCase = true) ||
            html.contains("this video is private", ignoreCase = true)
        ) {
            return VIPExtractionResult.NoFormatsFound(
                reason = "Video has been removed or is unavailable."
            )
        }

        // D. Adaptive-only (HLS/DASH only)
        if (hasAdaptiveOnly) {
            return VIPExtractionResult.NoFormatsFound(
                reason = "Unsupported adaptive-only media. Only HLS/DASH streams were found; progressive MP4 streams are not available for this video."
            )
        }

        // E. Generic No Formats
        return VIPExtractionResult.NoFormatsFound(
            reason = "No playable progressive MP4 video streams found on page."
        )
    }

    private fun extractTitle(html: String, fallbackId: String): String {
        // OpenGraph title
        val ogMatch = Pattern.compile("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogMatch.find()) {
            val raw = ogMatch.group(1)?.trim()
            if (!raw.isNullOrBlank()) return cleanTitle(raw)
        }

        // Twitter title
        val twMatch = Pattern.compile("<meta\\s+name=[\"']twitter:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (twMatch.find()) {
            val raw = twMatch.group(1)?.trim()
            if (!raw.isNullOrBlank()) return cleanTitle(raw)
        }

        // HTML <title> tag
        val titleMatch = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (titleMatch.find()) {
            val raw = titleMatch.group(1)?.trim()
            if (!raw.isNullOrBlank()) return cleanTitle(raw)
        }

        // Video title in flashvars JSON
        val jsonTitleMatch = Pattern.compile("[\"']video_title[\"']\\s*:\\s*[\"']([^\"']+)[\"']").matcher(html)
        if (jsonTitleMatch.find()) {
            val raw = jsonTitleMatch.group(1)?.trim()
            if (!raw.isNullOrBlank()) return cleanTitle(raw)
        }

        return "Pornhub Video ($fallbackId)"
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(" - Pornhub.com", "")
            .replace("- Pornhub.com", "")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun extractAuthor(html: String): String {
        val authorMatch = Pattern.compile("class=[\"'][^\"']*usernameBadgesWrapper[^\"']*[\"'][^>]*>\\s*<a[^>]*>([^<]+)</a>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (authorMatch.find()) {
            val name = authorMatch.group(1)?.trim()
            if (!name.isNullOrBlank()) return name
        }

        val uploaderMatch = Pattern.compile("class=[\"']bolded[\"']>\\s*<a[^>]*href=[\"']/(?:model|channels|users)/[^\"']+[\"'][^>]*>([^<]+)</a>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (uploaderMatch.find()) {
            val name = uploaderMatch.group(1)?.trim()
            if (!name.isNullOrBlank()) return name
        }

        return "Pornhub Creator"
    }

    private fun extractThumbnail(html: String): String? {
        val ogImgMatch = Pattern.compile("<meta\\s+property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogImgMatch.find()) {
            val img = ogImgMatch.group(1)?.trim()
            if (!img.isNullOrBlank() && img.startsWith("http")) return img
        }

        val jsonImgMatch = Pattern.compile("[\"']image_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']").matcher(html)
        if (jsonImgMatch.find()) {
            val img = jsonImgMatch.group(1)?.replace("\\/", "/")?.trim()
            if (!img.isNullOrBlank() && img.startsWith("http")) return img
        }

        return null
    }

    private fun extractDuration(html: String): Long? {
        val durMatch = Pattern.compile("<meta\\s+property=[\"']video:duration[\"']\\s+content=[\"'](\\d+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (durMatch.find()) {
            return durMatch.group(1)?.toLongOrNull()
        }
        val jsonDurMatch = Pattern.compile("[\"']video_duration[\"']\\s*:\\s*[\"']?(\\d+)[\"']?").matcher(html)
        if (jsonDurMatch.find()) {
            return jsonDurMatch.group(1)?.toLongOrNull()
        }
        return null
    }

    private fun extractStreams(html: String, viewKey: String): Pair<List<VIPMediaStream>, Boolean> {
        val streams = mutableListOf<VIPMediaStream>()
        var hasAdaptiveOnly = false

        val streamHeaders = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to REFERER_URL,
            "Origin" to ORIGIN_URL,
            "Cookie" to buildCookieHeader()
        )

        // Strategy 1: Find mediaDefinitions array
        val mediaDefRegex = Pattern.compile("[\"']mediaDefinitions[\"']\\s*:\\s*(\\[.*?\\])(?=[,;}\\n\\r])", Pattern.DOTALL)
        var matcher = mediaDefRegex.matcher(html)
        var found = matcher.find()
        if (!found) {
            val fallbackRegex = Pattern.compile("[\"']mediaDefinitions[\"']\\s*:\\s*(\\[\\s*\\{.*?\\}\\s*\\])", Pattern.DOTALL)
            matcher = fallbackRegex.matcher(html)
            found = matcher.find()
        }

        if (found) {
            val jsonArrayStr = matcher.group(1)
            if (!jsonArrayStr.isNullOrBlank()) {
                try {
                    val array = JSONArray(jsonArrayStr)
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val format = item.optString("format", "").lowercase()
                        val rawUrl = item.optString("videoUrl", "")

                        val isAdaptive = format == "hls" || format == "mpd" ||
                                rawUrl.contains(".m3u8", ignoreCase = true) ||
                                rawUrl.contains(".mpd", ignoreCase = true) ||
                                rawUrl.contains("/hls/", ignoreCase = true) ||
                                rawUrl.contains("format=hls", ignoreCase = true) ||
                                rawUrl.contains("type=hls", ignoreCase = true) ||
                                rawUrl.contains("/m3u8", ignoreCase = true)

                        if (isAdaptive) {
                            hasAdaptiveOnly = true
                        }

                        val isApiEndpoint = isApiOrManifestUrl(rawUrl)

                        // Strictly ensure progressive media only - never label an adaptive stream or API endpoint as MP4
                        if (!isAdaptive && !isApiEndpoint && (format == "mp4" || format == "webm" || rawUrl.contains(".mp4", ignoreCase = true)) &&
                            rawUrl.isNotBlank() && rawUrl.startsWith("http")
                        ) {
                            val quality = item.optString("quality", "")
                            val height = item.optInt("height", 0)
                            val qualityLabel = when {
                                quality.isNotBlank() && quality.endsWith("p", ignoreCase = true) -> quality
                                quality.isNotBlank() -> "${quality}p"
                                height > 0 -> "${height}p"
                                else -> "Standard"
                            }

                            val cleanUrl = rawUrl.replace("\\/", "/")
                            val formatLabel = if (format == "webm") "WEBM" else "MP4"
                            streams.add(
                                VIPMediaStream(
                                    id = "ph_${viewKey}_${qualityLabel.replace(" ", "_")}_$i",
                                    url = cleanUrl,
                                    quality = "$qualityLabel ($formatLabel)",
                                    format = formatLabel,
                                    isVideo = true,
                                    isAudio = true,
                                    headers = streamHeaders
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Malformed JSON, fall through to alternative extraction
                }
            }
        }

        // Strategy 2: Flashvars JSON block
        if (streams.isEmpty()) {
            val flashvarsRegex = Pattern.compile("var\\s+flashvars_\\d+\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL)
            val fvMatcher = flashvarsRegex.matcher(html)
            if (fvMatcher.find()) {
                val fvStr = fvMatcher.group(1)
                if (!fvStr.isNullOrBlank()) {
                    try {
                        val fvObj = JSONObject(fvStr)
                        val mediaDefs = fvObj.optJSONArray("mediaDefinitions")
                        if (mediaDefs != null) {
                            for (i in 0 until mediaDefs.length()) {
                                val item = mediaDefs.optJSONObject(i) ?: continue
                                val format = item.optString("format", "").lowercase()
                                val rawUrl = item.optString("videoUrl", "")

                                val isAdaptive = format == "hls" || format == "mpd" ||
                                        rawUrl.contains(".m3u8", ignoreCase = true) ||
                                        rawUrl.contains(".mpd", ignoreCase = true) ||
                                        rawUrl.contains("/hls/", ignoreCase = true) ||
                                        rawUrl.contains("format=hls", ignoreCase = true) ||
                                        rawUrl.contains("type=hls", ignoreCase = true) ||
                                        rawUrl.contains("/m3u8", ignoreCase = true)

                                if (isAdaptive) {
                                    hasAdaptiveOnly = true
                                }

                                val isApiEndpoint = isApiOrManifestUrl(rawUrl)

                                if (!isAdaptive && !isApiEndpoint && (format == "mp4" || format == "webm" || rawUrl.contains(".mp4", ignoreCase = true)) &&
                                    rawUrl.isNotBlank() && rawUrl.startsWith("http")
                                ) {
                                    val quality = item.optString("quality", "")
                                    val height = item.optInt("height", 0)
                                    val qualityLabel = when {
                                        quality.isNotBlank() && quality.endsWith("p", ignoreCase = true) -> quality
                                        quality.isNotBlank() -> "${quality}p"
                                        height > 0 -> "${height}p"
                                        else -> "Standard"
                                    }
                                    val cleanUrl = rawUrl.replace("\\/", "/")
                                    val formatLabel = if (format == "webm") "WEBM" else "MP4"
                                    streams.add(
                                        VIPMediaStream(
                                            id = "ph_${viewKey}_${qualityLabel.replace(" ", "_")}_$i",
                                            url = cleanUrl,
                                            quality = "$qualityLabel ($formatLabel)",
                                            format = formatLabel,
                                            isVideo = true,
                                            isAudio = true,
                                            headers = streamHeaders
                                        )
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Malformed JSON
                    }
                }
            }
        }

        // Strategy 3: Direct video or source tag fallback
        if (streams.isEmpty()) {
            val sourceRegex = Pattern.compile("<source[^>]+src=[\"'](https?://[^\"']+\\.mp4[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
            val srcMatcher = sourceRegex.matcher(html)
            var srcIndex = 0
            while (srcMatcher.find()) {
                val directUrl = srcMatcher.group(1)?.replace("&amp;", "&") ?: continue
                if (!directUrl.contains(".m3u8", ignoreCase = true) &&
                    !directUrl.contains(".mpd", ignoreCase = true) &&
                    !directUrl.contains("/hls/", ignoreCase = true)
                ) {
                    streams.add(
                        VIPMediaStream(
                            id = "ph_${viewKey}_direct_$srcIndex",
                            url = directUrl,
                            quality = "Direct (MP4)",
                            format = "MP4",
                            isVideo = true,
                            isAudio = true,
                            headers = streamHeaders
                        )
                    )
                    srcIndex++
                }
            }
        }

        // Deduplicate and sort descending by resolution
        val distinctStreams = mutableListOf<VIPMediaStream>()
        val seenQualities = mutableSetOf<String>()

        val sorted = streams.sortedByDescending { extractHeightNumber(it.quality) }
        for (stream in sorted) {
            if (seenQualities.add(stream.quality)) {
                distinctStreams.add(stream)
            }
        }

        return Pair(distinctStreams, hasAdaptiveOnly)
    }

    private fun extractHeightNumber(qualityLabel: String): Int {
        val matcher = Pattern.compile("(\\d{3,4})p").matcher(qualityLabel)
        return if (matcher.find()) {
            matcher.group(1)?.toIntOrNull() ?: 0
        } else 0
    }
}
