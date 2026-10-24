package com.example

import com.example.core.media.extraction.ExtractionResult
import com.example.core.media.extraction.YouTubeMediaExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeMediaExtractorTest {

    private lateinit var extractor: YouTubeMediaExtractor

    @Before
    fun setUp() {
        extractor = YouTubeMediaExtractor()
    }

    @Test
    fun testCanHandleVariousYouTubeUrls() {
        assertTrue(extractor.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(extractor.canHandle("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
        assertTrue(extractor.canHandle("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(extractor.canHandle("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(extractor.canHandle("https://youtube.com/embed/dQw4w9WgXcQ"))
        assertTrue(extractor.canHandle("https://youtube.com/live/dQw4w9WgXcQ?feature=share"))

        assertFalse(extractor.canHandle("https://www.instagram.com/reel/C-0xyz123/"))
        assertFalse(extractor.canHandle("https://example.com/video.mp4"))
        assertFalse(extractor.canHandle(""))
    }

    @Test
    fun testExtractVideoId() {
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://youtube.com/watch?feature=related&v=dQw4w9WgXcQ"))
    }

    @Test
    fun testParseStreamingDataWithDirectProgressiveUrls() {
        val streamingData = JSONObject().apply {
            put("formats", JSONArray().apply {
                put(JSONObject().apply {
                    put("itag", 22)
                    put("url", "https://rr1---sn-abc.googlevideo.com/videoplayback?id=123")
                    put("mimeType", "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"")
                    put("qualityLabel", "720p")
                    put("contentLength", "15400000")
                })
                put(JSONObject().apply {
                    put("itag", 18)
                    put("url", "https://rr1---sn-abc.googlevideo.com/videoplayback?id=456")
                    put("mimeType", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"")
                    put("qualityLabel", "360p")
                    put("contentLength", "8200000")
                })
            })
            put("adaptiveFormats", JSONArray().apply {
                put(JSONObject().apply {
                    put("itag", 140)
                    put("url", "https://rr1---sn-abc.googlevideo.com/videoplayback?id=789")
                    put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                    put("bitrate", 128000)
                    put("contentLength", "3500000")
                })
            })
        }

        val streams = extractor.parseStreamingData(streamingData, "test1234567")
        // 2 progressive videos (720p, 360p) + 2 audio options (M4A and MP3 conversion) = 4
        assertEquals(4, streams.size)

        val prog720 = streams.firstOrNull { it.quality.contains("720p") }
        assertNotNull(prog720)
        assertTrue(prog720!!.isVideo && prog720.isAudio)
        assertEquals("https://rr1---sn-abc.googlevideo.com/videoplayback?id=123", prog720.url)
        assertEquals(15400000L, prog720.contentLength)

        val m4aTrack = streams.firstOrNull { it.isAudioOnly && it.format == "M4A" }
        assertNotNull(m4aTrack)
        assertTrue(m4aTrack!!.quality.contains("128kbps"))

        val mp3Track = streams.firstOrNull { it.isAudioOnly && it.format == "MP3" }
        assertNotNull(mp3Track)
        assertTrue(mp3Track!!.quality.contains("128kbps"))
    }

    @Test
    fun testRejectionOfEncryptedSignatureCipherStreams() {
        // When YouTube provides only signatureCipher with encrypted 's' parameter,
        // it requires an active JS descrambling algorithm. Our extractor must strictly reject
        // the ciphered URL rather than constructing a broken link that fails with HTTP 403.
        val cipherFormat = JSONObject().apply {
            put("itag", 22)
            put("signatureCipher", "s=A1B2C3D4E5F6G7H8&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fid%3Dtest")
            put("qualityLabel", "720p")
            put("mimeType", "video/mp4")
        }

        val parsedUrl = extractor.extractDirectStreamUrl(cipherFormat)
        assertNull("Ciphered streams with encrypted 's' parameter MUST be rejected to avoid 403 Forbidden errors", parsedUrl)

        val streamingData = JSONObject().apply {
            put("formats", JSONArray().apply { put(cipherFormat) })
        }
        val streams = extractor.parseStreamingData(streamingData, "test1234567")
        assertTrue("No streams should be created when only encrypted cipher is available", streams.isEmpty())
        assertTrue("hasCipherStreams should report true for diagnostic reporting", extractor.hasCipherStreams(streamingData))
    }

    @Test
    fun testDirectUrlExtractionBypassesCipher() {
        val directFormat = JSONObject().apply {
            put("url", "https://rr2.googlevideo.com/videoplayback?id=direct_test")
            put("qualityLabel", "1080p")
            put("mimeType", "video/mp4")
        }

        val url = extractor.extractDirectStreamUrl(directFormat)
        assertEquals("https://rr2.googlevideo.com/videoplayback?id=direct_test", url)
    }

    @Test
    fun testAdaptiveVideoFormatsPairedWithAudio() {
        val streamingData = JSONObject().apply {
            put("adaptiveFormats", JSONArray().apply {
                put(JSONObject().apply {
                    put("itag", 137)
                    put("url", "https://rr1.googlevideo.com/videoplayback?id=1080p_video")
                    put("mimeType", "video/mp4; codecs=\"avc1.640028\"")
                    put("qualityLabel", "1080p")
                    put("contentLength", "50000000")
                })
                put(JSONObject().apply {
                    put("itag", 140)
                    put("url", "https://rr1.googlevideo.com/videoplayback?id=audio_track")
                    put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                    put("bitrate", 128000)
                    put("contentLength", "4000000")
                })
            })
        }

        val streams = extractor.parseStreamingData(streamingData, "adaptiveTest123")
        val video1080 = streams.firstOrNull { it.quality.contains("1080p") }
        assertNotNull("1080p adaptive video stream should be parsed", video1080)
        assertTrue(video1080!!.requiresAudioMerge)
        assertEquals("https://rr1.googlevideo.com/videoplayback?id=audio_track", video1080.audioUrl)
        assertTrue(video1080.isVideo)
        assertFalse("Adaptive video without embedded audio should have isAudio = false", video1080.isAudio)
    }

    @Test
    fun testAudioExtractionFallbackWhenOnlyProgressiveAvailable() {
        val streamingData = JSONObject().apply {
            put("formats", JSONArray().apply {
                put(JSONObject().apply {
                    put("itag", 18)
                    put("url", "https://rr1.googlevideo.com/videoplayback?id=only_prog_360")
                    put("mimeType", "video/mp4")
                    put("qualityLabel", "360p")
                    put("contentLength", "5000000")
                })
            })
        }

        val streams = extractor.parseStreamingData(streamingData, "fallbackTest")
        val video360 = streams.firstOrNull { it.isVideo }
        assertNotNull(video360)
        assertEquals("360p (MP4)", video360!!.quality)

        val m4aAudio = streams.firstOrNull { it.isAudioOnly && it.format == "M4A" }
        assertNotNull("Synthesized M4A audio should be provided when only progressive format is available", m4aAudio)

        val mp3Audio = streams.firstOrNull { it.isAudioOnly && it.format == "MP3" }
        assertNotNull("Synthesized MP3 audio should be provided when only progressive format is available", mp3Audio)
    }

    @Test
    fun testAdaptiveFormatsResolutionClassificationAndSorting() {
        val streamingData = JSONObject().apply {
            put("adaptiveFormats", JSONArray().apply {
                put(JSONObject().apply {
                    put("itag", 137)
                    put("url", "https://rr1.googlevideo.com/videoplayback?id=1080p")
                    put("mimeType", "video/mp4; codecs=\"avc1.640028\"")
                    put("qualityLabel", "1080p")
                    put("contentLength", "55000000")
                })
                put(JSONObject().apply {
                    put("itag", 136)
                    put("url", "https://rr1.googlevideo.com/videoplayback?id=720p")
                    put("mimeType", "video/mp4; codecs=\"avc1.4d401f\"")
                    put("qualityLabel", "720p")
                    put("contentLength", "32000000")
                })
                put(JSONObject().apply {
                    put("itag", 135)
                    put("url", "https://rr1.googlevideo.com/videoplayback?id=480p")
                    put("mimeType", "video/mp4; codecs=\"avc1.4d401e\"")
                    put("qualityLabel", "480p")
                    put("contentLength", "18000000")
                })
                put(JSONObject().apply {
                    put("itag", 140)
                    put("url", "https://rr1.googlevideo.com/videoplayback?id=audio_128k")
                    put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                    put("bitrate", 128000)
                    put("contentLength", "4200000")
                })
            })
        }

        val streams = extractor.parseStreamingData(streamingData, "multiResTest")
        val videoQualities = streams.filter { it.isVideo }.map { it.quality }

        assertTrue("Should include 1080p", videoQualities.any { it.contains("1080p") })
        assertTrue("Should include 720p", videoQualities.any { it.contains("720p") })
        assertTrue("Should include 480p", videoQualities.any { it.contains("480p") })

        // Check that adaptive streams are correctly paired with the audio track
        val v1080 = streams.first { it.quality.contains("1080p") }
        assertTrue(v1080.requiresAudioMerge)
        assertEquals("https://rr1.googlevideo.com/videoplayback?id=audio_128k", v1080.audioUrl)

        // Check audio options
        val audioFormats = streams.filter { it.isAudioOnly }.map { it.format }
        assertTrue("Should provide M4A audio", audioFormats.contains("M4A"))
        assertTrue("Should provide MP3 conversion option", audioFormats.contains("MP3"))
    }

    @Test
    fun testValidMp3DetectionRequiresSyncOrId3() {
        val tempId3 = java.io.File.createTempFile("test_id3", ".mp3").apply {
            writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) + ByteArray(200))
            deleteOnExit()
        }
        assertTrue(com.example.core.media.download.AudioTranscoder.isValidMp3File(tempId3))

        val tempFake = java.io.File.createTempFile("test_fake", ".mp3").apply {
            writeBytes("ftypM4A .... fake aac content".toByteArray() + ByteArray(200))
            deleteOnExit()
        }
        assertFalse("M4A content renamed to .mp3 must be rejected as invalid MP3", com.example.core.media.download.AudioTranscoder.isValidMp3File(tempFake))

        val tempEmpty = java.io.File.createTempFile("test_empty", ".mp3").apply {
            writeBytes(ByteArray(0))
            deleteOnExit()
        }
        assertFalse("Zero-byte file must be rejected", com.example.core.media.download.AudioTranscoder.isValidMp3File(tempEmpty))
    }

    @Test
    fun testNewPipeExtractorCanHandleUrlsAndExtractVideoId() {
        val npExtractor = com.example.core.media.extraction.youtube.NewPipeYouTubeExtractor()
        assertTrue(npExtractor.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(npExtractor.canHandle("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(npExtractor.canHandle("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(npExtractor.canHandle("https://www.youtube.com/embed/dQw4w9WgXcQ"))

        assertEquals("dQw4w9WgXcQ", npExtractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", npExtractor.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(npExtractor.canHandle("https://www.instagram.com/reel/C-0xyz123/"))
    }
}
