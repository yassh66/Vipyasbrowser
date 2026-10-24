package com.example.core.navigation

import java.net.URLEncoder

object UrlResolver {

    private val DOMAIN_REGEX = Regex(
        "^(https?://)?" + // optional scheme
        "([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}" + // domain
        "(:[0-9]{1,5})?" + // optional port
        "(/.*)?$" // optional path
    )

    private val IP_REGEX = Regex(
        "^(https?://)?" +
        "([0-9]{1,3}\\.){3}[0-9]{1,3}" +
        "(:[0-9]{1,5})?" +
        "(/.*)?$"
    )

    /**
     * Resolves a raw query or address entered by the user into a validated URL.
     * If the input is a valid domain or URL, navigates directly (with HTTPS upgrade if configured).
     * If the input is a search query, formats it using the selected search engine URL template.
     */
    fun resolveUrl(
        rawInput: String,
        searchEngineTemplate: String = "https://www.google.com/search?q=",
        httpsOnly: Boolean = false
    ): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return "yas://home"
        if (trimmed == "yas://home") return "yas://home"

        // Handle explicit schemes
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return if (httpsOnly && trimmed.startsWith("http://", ignoreCase = true)) {
                "https://" + trimmed.removePrefix("http://")
            } else {
                trimmed
            }
        }

        // Handle localhost
        if (trimmed.startsWith("localhost", ignoreCase = true)) {
            return "http://$trimmed"
        }

        // Check if it's a domain name or IP without scheme
        val isDomain = DOMAIN_REGEX.matches(trimmed) && !trimmed.contains(" ")
        val isIp = IP_REGEX.matches(trimmed) && !trimmed.contains(" ")

        if (isDomain || isIp) {
            val scheme = if (httpsOnly) "https://" else "https://"
            return "$scheme$trimmed"
        }

        // Otherwise, it's a search query
        val encodedQuery = try {
            URLEncoder.encode(trimmed, "UTF-8")
        } catch (e: Exception) {
            trimmed.replace(" ", "+")
        }
        return "$searchEngineTemplate$encodedQuery"
    }
}
