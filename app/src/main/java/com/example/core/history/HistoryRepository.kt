package com.example.core.history

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

class HistoryRepository internal constructor(private val storageDir: File) {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyFile by lazy { File(storageDir, HISTORY_FILE_NAME) }

    private val _historyFlow = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _historyFlow.asStateFlow()

    init {
        scope.launch {
            loadInitialHistory()
        }
    }

    private suspend fun loadInitialHistory() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readFromFile()
            _historyFlow.value = list
        }
    }

    fun recordVisit(url: String, rawTitle: String?, isIncognito: Boolean) {
        scope.launch {
            recordVisitAndWait(url, rawTitle, isIncognito)
        }
    }

    suspend fun recordVisitAndWait(url: String, rawTitle: String?, isIncognito: Boolean) = withContext(Dispatchers.IO) {
        if (isIncognito) return@withContext
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank() ||
            cleanUrl == "yas://home" ||
            cleanUrl.startsWith("about:", ignoreCase = true) ||
            cleanUrl.startsWith("chrome:", ignoreCase = true) ||
            cleanUrl.startsWith("file:", ignoreCase = true)
        ) {
            return@withContext
        }

        val displayTitle = when {
            !rawTitle.isNullOrBlank() && rawTitle != cleanUrl -> rawTitle.trim()
            else -> cleanUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        }

        mutex.withLock {
            val current = _historyFlow.value.toMutableList()
            val now = System.currentTimeMillis()

            // Deduplicate immediate reloads (same URL within 10 seconds)
            val first = current.firstOrNull()
            if (first != null && first.url == cleanUrl && (now - first.timestamp) < 10_000L) {
                current[0] = first.copy(title = displayTitle, timestamp = now)
            } else {
                current.add(0, HistoryEntry(url = cleanUrl, title = displayTitle, timestamp = now))
            }

            // Cap history list at 1000 items
            val capped = if (current.size > 1000) current.take(1000) else current
            _historyFlow.value = capped
            saveToFile(capped)
        }
    }

    suspend fun deleteEntry(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _historyFlow.value.filterNot { it.id == id }
            _historyFlow.value = updated
            saveToFile(updated)
        }
    }

    suspend fun clearRange(sinceMillis: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = if (sinceMillis <= 0L) {
                emptyList()
            } else {
                _historyFlow.value.filterNot { it.timestamp >= sinceMillis }
            }
            _historyFlow.value = updated
            saveToFile(updated)
        }
    }

    suspend fun clearAll() = clearRange(0L)

    private fun readFromFile(): List<HistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val content = historyFile.readText()
            val array = JSONArray(content)
            val list = mutableListOf<HistoryEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    HistoryEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        url = obj.optString("url", ""),
                        title = obj.optString("title", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list.filter { it.url.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveToFile(entries: List<HistoryEntry>) {
        try {
            val array = JSONArray()
            for (entry in entries) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("url", entry.url)
                obj.put("title", entry.title)
                obj.put("timestamp", entry.timestamp)
                array.put(obj)
            }
            historyFile.writeText(array.toString())
        } catch (_: Exception) {
            // Non-fatal write failure
        }
    }

    companion object {
        private const val HISTORY_FILE_NAME = "yas_browsing_history.json"

        @Volatile
        private var instance: HistoryRepository? = null

        fun getInstance(context: Context): HistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: HistoryRepository(context.applicationContext.filesDir).also { instance = it }
            }
        }

        fun createForTest(storageDir: File): HistoryRepository {
            return HistoryRepository(storageDir)
        }
    }
}
