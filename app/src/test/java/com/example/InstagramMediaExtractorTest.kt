package com.example

import com.example.core.media.MediaAnalyzer
import com.example.core.media.extraction.ExtractionResult
import com.example.core.media.extraction.InstagramMediaExtractor
import com.example.core.media.extraction.MediaExtractionManager
import com.example.core.media.extraction.MediaStream
import com.example.core.media.extraction.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class InstagramMediaExtractorTest {

    private lateinit var extractor: InstagramMediaExtractor
    private lateinit var manager: MediaExtractionManager
    private lateinit var analyzer: MediaAnalyzer

    @Before
    fun setUp() {
        extractor = InstagramMediaExtractor()
        manager = MediaExtractionManager()
        analyzer = MediaAnalyzer(manager)
    }

    @Test
    fun testCanHandleVariousInstagramUrls() {
        assertTrue(extractor.canHandle("https://www.instagram.com/reel/C-0xyz123/"))
        assertTrue(extractor.canHandle("https://instagram.com/reel/C-0xyz123"))
        assertTrue(extractor.canHandle("https://www.instagram.com/reels/C-0xyz123/"))
        assertTrue(extractor.canHandle("https://www.instagram.com/p/C-0xyz123/?igsh=MWx123"))
        assertTrue(extractor.canHandle("https://www.instagram.com/tv/C-0xyz123/?utm_source=ig_web"))
        assertFalse(extractor.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(extractor.canHandle("https://example.com/video.mp4"))
    }

    @Test
    fun testExtractShortcodeFromComplexUrls() {
        assertEquals("C-0xyz123", extractor.extractShortcode("https://www.instagram.com/reel/C-0xyz123/"))
        assertEquals("C-0xyz123", extractor.extractShortcode("https://www.instagram.com/reels/C-0xyz123/?igsh=MWx#tag"))
        assertEquals("CIxm6P2A823", extractor.extractShortcode("https://www.instagram.com/p/CIxm6P2A823/"))
        assertEquals("B123_abc", extractor.extractShortcode("https://instagram.com/tv/B123_abc?utm_medium=copy_link"))
    }

    @Test
    fun testShortcodeToMediaIdConversion() {
        // Known shortcode: "CIxm6P2A823"
        val mediaId = extractor.shortcodeToMediaId("CIxm6P2A823")
        assertNotNull(mediaId)
        assertTrue(mediaId!!.isNotEmpty())
        assertTrue(BigInteger(mediaId) > BigInteger.ZERO)
    }

    @Test
    fun testPlatformDetectionInManager() {
        assertEquals(Platform.INSTAGRAM, manager.detectPlatform("https://www.instagram.com/reel/C-0xyz/"))
        assertEquals(Platform.YOUTUBE, manager.detectPlatform("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Platform.DIRECT, manager.detectPlatform("https://example.com/clip.mp4"))
    }

    @Test
    fun testStreamMatrixBuildingIncludesAudioOptions() {
        val testStreams = listOf(
            MediaStream(
                id = "ig_v1",
                url = "https://cdn.instagram.com/v1.mp4",
                quality = "HD 1080p",
                format = "MP4",
                isVideo = true,
                isAudio = true
            ),
            MediaStream(
                id = "ig_a1",
                url = "https://cdn.instagram.com/v1.mp4",
                quality = "Original Audio (MP3 320kbps)",
                format = "MP3",
                isVideo = false,
                isAudio = true
            )
        )

        val matrix = analyzer.buildStreamMatrix(testStreams)
        assertEquals(1, matrix.videoStreams.size)
        assertEquals(2, matrix.audioStreams.size) // Contains combined + audio only
        assertEquals(1, matrix.adaptiveAudioStreams.size) // Audio only stream for the Audio tab
        assertEquals("MP3", matrix.adaptiveAudioStreams[0].format)
    }

    @Test
    fun testFileExtensionExtraction() {
        assertEquals("mp4", analyzer.extractFileExtension("https://cdn.instagram.com/video.mp4?token=123"))
        assertEquals("mp3", analyzer.extractFileExtension("https://cdn.example.com/song.mp3"))
        assertEquals("webm", analyzer.extractFileExtension("https://site.org/clip.webm#t=10"))
    }
}
