package com.example.core.media.extraction

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeMediaExtractor(
    private val client: OkHttpClient = defaultHttpClient()
) : MediaExtractor {

    companion object {
        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        private val YOUTUBE_URL_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?.*v=|v/|embed/|shorts/|live/)|youtu\\.be/)([a-zA-Z0-9_-]{11})",
            Pattern.CASE_INSENSITIVE
        )
    }

    override fun canHandle(url: String): Boolean {
        if (url.isBlank()) return false
        val clean = url.trim().lowercase()
        return (clean.contains("youtube.com") || clean.contains("youtu.be")) &&
                extractVideoId(url) != null
    }

    private fun getYouTubeCookies(): String? {
        return try {
            CookieManager.getInstance().getCookie("https://www.youtube.com")
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)
            ?: return@withContext ExtractionResult.Error("Could not parse YouTube video ID from URL: $url")

        val cookies = getYouTubeCookies()

        // 1. Primary engine: NewPipeExtractor mature extraction architecture
        try {
            val newPipeResult = com.example.core.media.extraction.youtube.NewPipeYouTubeExtractor().extract(url)
            if (newPipeResult is ExtractionResult.Success && newPipeResult.streams.isNotEmpty()) {
                return@withContext newPipeResult
            }
        } catch (_: Exception) {}

        // Query extraction strategies
        // 1. Android VR Client profile: Oculus Quest client returns unthrottled direct progressive & adaptive MP4
        val vrResult = tryExtractFromYouTubei(videoId, url, clientType = "ANDROID_VR", cookies = cookies)

        // 2. Android Testsuite profile: bypasses throttling and returns unthrottled direct streams
        val tsResult = tryExtractFromYouTubei(videoId, url, clientType = "ANDROID_TESTSUITE", cookies = cookies)

        // 3. iOS Client profile: often serves unthrottled direct progressive and adaptive streams
        val iosResult = tryExtractFromYouTubei(videoId, url, clientType = "IOS", cookies = cookies)

        // 4. TVHTML5 Embedded Player profile
        val tvResult = tryExtractFromYouTubei(videoId, url, clientType = "TVHTML5", cookies = cookies)

        // 5. Web embedded player profile
        val webEmbedResult = tryExtractFromYouTubei(videoId, url, clientType = "WEB_EMBEDDED", cookies = cookies)

        // 6. Standard Android Client profile
        val androidResult = tryExtractFromYouTubei(videoId, url, clientType = "ANDROID", cookies = cookies)

        // 7. Web player page scraping fallback
        val webResult = tryExtractFromWebPage(videoId, url, cookies = cookies)

        val successfulResults = listOfNotNull(
            vrResult as? ExtractionResult.Success,
            tsResult as? ExtractionResult.Success,
            iosResult as? ExtractionResult.Success,
            tvResult as? ExtractionResult.Success,
            webEmbedResult as? ExtractionResult.Success,
            androidResult as? ExtractionResult.Success,
            webResult as? ExtractionResult.Success
        )

        // Select the strategy that provides the highest resolution real formats (1080p > 720p > 480p > 360p)
        val best = selectBestResult(successfulResults)
        if (best != null && best.streams.isNotEmpty()) {
            return@withContext best
        }

        // Honest error reporting
        val errorMessage = when {
            vrResult is ExtractionResult.NoFormatsFound && vrResult.reason.contains("encrypted signature", ignoreCase = true) ->
                vrResult.reason
            iosResult is ExtractionResult.NoFormatsFound && iosResult.reason.contains("encrypted signature", ignoreCase = true) ->
                iosResult.reason
            webEmbedResult is ExtractionResult.NoFormatsFound && webEmbedResult.reason.contains("encrypted signature", ignoreCase = true) ->
                webEmbedResult.reason
            androidResult is ExtractionResult.NoFormatsFound && androidResult.reason.contains("encrypted signature", ignoreCase = true) ->
                androidResult.reason
            vrResult is ExtractionResult.Error -> vrResult.message
            iosResult is ExtractionResult.Error -> iosResult.message
            webEmbedResult is ExtractionResult.Error -> webEmbedResult.message
            androidResult is ExtractionResult.Error -> androidResult.message
            webResult is ExtractionResult.Error -> webResult.message
            else -> "No direct unencrypted playable streams found. This video may use encrypted signature protection or DRM."
        }

        ExtractionResult.NoFormatsFound(errorMessage)
    }

    private fun selectBestResult(results: List<ExtractionResult.Success>): ExtractionResult.Success? {
        if (results.isEmpty()) return null
        return results.maxByOrNull { result ->
            var score = 0
            for (stream in result.streams) {
                val res = extractResolutionNumber(stream.quality)
                when {
                    res >= 1080 -> score += 1000
                    res >= 720 -> score += 500
                    res >= 480 -> score += 250
                    res >= 360 -> score += 100
                }
                if (stream.isAudio) score += 20
            }
            score += result.streams.size
            score
        }
    }

    fun extractResolutionNumber(quality: String): Int {
        val matcher = Pattern.compile("(\\d{3,4})p").matcher(quality)
        if (matcher.find()) {
            return matcher.group(1)?.toIntOrNull() ?: 0
        }
        return 0
    }

    fun extractVideoId(url: String): String? {
        val matcher = YOUTUBE_URL_PATTERN.matcher(url)
        if (matcher.find()) {
            return matcher.group(1)
        }
        // Fallback parameter search
        val vParam = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})").matcher(url)
        if (vParam.find()) {
            return vParam.group(1)
        }
        return null
    }

    fun tryExtractFromYouTubei(
        videoId: String,
        sourceUrl: String,
        clientType: String = "ANDROID",
        cookies: String? = null
    ): ExtractionResult {
        return try {
            val endpoint = "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"

            val clientContext: JSONObject
            val userAgent: String
            val clientNameHeader: String
            val clientVersionHeader: String

            when (clientType) {
                "ANDROID_TESTSUITE" -> {
                    clientContext = JSONObject().apply {
                        put("clientName", "ANDROID_TESTSUITE")
                        put("clientVersion", "1.9")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    }
                    userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14; US) gzip"
                    clientNameHeader = "61"
                    clientVersionHeader = "1.9"
                }
                "ANDROID_VR" -> {
                    clientContext = JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.61.48")
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("androidSdkVersion", 32)
                        put("hl", "en")
                        put("gl", "US")
                    }
                    userAgent = "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/34.0.0.30.37 Mobile VR Safari/537.36"
                    clientNameHeader = "28"
                    clientVersionHeader = "1.61.48"
                }
                "IOS" -> {
                    clientContext = JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "19.29.1")
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone14,5")
                        put("hl", "en")
                        put("gl", "US")
                    }
                    userAgent = "com.google.ios.youtube/19.29.1 (iPhone14,5; U; CPU iOS 17_5_1 like Mac OS X)"
                    clientNameHeader = "5"
                    clientVersionHeader = "19.29.1"
                }
                "TVHTML5" -> {
                    clientContext = JSONObject().apply {
                        put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                        put("clientVersion", "2.0")
                        put("hl", "en")
                        put("gl", "US")
                    }
                    userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/11.50 Safari/605.1.15"
                    clientNameHeader = "85"
                    clientVersionHeader = "2.0"
                }
                "WEB_EMBEDDED" -> {
                    clientContext = JSONObject().apply {
                        put("clientName", "WEB_EMBEDDED_PLAYER")
                        put("clientVersion", "1.20240826.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    }
                    userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                    clientNameHeader = "56"
                    clientVersionHeader = "1.20240826.01.00"
                }
                else -> {
                    clientContext = JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.29.37")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    }
                    userAgent = "com.google.android.youtube/19.29.37 (Linux; U; Android 14)"
                    clientNameHeader = "3"
                    clientVersionHeader = "19.29.37"
                }
            }

            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", JSONObject().apply {
                    put("client", clientContext)
                    if (clientType == "WEB_EMBEDDED" || clientType == "TVHTML5") {
                        put("thirdParty", JSONObject().apply {
                            put("embedUrl", "https://www.youtube.com")
                        })
                    }
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody(mediaType))
                .header("User-Agent", userAgent)
                .header("X-YouTube-Client-Name", clientNameHeader)
                .header("X-YouTube-Client-Version", clientVersionHeader)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")

            if (!cookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: return ExtractionResult.Error("Empty response from YouTube player service")

            val json = JSONObject(responseBody)

            val playabilityStatus = json.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status") ?: ""
            if (status.equals("ERROR", ignoreCase = true) || status.equals("LOGIN_REQUIRED", ignoreCase = true)) {
                val reason = playabilityStatus?.optString("reason") ?: "Video is unavailable or requires login."
                return ExtractionResult.Error(reason)
            }

            val videoDetails = json.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title") ?: "YouTube Video ($videoId)"
            val author = videoDetails?.optString("author") ?: "YouTube Creator"
            val lengthSeconds = videoDetails?.optLong("lengthSeconds", 0L) ?: 0L

            val thumbArray = videoDetails?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbUrl = if (thumbArray != null && thumbArray.length() > 0) {
                thumbArray.getJSONObject(thumbArray.length() - 1).optString("url")
            } else {
                "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
            }

            val streamingData = json.optJSONObject("streamingData")
                ?: return ExtractionResult.NoFormatsFound("No streaming data available for this video.")

            val streamHeaders = mutableMapOf<String, String>()
            streamHeaders["User-Agent"] = userAgent

            // For web embedded client, set Referer and Origin.
            // For native mobile clients (IOS, ANDROID, ANDROID_VR), do NOT set Referer/Origin
            // as googlevideo rejects native User-Agents accompanied by browser referers with 403 Forbidden!
            if (clientType == "WEB_EMBEDDED") {
                streamHeaders["Referer"] = "https://www.youtube.com/"
                streamHeaders["Origin"] = "https://www.youtube.com"
            }

            if (!cookies.isNullOrBlank()) {
                streamHeaders["Cookie"] = cookies
            }

            val streams = parseStreamingData(streamingData, videoId, streamHeaders)
            if (streams.isEmpty()) {
                val hasCipher = hasCipherStreams(streamingData)
                val reason = if (hasCipher) {
                    "This YouTube video requires encrypted signatureCipher descrambling which is unsupported without a JavaScript engine. Direct playable streams are not available."
                } else {
                    "No direct playable streams found in YouTube player response."
                }
                return ExtractionResult.NoFormatsFound(reason)
            }

            val metadata = MediaMetadata(
                id = videoId,
                title = title,
                author = author,
                thumbnailUrl = thumbUrl,
                sourceUrl = sourceUrl,
                platform = Platform.YOUTUBE,
                durationSeconds = lengthSeconds
            )

            ExtractionResult.Success(metadata, streams)
        } catch (e: Exception) {
            ExtractionResult.Error("YouTubei extraction failed: ${e.message}", e)
        }
    }

    private fun tryExtractFromWebPage(
        videoId: String,
        sourceUrl: String,
        cookies: String? = null
    ): ExtractionResult {
        return try {
            val pageUrl = "https://www.youtube.com/watch?v=$videoId"
            val webUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            val requestBuilder = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", webUA)
                .header("Accept-Language", "en-US,en;q=0.9")

            if (!cookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""

            // Look for ytInitialPlayerResponse = {...}
            val pattern = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});", Pattern.DOTALL)
            val matcher = pattern.matcher(html)
            if (!matcher.find()) {
                return ExtractionResult.Error("Could not locate player response on YouTube page")
            }

            val jsonStr = matcher.group(1) ?: return ExtractionResult.Error("Could not locate player response on YouTube page")
            val json = JSONObject(jsonStr)

            val videoDetails = json.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title") ?: "YouTube Video ($videoId)"
            val author = videoDetails?.optString("author") ?: "YouTube Creator"
            val lengthSeconds = videoDetails?.optLong("lengthSeconds", 0L) ?: 0L

            val thumbArray = videoDetails?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbUrl = if (thumbArray != null && thumbArray.length() > 0) {
                thumbArray.getJSONObject(thumbArray.length() - 1).optString("url")
            } else {
                "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
            }

            val streamingData = json.optJSONObject("streamingData")
                ?: return ExtractionResult.NoFormatsFound("No streaming data available on web page.")

            val streamHeaders = mutableMapOf(
                "User-Agent" to webUA,
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com"
            )
            if (!cookies.isNullOrBlank()) {
                streamHeaders["Cookie"] = cookies
            }

            val streams = parseStreamingData(streamingData, videoId, streamHeaders)
            if (streams.isEmpty()) {
                val hasCipher = hasCipherStreams(streamingData)
                val reason = if (hasCipher) {
                    "Web player streams are protected by YouTube signatureCipher."
                } else {
                    "No direct streams found on web page."
                }
                return ExtractionResult.NoFormatsFound(reason)
            }

            val metadata = MediaMetadata(
                id = videoId,
                title = title,
                author = author,
                thumbnailUrl = thumbUrl,
                sourceUrl = sourceUrl,
                platform = Platform.YOUTUBE,
                durationSeconds = lengthSeconds
            )

            ExtractionResult.Success(metadata, streams)
        } catch (e: Exception) {
            ExtractionResult.Error("Web page extraction failed: ${e.message}", e)
        }
    }

    fun parseStreamingData(
        streamingData: JSONObject,
        videoId: String,
        streamHeaders: Map<String, String> = emptyMap()
    ): List<MediaStream> {
        val streams = mutableListOf<MediaStream>()

        // 1. Extract direct audio streams from adaptiveFormats
        val audioStreams = mutableListOf<MediaStream>()
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
        if (adaptiveFormats != null) {
            for (i in 0 until adaptiveFormats.length()) {
                val formatObj = adaptiveFormats.getJSONObject(i)
                val directUrl = extractDirectStreamUrl(formatObj) ?: continue
                val mimeType = formatObj.optString("mimeType", "")
                val contentLength = formatObj.optLong("contentLength", 0L).takeIf { it > 0 }

                if (mimeType.contains("audio", ignoreCase = true)) {
                    val bitrate = formatObj.optInt("bitrate", 128000) / 1000
                    val isAac = mimeType.contains("mp4a", ignoreCase = true) || mimeType.contains("mp4", ignoreCase = true)
                    val formatName = if (isAac) "M4A" else "WebM"
                    val audioLabel = "Audio Track (${bitrate}kbps $formatName)"

                    audioStreams.add(
                        MediaStream(
                            id = "yt_${videoId}_audio_${i}",
                            url = directUrl,
                            quality = audioLabel,
                            format = formatName,
                            isVideo = false,
                            isAudio = true,
                            contentLength = contentLength,
                            headers = streamHeaders
                        )
                    )
                }
            }
        }

        // Best audio track for pairing with adaptive video: prefer M4A/AAC for MP4 container
        val bestAudioStream = audioStreams.firstOrNull { it.format.equals("M4A", ignoreCase = true) }
            ?: audioStreams.firstOrNull()

        // 2. Extract progressive video formats (contain both video and audio)
        val progressiveStreams = mutableListOf<MediaStream>()
        val formats = streamingData.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val formatObj = formats.getJSONObject(i)
                val directUrl = extractDirectStreamUrl(formatObj) ?: continue
                val qualityLabel = formatObj.optString("qualityLabel", "Standard Video")
                val mimeType = formatObj.optString("mimeType", "video/mp4")
                val isMp4 = mimeType.contains("mp4", ignoreCase = true)
                val formatName = if (isMp4) "MP4" else "WebM"
                val contentLength = formatObj.optLong("contentLength", 0L).takeIf { it > 0 }

                progressiveStreams.add(
                    MediaStream(
                        id = "yt_${videoId}_prog_${i}",
                        url = directUrl,
                        quality = "$qualityLabel ($formatName)",
                        format = formatName,
                        isVideo = true,
                        isAudio = true,
                        contentLength = contentLength,
                        headers = streamHeaders,
                        requiresAudioMerge = false
                    )
                )
            }
        }

        // 3. Extract adaptive video formats and pair with best audio stream
        val adaptiveVideoStreams = mutableListOf<MediaStream>()
        if (adaptiveFormats != null && bestAudioStream != null) {
            for (i in 0 until adaptiveFormats.length()) {
                val formatObj = adaptiveFormats.getJSONObject(i)
                val directUrl = extractDirectStreamUrl(formatObj) ?: continue
                val mimeType = formatObj.optString("mimeType", "")
                val contentLength = formatObj.optLong("contentLength", 0L).takeIf { it > 0 }

                if (mimeType.contains("video", ignoreCase = true)) {
                    val rawQuality = formatObj.optString("qualityLabel", "")
                    val isMp4 = mimeType.contains("mp4", ignoreCase = true)
                    val formatName = if (isMp4) "MP4" else "WebM"
                    val height = formatObj.optInt("height", 0)
                    val qualityLabel = if (rawQuality.isNotBlank()) rawQuality else if (height > 0) "${height}p" else ""

                    if (qualityLabel.isNotBlank()) {
                        adaptiveVideoStreams.add(
                            MediaStream(
                                id = "yt_${videoId}_adaptive_${i}",
                                url = directUrl,
                                quality = "$qualityLabel ($formatName)",
                                format = formatName,
                                isVideo = true,
                                isAudio = false,
                                contentLength = contentLength,
                                headers = streamHeaders,
                                requiresAudioMerge = true,
                                audioUrl = bestAudioStream.url,
                                audioHeaders = bestAudioStream.headers
                            )
                        )
                    }
                }
            }
        }

        // 4. Combine and sort video formats descending by real resolution
        val seenResolutions = mutableSetOf<Int>()
        val combinedVideoStreams = mutableListOf<MediaStream>()

        // Progressive streams are preferred if matching resolution exists (no muxing required)
        val candidateVideoStreams = mutableListOf<MediaStream>()
        candidateVideoStreams.addAll(progressiveStreams)
        candidateVideoStreams.addAll(adaptiveVideoStreams)

        // Sort descending by resolution (1080p > 720p > 480p > 360p...)
        candidateVideoStreams.sortByDescending { extractResolutionNumber(it.quality) }

        for (v in candidateVideoStreams) {
            val res = extractResolutionNumber(v.quality)
            if (res > 0 && seenResolutions.add(res)) {
                combinedVideoStreams.add(v)
            } else if (res == 0 && combinedVideoStreams.none { it.quality == v.quality }) {
                combinedVideoStreams.add(v)
            }
        }

        streams.addAll(combinedVideoStreams)

        // 5. Add audio streams (M4A and MP3 conversion option)
        for (a in audioStreams) {
            streams.add(a)
            // Add MP3 conversion option
            if (a.format.equals("M4A", ignoreCase = true) || a.format.equals("AAC", ignoreCase = true)) {
                streams.add(
                    a.copy(
                        id = "${a.id}_mp3",
                        quality = a.quality.replace("M4A", "MP3").replace("AAC", "MP3"),
                        format = "MP3"
                    )
                )
            }
        }

        // If no standalone audio streams found but progressive streams exist,
        // create audio extraction options from the progressive stream so Audio tab is NEVER empty!
        if (audioStreams.isEmpty() && progressiveStreams.isNotEmpty()) {
            val prog = progressiveStreams.first()
            streams.add(
                MediaStream(
                    id = "yt_${videoId}_audio_prog_m4a",
                    url = prog.url,
                    quality = "Original Audio (M4A)",
                    format = "M4A",
                    isVideo = false,
                    isAudio = true,
                    contentLength = prog.contentLength,
                    headers = prog.headers
                )
            )
            streams.add(
                MediaStream(
                    id = "yt_${videoId}_audio_prog_mp3",
                    url = prog.url,
                    quality = "Extracted Audio (MP3 320kbps)",
                    format = "MP3",
                    isVideo = false,
                    isAudio = true,
                    contentLength = prog.contentLength,
                    headers = prog.headers
                )
            )
        }

        return streams
    }

    /**
     * Extracts a directly playable and downloadable URL.
     * Rejects encrypted signatureCipher formats where signature descrambling is required,
     * preventing 403 Forbidden download failures.
     */
    fun extractDirectStreamUrl(formatObj: JSONObject): String? {
        val directUrl = formatObj.optString("url")
        if (directUrl.isNotBlank() && directUrl.startsWith("http")) {
            return directUrl
        }

        // Check if format is ciphered
        val cipher = formatObj.optString("signatureCipher").ifBlank { formatObj.optString("cipher") }
        if (cipher.isNotBlank()) {
            val params = parseQueryString(cipher)
            val rawUrl = params["url"]
            val sig = params["s"] ?: params["sig"] ?: params["signature"]

            // If an encrypted 's' parameter is present, it REQUIRES a JS player descrambler.
            // Returning rawUrl without descrambling causes an immediate HTTP 403 from googlevideo servers.
            if (!sig.isNullOrBlank() && params.containsKey("s")) {
                // Encrypted signature cipher detected - unsupported without JS execution engine
                return null
            }

            if (!rawUrl.isNullOrBlank() && rawUrl.startsWith("http")) {
                val sp = params["sp"] ?: "sig"
                return if (!sig.isNullOrBlank()) {
                    if (rawUrl.contains("?")) "$rawUrl&$sp=$sig" else "$rawUrl?$sp=$sig"
                } else {
                    rawUrl
                }
            }
        }

        return null
    }

    /**
     * Checks whether the streamingData contains ciphered streams that were rejected.
     */
    fun hasCipherStreams(streamingData: JSONObject): Boolean {
        val formats = streamingData.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val obj = formats.getJSONObject(i)
                if (obj.has("signatureCipher") || obj.has("cipher")) return true
            }
        }
        val adaptive = streamingData.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val obj = adaptive.getJSONObject(i)
                if (obj.has("signatureCipher") || obj.has("cipher")) return true
            }
        }
        return false
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }
}
