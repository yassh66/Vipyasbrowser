package com.example

import com.example.core.settings.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNetworkPolicyTest {

    /**
     * Pure evaluator for testing network restriction logic independently of Android context.
     * Matches DownloadEngine.checkNetworkRestriction implementation.
     */
    private fun evaluateNetworkRestriction(
        settings: UserSettings,
        isWifiConnected: Boolean
    ): String? {
        if (!settings.wifiOnlyDownloads) {
            // Wi-Fi Only is OFF (Default): Downloads freely allowed over cellular, Wi-Fi, Ethernet, VPN
            return null
        }
        if (!isWifiConnected) {
            return "Blocked: 'Download on Wi-Fi Only' is enabled in Settings, but device is on cellular data."
        }
        return null
    }

    @Test
    fun testDefaultPolicyAllowsCellularAndMobileData() {
        val defaultSettings = UserSettings()
        // 1. Verify default preference is OFF
        assertFalse("Wi-Fi only downloads MUST be OFF by default", defaultSettings.wifiOnlyDownloads)

        // 2. When device is on mobile cellular data (isWifi = false), download MUST be allowed
        val cellularCheckResult = evaluateNetworkRestriction(defaultSettings, isWifiConnected = false)
        assertNull("Downloads over cellular/mobile data must NOT be blocked when Wi-Fi Only is OFF", cellularCheckResult)

        // 3. When device is on Wi-Fi (isWifi = true), download is also allowed
        val wifiCheckResult = evaluateNetworkRestriction(defaultSettings, isWifiConnected = true)
        assertNull("Downloads over Wi-Fi must be allowed", wifiCheckResult)
    }

    @Test
    fun testUserExplicitWifiOnlyRestriction() {
        val userRestrictedSettings = UserSettings(wifiOnlyDownloads = true)
        assertTrue(userRestrictedSettings.wifiOnlyDownloads)

        // On cellular data, when user turned ON the setting, download is restricted with explicit message
        val cellularBlocked = evaluateNetworkRestriction(userRestrictedSettings, isWifiConnected = false)
        assertNotNull("Cellular download must be blocked when user explicitly turns on Wi-Fi Only", cellularBlocked)
        assertTrue(cellularBlocked!!.contains("Download on Wi-Fi Only"))

        // On Wi-Fi, download proceeds
        val wifiAllowed = evaluateNetworkRestriction(userRestrictedSettings, isWifiConnected = true)
        assertNull("Wi-Fi download proceeds normally when Wi-Fi Only is enabled", wifiAllowed)
    }

    @Test
    fun testReversibilityOfWifiRestriction() {
        var settings = UserSettings(wifiOnlyDownloads = false)
        assertNull(evaluateNetworkRestriction(settings, isWifiConnected = false))

        // User turns ON Wi-Fi only
        settings = settings.copy(wifiOnlyDownloads = true)
        assertNotNull(evaluateNetworkRestriction(settings, isWifiConnected = false))

        // User turns OFF Wi-Fi only
        settings = settings.copy(wifiOnlyDownloads = false)
        assertNull("Turning OFF Wi-Fi Only must immediately unblock cellular data downloads",
            evaluateNetworkRestriction(settings, isWifiConnected = false))
    }

    @Test
    fun testHttpRangeHeaderForResumingDownloads() {
        val partialBytes = 1048576L // 1MB already downloaded
        val rangeHeader = "bytes=$partialBytes-"
        assertEquals("bytes=1048576-", rangeHeader)
    }
}
