package com.lightterm.domain.model

import java.util.UUID

data class VirtualKey(
    val id: String,
    val label: String,
    val sequence: String,
    val definition: String,
) {
    companion object {
        fun defaults(): List<VirtualKey> = listOf(
            preset(
                id = "ctrl",
                label = "Ctrl",
                definition = "Ctrl+C",
                sequence = "\u0003",
            ),
            preset(
                id = "alt",
                label = "Alt",
                definition = "Esc",
                sequence = "\u001b",
            ),
            preset(
                id = "tab",
                label = "Tab",
                definition = "Tab",
                sequence = "\t",
            ),
            preset(
                id = "esc",
                label = "Esc",
                definition = "Esc",
                sequence = "\u001b",
            ),
            preset(
                id = "up",
                label = "Up",
                definition = "Up",
                sequence = "\u001b[A",
            ),
            preset(
                id = "down",
                label = "Down",
                definition = "Down",
                sequence = "\u001b[B",
            ),
            preset(
                id = "left",
                label = "Left",
                definition = "Left",
                sequence = "\u001b[D",
            ),
            preset(
                id = "right",
                label = "Right",
                definition = "Right",
                sequence = "\u001b[C",
            ),
        )

        fun create(
            label: String,
            definition: String,
        ): VirtualKey {
            val normalizedDefinition = definition.trim()
            val normalizedLabel = label.trim().ifBlank { normalizedDefinition }
            return VirtualKey(
                id = UUID.randomUUID().toString(),
                label = normalizedLabel,
                sequence = VirtualKeyDefinitionParser.parse(normalizedDefinition),
                definition = normalizedDefinition,
            )
        }

        private fun preset(
            id: String,
            label: String,
            definition: String,
            sequence: String,
        ): VirtualKey = VirtualKey(
            id = id,
            label = label,
            sequence = sequence,
            definition = definition,
        )
    }
}
