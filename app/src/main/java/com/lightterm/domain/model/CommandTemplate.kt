package com.lightterm.domain.model

import java.util.UUID

data class CommandTemplate(
    val id: String,
    val label: String,
    val template: String,
) {
    fun placeholders(): List<CommandTemplatePlaceholder> = parseCommandTemplatePlaceholders(template)

    companion object {
        fun defaults(): List<CommandTemplate> = listOf(
            preset(
                id = "tail-log",
                label = "Tail Log",
                template = "tail -n {{lines|200}} -f {{path|/var/log/syslog}}",
            ),
            preset(
                id = "journal-service",
                label = "Journalctl Service",
                template = "journalctl -u {{service}} -n {{lines|200}} -f",
            ),
            preset(
                id = "docker-logs",
                label = "Docker Logs",
                template = "docker logs --tail {{lines|200}} -f {{container}}",
            ),
            preset(
                id = "find-port",
                label = "Find Port",
                template = "ss -lntp | grep {{port|80}}",
            ),
        )

        fun create(
            label: String,
            template: String,
        ): CommandTemplate {
            val normalizedTemplate = template.trim()
            require(normalizedTemplate.isNotBlank()) { "Command template cannot be empty" }
            val normalizedLabel = label.trim().ifBlank { inferLabel(normalizedTemplate) }
            return CommandTemplate(
                id = UUID.randomUUID().toString(),
                label = normalizedLabel,
                template = normalizedTemplate,
            )
        }

        private fun inferLabel(template: String): String {
            return template.lineSequence()
                .firstOrNull()
                ?.trim()
                ?.take(MAX_INFERRED_LABEL_LENGTH)
                ?.ifBlank { "Template" }
                ?: "Template"
        }

        private fun preset(
            id: String,
            label: String,
            template: String,
        ): CommandTemplate = CommandTemplate(
            id = id,
            label = label,
            template = template,
        )

        private const val MAX_INFERRED_LABEL_LENGTH = 28
    }
}

data class CommandTemplatePlaceholder(
    val key: String,
    val defaultValue: String? = null,
) {
    val displayLabel: String
        get() = key.replace('_', ' ').replace('-', ' ')
}

private val commandTemplatePlaceholderRegex =
    Regex("\\{\\{\\s*([A-Za-z0-9_-]+)\\s*(?:\\|\\s*([^{}]*?)\\s*)?\\}\\}")

fun parseCommandTemplatePlaceholders(template: String): List<CommandTemplatePlaceholder> {
    val ordered = linkedMapOf<String, CommandTemplatePlaceholder>()
    commandTemplatePlaceholderRegex.findAll(template).forEach { match ->
        val key = match.groupValues[1].trim()
        if (key.isBlank()) {
            return@forEach
        }
        val defaultValue = match.groupValues.getOrNull(2)
            ?.takeIf { it.isNotBlank() }
            ?.trim()
        val existing = ordered[key]
        ordered[key] = when {
            existing == null -> CommandTemplatePlaceholder(key, defaultValue)
            existing.defaultValue.isNullOrBlank() && !defaultValue.isNullOrBlank() ->
                existing.copy(defaultValue = defaultValue)

            else -> existing
        }
    }
    return ordered.values.toList()
}

fun renderCommandTemplate(
    template: String,
    values: Map<String, String>,
): String {
    return commandTemplatePlaceholderRegex.replace(template) { match ->
        val key = match.groupValues[1].trim()
        val resolvedValue = values[key]
            ?.takeIf { it.isNotEmpty() }
            ?: match.groupValues.getOrNull(2)?.trim().orEmpty()
        resolvedValue
    }
}
