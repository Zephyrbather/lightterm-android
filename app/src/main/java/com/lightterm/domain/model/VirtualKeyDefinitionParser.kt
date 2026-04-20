package com.lightterm.domain.model

object VirtualKeyDefinitionParser {
    fun parse(definition: String): String {
        val normalized = definition.trim()
        require(normalized.isNotEmpty()) { "请输入快捷键定义" }

        val tokens = normalized.split("+")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (tokens.size <= 1) {
            return resolveSingle(normalized)
        }

        val modifiers = tokens.dropLast(1).map(::normalizeToken)
        require(modifiers.isNotEmpty()) { "请输入有效快捷键定义" }
        require(modifiers.all { it == "ctrl" || it == "alt" }) {
            "当前仅支持 Ctrl 和 Alt 组合键"
        }

        var sequence = resolveBase(
            token = tokens.last(),
            ctrlPressed = modifiers.contains("ctrl"),
        )

        if (modifiers.contains("alt")) {
            sequence = "\u001b$sequence"
        }

        return sequence
    }

    private fun resolveSingle(token: String): String = when (normalizeToken(token)) {
        "tab" -> "\t"
        "esc", "escape" -> "\u001b"
        "enter", "return" -> "\r"
        "space" -> " "
        "up" -> "\u001b[A"
        "down" -> "\u001b[B"
        "left" -> "\u001b[D"
        "right" -> "\u001b[C"
        else -> decodeEscapes(token)
    }

    private fun resolveBase(
        token: String,
        ctrlPressed: Boolean,
    ): String {
        if (!ctrlPressed) {
            return resolveSingle(token)
        }

        val normalized = token.trim()
        require(normalized.isNotEmpty()) { "Ctrl 组合缺少主按键" }
        require(normalized.length == 1) { "Ctrl 组合当前仅支持单字符主按键" }

        val code = normalized.uppercase()[0].code and 0x1F
        return code.toChar().toString()
    }

    private fun normalizeToken(token: String): String = token
        .trim()
        .lowercase()

    private fun decodeEscapes(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current != '\\' || index == value.lastIndex) {
                append(current)
                index += 1
                continue
            }

            when (val escaped = value[index + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'e' -> append('\u001b')
                '\\' -> append('\\')
                else -> {
                    append('\\')
                    append(escaped)
                }
            }
            index += 2
        }
    }
}
