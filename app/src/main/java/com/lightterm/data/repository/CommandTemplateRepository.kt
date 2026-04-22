package com.lightterm.data.repository

import android.content.Context
import com.lightterm.domain.model.CommandTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class CommandTemplateRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _templates = MutableStateFlow(loadTemplates())

    val templates: StateFlow<List<CommandTemplate>> = _templates.asStateFlow()

    fun addTemplate(
        label: String,
        template: String,
    ): List<CommandTemplate> {
        val updated = _templates.value + CommandTemplate.create(label, template)
        return persist(updated)
    }

    fun updateTemplate(
        id: String,
        label: String,
        template: String,
    ): List<CommandTemplate> {
        val current = _templates.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) {
            return current
        }
        val updatedTemplate = CommandTemplate.create(label, template).copy(id = id)
        current[index] = updatedTemplate
        return persist(current)
    }

    fun removeTemplate(id: String): List<CommandTemplate> {
        val updated = _templates.value.filterNot { it.id == id }
        return persist(updated)
    }

    fun resetDefaults(): List<CommandTemplate> = persist(CommandTemplate.defaults())

    private fun persist(updated: List<CommandTemplate>): List<CommandTemplate> {
        val sanitized = sanitizeTemplates(updated)
        _templates.value = sanitized
        preferences.edit().putString(KEY_ITEMS, serialize(sanitized)).apply()
        return sanitized
    }

    private fun loadTemplates(): List<CommandTemplate> {
        val raw = preferences.getString(KEY_ITEMS, null) ?: return CommandTemplate.defaults()
        return runCatching {
            val items = JSONArray(raw)
            sanitizeTemplates(buildList {
                repeat(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    add(
                        CommandTemplate(
                            id = item.getString("id"),
                            label = item.getString("label"),
                            template = item.getString("template"),
                        ),
                    )
                }
            }).ifEmpty { CommandTemplate.defaults() }
        }.getOrElse { CommandTemplate.defaults() }
    }

    private fun sanitizeTemplates(current: List<CommandTemplate>): List<CommandTemplate> {
        return current.filterNot(::isLegacyCodexInline)
    }

    private fun isLegacyCodexInline(template: CommandTemplate): Boolean {
        return template.id == LEGACY_CODEX_INLINE_ID ||
            template.template.trim() == LEGACY_CODEX_INLINE_COMMAND
    }

    private fun serialize(items: List<CommandTemplate>): String = JSONArray().apply {
        items.forEach { item ->
            put(
                JSONObject().apply {
                    put("id", item.id)
                    put("label", item.label)
                    put("template", item.template)
                },
            )
        }
    }.toString()

    private companion object {
        private const val PREFERENCES_NAME = "command_templates"
        private const val KEY_ITEMS = "items"
        private const val LEGACY_CODEX_INLINE_ID = "codex-inline"
        private const val LEGACY_CODEX_INLINE_COMMAND = "codex --no-alt-screen"
    }
}
