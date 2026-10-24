package com.example

import com.example.core.navigation.UrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlResolverTest {

    private val googleTemplate = "https://www.google.com/search?q="
    private val duckduckgoTemplate = "https://duckduckgo.com/?q="

    @Test
    fun testDirectUrlsPassThrough() {
        assertEquals("https://instagram.com/reel/123", UrlResolver.resolveUrl("https://instagram.com/reel/123", googleTemplate))
        assertEquals("http://example.com/test", UrlResolver.resolveUrl("http://example.com/test", googleTemplate, httpsOnly = false))
    }

    @Test
    fun testHttpsOnlyUpgrade() {
        assertEquals("https://example.com/test", UrlResolver.resolveUrl("http://example.com/test", googleTemplate, httpsOnly = true))
    }

    @Test
    fun testDomainWithoutSchemeAddsHttps() {
        assertEquals("https://github.com", UrlResolver.resolveUrl("github.com", googleTemplate))
        assertEquals("https://www.wikipedia.org/wiki/Kotlin", UrlResolver.resolveUrl("www.wikipedia.org/wiki/Kotlin", googleTemplate))
        assertEquals("https://sub.domain.co.uk", UrlResolver.resolveUrl("sub.domain.co.uk", googleTemplate))
    }

    @Test
    fun testSearchQueriesUseSearchEngine() {
        val queryResult = UrlResolver.resolveUrl("android jetpack compose", googleTemplate)
        assertTrue(queryResult.startsWith(googleTemplate))
        assertTrue(queryResult.contains("android+jetpack+compose") || queryResult.contains("android%20jetpack%20compose"))

        val persianQuery = UrlResolver.resolveUrl("مرورگر یاس", duckduckgoTemplate)
        assertTrue(persianQuery.startsWith(duckduckgoTemplate))
    }

    @Test
    fun testEmptyInputReturnsHome() {
        assertEquals("yas://home", UrlResolver.resolveUrl("", googleTemplate))
        assertEquals("yas://home", UrlResolver.resolveUrl("   ", googleTemplate))
        assertEquals("yas://home", UrlResolver.resolveUrl("yas://home", googleTemplate))
    }
}
