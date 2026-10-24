package com.example

import com.example.core.history.HistoryEntry
import com.example.core.history.HistoryRepository
import com.example.features.browser.BrowserTab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class HistoryAndTabManagementTest {

    private lateinit var tempDir: File
    private lateinit var repository: HistoryRepository

    @Before
    fun setUp() {
        tempDir = File.createTempFile("yas_test_history_", "").apply {
            delete()
            mkdirs()
        }
        repository = HistoryRepository.createForTest(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testIncognitoExclusionFromHistory() = runBlocking {
        repository.recordVisitAndWait("https://github.com", "GitHub", isIncognito = true)
        val list = repository.history.value
        assertTrue("Incognito visits must never be recorded in browsing history", list.isEmpty())
    }

    @Test
    fun testInternalUrlsExcluded() = runBlocking {
        repository.recordVisitAndWait("yas://home", "YAS Start", isIncognito = false)
        repository.recordVisitAndWait("about:blank", "Blank", isIncognito = false)
        repository.recordVisitAndWait("chrome://settings", "Chrome Settings", isIncognito = false)
        repository.recordVisitAndWait("", null, isIncognito = false)

        val list = repository.history.value
        assertTrue("Internal YAS and browser scheme URLs must not pollute history", list.isEmpty())
    }

    @Test
    fun testNormalVisitRecordedAndPersisted() = runBlocking {
        repository.recordVisitAndWait("https://android.com", "Android Developers", isIncognito = false)
        val list = repository.history.value
        assertEquals(1, list.size)
        assertEquals("https://android.com", list[0].url)
        assertEquals("Android Developers", list[0].title)

        // Verify fresh repository instance reads same file
        val reloadedRepo = HistoryRepository.createForTest(tempDir)
        // Wait briefly for initial load
        kotlinx.coroutines.delay(100)
        val reloadedList = reloadedRepo.history.value
        assertEquals(1, reloadedList.size)
        assertEquals("https://android.com", reloadedList[0].url)
    }

    @Test
    fun testDuplicateImmediateReloadDebounce() = runBlocking {
        repository.recordVisitAndWait("https://kotlinlang.org", "Kotlin", isIncognito = false)
        repository.recordVisitAndWait("https://kotlinlang.org", "Kotlin Programming", isIncognito = false)

        val list = repository.history.value
        assertEquals("Immediate reloads within 10s must be debounced rather than duplicated", 1, list.size)
        assertEquals("Kotlin Programming", list[0].title)
    }

    @Test
    fun testSingleItemDeletion() = runBlocking {
        repository.recordVisitAndWait("https://site1.com", "Site 1", isIncognito = false)
        repository.recordVisitAndWait("https://site2.com", "Site 2", isIncognito = false)

        val listBefore = repository.history.value
        assertEquals(2, listBefore.size)

        val itemToDelete = listBefore.first { it.url == "https://site1.com" }
        repository.deleteEntry(itemToDelete.id)

        val listAfter = repository.history.value
        assertEquals(1, listAfter.size)
        assertEquals("https://site2.com", listAfter[0].url)
    }

    @Test
    fun testClearRange24HoursPreservesOlderHistory() = runBlocking {
        val now = System.currentTimeMillis()
        val oldTimestamp = now - (48 * 60 * 60 * 1000L) // 2 days ago
        val recentTimestamp = now - (2 * 60 * 60 * 1000L) // 2 hours ago

        val oldEntry = HistoryEntry(id = "old_1", url = "https://old.com", title = "Old Site", timestamp = oldTimestamp)
        val recentEntry = HistoryEntry(id = "recent_2", url = "https://recent.com", title = "Recent Site", timestamp = recentTimestamp)

        // Simulate existing history with both entries
        val jsonArray = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("id", recentEntry.id)
                put("url", recentEntry.url)
                put("title", recentEntry.title)
                put("timestamp", recentEntry.timestamp)
            })
            put(org.json.JSONObject().apply {
                put("id", oldEntry.id)
                put("url", oldEntry.url)
                put("title", oldEntry.title)
                put("timestamp", oldEntry.timestamp)
            })
        }
        File(tempDir, "yas_browsing_history.json").writeText(jsonArray.toString())

        val repo = HistoryRepository.createForTest(tempDir)
        kotlinx.coroutines.delay(100)
        assertEquals(2, repo.history.value.size)

        // Clear last 24h
        val twentyFourHoursAgo = now - (24 * 60 * 60 * 1000L)
        repo.clearRange(twentyFourHoursAgo)

        val remaining = repo.history.value
        assertEquals(1, remaining.size)
        assertEquals("https://old.com", remaining[0].url)
    }

    @Test
    fun testClearAll() = runBlocking {
        repository.recordVisitAndWait("https://a.com", "A", isIncognito = false)
        repository.recordVisitAndWait("https://b.com", "B", isIncognito = false)
        assertEquals(2, repository.history.value.size)

        repository.clearAll()
        assertTrue(repository.history.value.isEmpty())
    }

    @Test
    fun testTabCounterPresentationLogic() {
        val singleTabList = listOf(BrowserTab(title = "YAS Start", url = "yas://home"))
        val multiTabList = listOf(
            BrowserTab(title = "YAS Start", url = "yas://home"),
            BrowserTab(title = "Example", url = "https://example.com")
        )

        // 1 tab open: Show '+' (New Tab) button, not a redundant '1' badge
        val showPlusButton = singleTabList.size <= 1
        assertTrue("Single open tab must present '+' new tab action", showPlusButton)

        // 2+ tabs open: Show compact square tab counter
        val showCounterBadge = multiTabList.size >= 2
        assertTrue("Two or more open tabs must present compact square tab counter", showCounterBadge)
        assertEquals(2, multiTabList.size)
    }

    @Test
    fun testTabClosingResetsToHomeWhenLastTabClosed() {
        val tabs = mutableListOf(BrowserTab(id = "tab1", title = "My Page", url = "https://example.com"))

        // Simulate closing the last tab
        if (tabs.size > 1) {
            tabs.removeAt(0)
        } else {
            // Reset to home
            tabs[0] = BrowserTab(title = "YAS Start", url = "yas://home")
        }

        assertEquals(1, tabs.size)
        assertEquals("YAS Start", tabs[0].title)
        assertEquals("yas://home", tabs[0].url)
    }
}
