package com.example

import com.example.core.settings.ACCENT_COLOR_OPTIONS
import com.example.core.settings.SEARCH_ENGINE_OPTIONS
import com.example.core.settings.ThemeMode
import com.example.core.settings.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun testDefaultSettingsInitialization() {
        val settings = UserSettings()
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals("cyan", settings.accentColorId)
        assertEquals("google", settings.searchEngineId)
        assertTrue(settings.doNotTrack)
        assertTrue(settings.blockThirdPartyCookies)
        assertTrue(settings.httpsOnly)
        assertFalse(settings.desktopMode)
        assertFalse(settings.wifiOnlyDownloads)
        assertTrue(settings.fastChunking)
        assertEquals("320 kbps", settings.audioBitrate)
    }

    @Test
    fun testAccentColorsAvailable() {
        assertTrue(ACCENT_COLOR_OPTIONS.isNotEmpty())
        val cyan = ACCENT_COLOR_OPTIONS.firstOrNull { it.id == "cyan" }
        assertNotNull(cyan)
        assertEquals("Cyber Cyan", cyan?.name)

        val purple = ACCENT_COLOR_OPTIONS.firstOrNull { it.id == "purple" }
        assertNotNull(purple)

        val settings = UserSettings(accentColorId = "purple")
        assertEquals(purple?.color, settings.activeAccentColor)
    }

    @Test
    fun testSearchEnginesAvailable() {
        assertTrue(SEARCH_ENGINE_OPTIONS.size >= 4)
        val google = SEARCH_ENGINE_OPTIONS.firstOrNull { it.id == "google" }
        assertNotNull(google)
        assertTrue(google!!.searchUrlTemplate.contains("google.com/search?q="))

        val ddg = SEARCH_ENGINE_OPTIONS.firstOrNull { it.id == "duckduckgo" }
        assertNotNull(ddg)
        assertTrue(ddg!!.searchUrlTemplate.contains("duckduckgo.com/?q="))
    }

    @Test
    fun testThemeModeOptions() {
        assertEquals(3, ThemeMode.values().size)
        assertTrue(ThemeMode.values().contains(ThemeMode.DARK))
        assertTrue(ThemeMode.values().contains(ThemeMode.LIGHT))
        assertTrue(ThemeMode.values().contains(ThemeMode.SYSTEM))
    }

    @Test
    fun testDownloadPreferencesCustomization() {
        val custom = UserSettings(
            wifiOnlyDownloads = true,
            audioBitrate = "256 kbps",
            fastChunking = false
        )
        assertTrue(custom.wifiOnlyDownloads)
        assertEquals("256 kbps", custom.audioBitrate)
        assertFalse(custom.fastChunking)
    }
}
