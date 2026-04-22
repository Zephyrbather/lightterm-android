package com.lightterm.data.repository

import android.content.Context
import com.lightterm.domain.model.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

data class RemoteFileHistory(
    val directories: List<String> = emptyList(),
    val files: List<String> = emptyList(),
)

class RemoteFileHistoryRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var historyByServer: Map<String, RemoteFileHistory> = loadHistory()

    @Synchronized
    fun historyFor(server: ServerConfig): RemoteFileHistory {
        return historyByServer[server.remoteFileHistoryKey()] ?: RemoteFileHistory()
    }

    @Synchronized
    fun recordDirectory(
        server: ServerConfig,
        path: String,
    ): RemoteFileHistory {
        return update(server) { history ->
            history.copy(directories = pushRecent(history.directories, path))
        }
    }

    @Synchronized
    fun recordFile(
        server: ServerConfig,
        path: String,
    ): RemoteFileHistory {
        return update(server) { history ->
            history.copy(files = pushRecent(history.files, path))
        }
    }

    private fun update(
        server: ServerConfig,
        transform: (RemoteFileHistory) -> RemoteFileHistory,
    ): RemoteFileHistory {
        val key = server.remoteFileHistoryKey()
        val updatedMap = historyByServer.toMutableMap()
        val updatedHistory = transform(updatedMap[key] ?: RemoteFileHistory())
        if (updatedHistory.directories.isEmpty() && updatedHistory.files.isEmpty()) {
            updatedMap.remove(key)
        } else {
            updatedMap[key] = updatedHistory
        }
        persist(updatedMap)
        return updatedHistory
    }

    private fun pushRecent(
        items: List<String>,
        rawValue: String,
    ): List<String> {
        val normalized = rawValue.trim()
        if (normalized.isBlank()) {
            return items
        }
        return buildList {
            add(normalized)
            items.forEach { item ->
                if (item != normalized && size < MAX_HISTORY_PER_SERVER) {
                    add(item)
                }
            }
        }
    }

    private fun persist(updated: Map<String, RemoteFileHistory>) {
        historyByServer = updated
        preferences.edit().putString(KEY_ITEMS, serialize(updated)).apply()
    }

    private fun loadHistory(): Map<String, RemoteFileHistory> {
        val raw = preferences.getString(KEY_ITEMS, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { key ->
                    val item = root.optJSONObject(key) ?: return@forEach
                    val history = RemoteFileHistory(
                        directories = item.optJSONArray(KEY_DIRECTORIES).toNonBlankStringList(),
                        files = item.optJSONArray(KEY_FILES).toNonBlankStringList(),
                    )
                    if (history.directories.isNotEmpty() || history.files.isNotEmpty()) {
                        put(key, history)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun serialize(items: Map<String, RemoteFileHistory>): String = JSONObject().apply {
        items.forEach { (key, history) ->
            put(
                key,
                JSONObject().apply {
                    put(KEY_DIRECTORIES, JSONArray().apply { history.directories.forEach(::put) })
                    put(KEY_FILES, JSONArray().apply { history.files.forEach(::put) })
                },
            )
        }
    }.toString()

    private fun JSONArray?.toNonBlankStringList(): List<String> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            repeat(length()) { index ->
                optString(index)
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }

    private fun ServerConfig.remoteFileHistoryKey(): String {
        return if (id > 0) {
            "server:$id"
        } else {
            "route:${routeLabel()}|demo:${demoMode}"
        }
    }

    private companion object {
        private const val PREFERENCES_NAME = "remote_file_history"
        private const val KEY_ITEMS = "items"
        private const val KEY_DIRECTORIES = "directories"
        private const val KEY_FILES = "files"
        private const val MAX_HISTORY_PER_SERVER = 6
    }
}
