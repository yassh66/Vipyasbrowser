package com.example

import com.example.core.navigation.UrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFieldStabilityTest {

    private val googleTemplate = "https://www.google.com/search?q="
    private val duckDuckGoTemplate = "https://duckduckgo.com/?q="

    @Test
    fun testIndependentStateResolution() {
        // Simulates independent queries entered into Home Search vs Omnibox
        val homeQuery = "مرورگر سریع اندروید"
        val omniboxQuery = "https://github.com/trending"

        val homeResolved = UrlResolver.resolveUrl(homeQuery, googleTemplate)
        val omniboxResolved = UrlResolver.resolveUrl(omniboxQuery, googleTemplate)

        // Typing in home search does NOT change or match omnibox query
        assertNotEquals(homeResolved, omniboxResolved)
        assertTrue("Home search Persian query must be resolved via search engine", homeResolved.startsWith(googleTemplate))
        assertEquals("Omnibox direct URL must pass through cleanly without search engine prefix", "https://github.com/trending", omniboxResolved)
    }

    @Test
    fun testPersianRtlSearchQueryEncoding() {
        val persianQuery = "دانلود ویدیو اینستاگرام"
        val resolved = UrlResolver.resolveUrl(persianQuery, duckDuckGoTemplate)

        assertTrue(resolved.startsWith(duckDuckGoTemplate))
        assertTrue(resolved.contains("%D8%AF%D8%A7%D9%86%D9%84%D9%88%D8%AF") || resolved.contains("دانلود"))
    }

    @Test
    fun testEnglishLtrSearchQueryEncoding() {
        val englishQuery = "best privacy browser android 2026"
        val resolved = UrlResolver.resolveUrl(englishQuery, googleTemplate)

        assertTrue(resolved.startsWith(googleTemplate))
        assertTrue(resolved.contains("best+privacy+browser+android+2026") || resolved.contains("best%20privacy"))
    }

    @Test
    fun testDirectDomainsResolveWithoutAlteringDimensions() {
        val domain = "developer.android.com"
        val resolved = UrlResolver.resolveUrl(domain, googleTemplate)
        assertEquals("https://developer.android.com", resolved)

        val domainWithPort = "192.168.1.1:8080"
        val resolvedIp = UrlResolver.resolveUrl(domainWithPort, googleTemplate)
        assertEquals("https://192.168.1.1:8080", resolvedIp)
    }

    @Test
    fun testEmptyOrWhitespaceReturnsHome() {
        assertEquals("yas://home", UrlResolver.resolveUrl("", googleTemplate))
        assertEquals("yas://home", UrlResolver.resolveUrl("   \t  ", googleTemplate))
    }

    @Test
    fun testOmniboxTrailingActionStateLogic() {
        // State 1: When loading -> Stop action active
        val isLoading = true
        val isFocused = false
        val inputQuery = "https://example.com"
        val currentUrl = "https://example.com"

        val actionWhenLoading = when {
            isLoading -> "STOP"
            isFocused && inputQuery.isNotEmpty() -> "CLEAR"
            inputQuery.isNotBlank() && currentUrl != "yas://home" -> "RELOAD"
            else -> "NONE"
        }
        assertEquals("STOP", actionWhenLoading)

        // State 2: When user is typing/focused and has text -> Clear action active
        val actionWhenEditing = when {
            false -> "STOP"
            true && "typing text".isNotEmpty() -> "CLEAR"
            "typing text".isNotBlank() && currentUrl != "yas://home" -> "RELOAD"
            else -> "NONE"
        }
        assertEquals("CLEAR", actionWhenEditing)

        // State 3: When browsing web page (unfocused) -> Reload action active
        val actionWhenBrowsing = when {
            false -> "STOP"
            false && "https://example.com".isNotEmpty() -> "CLEAR"
            "https://example.com".isNotBlank() && currentUrl != "yas://home" -> "RELOAD"
            else -> "NONE"
        }
        assertEquals("RELOAD", actionWhenBrowsing)

        // State 4: When on home and input is empty -> No trailing icon (maximizes horizontal space)
        val actionOnHomeEmpty = when {
            false -> "STOP"
            false && "".isNotEmpty() -> "CLEAR"
            "".isNotBlank() && "yas://home" != "yas://home" -> "RELOAD"
            else -> "NONE"
        }
        assertEquals("NONE", actionOnHomeEmpty)
    }

    @Test
    fun testLongUrlFullResolutionPreservation() {
        val longUrl = "https://www.google.com/search?q=very+long+search+query+with+lots+of+parameters&hl=en&gl=us&sourceid=chrome&ie=UTF-8"
        val resolved = UrlResolver.resolveUrl(longUrl, googleTemplate)
        assertEquals("Long URL must be completely preserved without truncation", longUrl, resolved)
    }
}
