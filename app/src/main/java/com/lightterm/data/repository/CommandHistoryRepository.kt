package com.lightterm.data.repository

import android.content.Context
import com.lightterm.domain.model.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class CommandHistoryRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _history = MutableStateFlow(loadHistory())

    val history: StateFlow<Map<String, List<String>>> = _history.asStateFlow()

    fun record(
        server: ServerConfig,
        command: String,
    ) {
        val normalizedCommand = command
            .trimEnd('\r', '\n')
            .trim()
        if (normalizedCommand.isBlank()) {
            return
        }

        val key = server.historyKey()
        val updated = _history.value.toMutableMap()
        val entries = updated[key].orEmpty().toMutableList()
        entries.remove(normalizedCommand)
        entries.add(0, normalizedCommand)
        if (entries.size > MAX_HISTORY_PER_SERVER) {
            entries.subList(MAX_HISTORY_PER_SERVER, entries.size).clear()
        }
        updated[key] = entries
        persist(updated)
    }

    fun clear(server: ServerConfig) {
        val key = server.historyKey()
        if (!_history.value.containsKey(key)) {
            return
        }
        val updated = _history.value.toMutableMap()
        updated.remove(key)
        persist(updated)
    }

    fun historyFor(server: ServerConfig): List<String> = _history.value[server.historyKey()].orEmpty()

    fun search(
        server: ServerConfig,
        query: String,
    ): List<String> {
        val normalizedQuery = query.trim()
        return historyFor(server).filter { command ->
            normalizedQuery.isBlank() || command.contains(normalizedQuery, ignoreCase = true)
        }
    }

    private fun persist(updated: Map<String, List<String>>) {
        _history.value = updated
        preferences.edit().putString(KEY_ITEMS, serialize(updated)).apply()
    }

    private fun loadHistory(): Map<String, List<String>> {
        val raw = preferences.getString(KEY_ITEMS, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { key ->
                    val array = root.optJSONArray(key) ?: JSONArray()
                    val commands = buildList {
                        repeat(array.length()) { index ->
                            val value = array.optString(index)
                            if (value.isNotBlank()) {
                                add(value)
                            }
                        }
                    }
                    if (commands.isNotEmpty()) {
                        put(key, commands)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun serialize(items: Map<String, List<String>>): String = JSONObject().apply {
        items.forEach { (key, commands) ->
            put(
                key,
                JSONArray().apply {
                    commands.forEach(::put)
                },
            )
        }
    }.toString()

    private fun ServerConfig.historyKey(): String {
        return if (id > 0) {
            "server:$id"
        } else {
            "route:${routeLabel()}|demo:${demoMode}"
        }
    }

    private companion object {
        private const val PREFERENCES_NAME = "command_history"
        private const val KEY_ITEMS = "items"
        private const val MAX_HISTORY_PER_SERVER = 50
    }
}
