package com.lightterm.data.repository

import android.content.Context
import com.lightterm.domain.model.VirtualKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class VirtualKeyRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _keys = MutableStateFlow(loadKeys())

    val keys: StateFlow<List<VirtualKey>> = _keys.asStateFlow()

    fun addKey(
        label: String,
        definition: String,
    ): List<VirtualKey> {
        val updated = _keys.value + VirtualKey.create(label, definition)
        return persist(updated)
    }

    fun removeKey(id: String): List<VirtualKey> {
        val updated = _keys.value.filterNot { it.id == id }
        return persist(updated)
    }

    fun moveKey(
        id: String,
        offset: Int,
    ): List<VirtualKey> {
        val current = _keys.value.toMutableList()
        val currentIndex = current.indexOfFirst { it.id == id }
        if (currentIndex == -1) {
            return current
        }

        val targetIndex = (currentIndex + offset).coerceIn(0, current.lastIndex)
        if (currentIndex == targetIndex) {
            return current
        }

        val item = current.removeAt(currentIndex)
        current.add(targetIndex, item)
        return persist(current)
    }

    fun resetDefaults(): List<VirtualKey> = persist(VirtualKey.defaults())

    private fun persist(updated: List<VirtualKey>): List<VirtualKey> {
        _keys.value = updated
        preferences.edit().putString(KEY_ITEMS, serialize(updated)).apply()
        return updated
    }

    private fun loadKeys(): List<VirtualKey> {
        val raw = preferences.getString(KEY_ITEMS, null) ?: return VirtualKey.defaults()
        return runCatching {
            val items = JSONArray(raw)
            buildList {
                repeat(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    add(
                        VirtualKey(
                            id = item.getString("id"),
                            label = item.getString("label"),
                            sequence = item.getString("sequence"),
                            definition = item.optString("definition", item.getString("label")),
                        ),
                    )
                }
            }.ifEmpty { VirtualKey.defaults() }
        }.getOrElse { VirtualKey.defaults() }
    }

    private fun serialize(items: List<VirtualKey>): String = JSONArray().apply {
        items.forEach { item ->
            put(
                JSONObject().apply {
                    put("id", item.id)
                    put("label", item.label)
                    put("sequence", item.sequence)
                    put("definition", item.definition)
                },
            )
        }
    }.toString()

    private companion object {
        private const val PREFERENCES_NAME = "virtual_keys"
        private const val KEY_ITEMS = "items"
    }
}
