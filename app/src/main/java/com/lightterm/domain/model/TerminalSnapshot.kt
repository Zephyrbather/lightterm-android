package com.lightterm.domain.model

data class TerminalSnapshot(
    val lines: List<String> = emptyList(),
    val columns: Int = 80,
    val rows: Int = 24,
    val cursorRow: Int = 0,
    val cursorColumn: Int = 0,
    val updatedAtMillis: Long = 0L,
) {
    companion object {
        val EMPTY = TerminalSnapshot()
    }
}

