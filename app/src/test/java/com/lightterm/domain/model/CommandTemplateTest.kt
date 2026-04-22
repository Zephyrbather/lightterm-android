package com.lightterm.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandTemplateTest {
    @Test
    fun parsePlaceholders_deduplicatesByKeyAndKeepsDefault() {
        assertEquals(
            listOf(
                CommandTemplatePlaceholder("service", null),
                CommandTemplatePlaceholder("lines", "200"),
            ),
            parseCommandTemplatePlaceholders(
                "journalctl -u {{service}} -n {{lines|200}} --grep {{service}}",
            ),
        )
    }

    @Test
    fun renderCommandTemplate_usesProvidedValuesAndDefaults() {
        assertEquals(
            "tail -n 100 -f /tmp/demo.log",
            renderCommandTemplate(
                "tail -n {{lines|200}} -f {{path}}",
                mapOf(
                    "lines" to "100",
                    "path" to "/tmp/demo.log",
                ),
            ),
        )
    }

    @Test
    fun defaults_excludeLegacyCodexInlinePreset() {
        assertEquals(
            false,
            CommandTemplate.defaults().any { template ->
                template.template == "codex --no-alt-screen"
            },
        )
    }
}
