package com.lightterm.core.terminal

import android.os.SystemClock
import com.lightterm.domain.model.TerminalSnapshot

class TerminalEmulator(
    private val maxLines: Int,
    initialColumns: Int = 96,
    initialRows: Int = 32,
    private val timeProvider: () -> Long = SystemClock::elapsedRealtime,
) {
    private val screenLines = mutableListOf(StringBuilder())
    private var escapeState = EscapeState.NONE
    private val csiBuffer = StringBuilder()
    private var cursorRow = 0
    private var cursorColumn = 0
    private var savedCursorRow = 0
    private var savedCursorColumn = 0
    private var columns = initialColumns
    private var rows = initialRows

    @Synchronized
    fun appendText(text: String) {
        text.forEach { character ->
            if (consumeEscapeSequence(character)) {
                return@forEach
            }

            when (character) {
                ESC -> escapeState = EscapeState.ESCAPE
                '\r' -> cursorColumn = 0
                '\n' -> lineFeed()
                '\b' -> cursorColumn = (cursorColumn - 1).coerceAtLeast(0)
                '\u000c' -> clear()
                else -> {
                    if (!character.isISOControl()) {
                        appendVisibleText(if (character == '\t') "    " else character.toString())
                    }
                }
            }
        }
    }

    @Synchronized
    fun resize(newColumns: Int, newRows: Int) {
        val resolvedColumns = newColumns.coerceAtLeast(MIN_COLUMNS)
        if (resolvedColumns != columns) {
            reflowForColumns(resolvedColumns)
        }
        columns = resolvedColumns
        rows = newRows.coerceAtLeast(MIN_ROWS)
    }

    @Synchronized
    fun clear() {
        screenLines.clear()
        screenLines.add(StringBuilder())
        escapeState = EscapeState.NONE
        csiBuffer.clear()
        cursorRow = 0
        cursorColumn = 0
        savedCursorRow = 0
        savedCursorColumn = 0
    }

    @Synchronized
    fun snapshot(): TerminalSnapshot {
        val lines = snapshotLines()
        val resolvedCursorRow = cursorRow.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val resolvedCursorColumn = cursorColumn.coerceAtLeast(0)
        return TerminalSnapshot(
            lines = lines,
            columns = columns,
            rows = rows,
            cursorRow = resolvedCursorRow,
            cursorColumn = resolvedCursorColumn,
            updatedAtMillis = timeProvider(),
        )
    }

    private fun snapshotLines(): List<String> {
        val lines = screenLines.map { it.toString() }.toMutableList()
        while (lines.size > 1 && lines.last().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }
        if (lines.isEmpty()) {
            lines.add("")
        }
        return lines
    }

    private fun reflowForColumns(newColumns: Int) {
        val oldLines = snapshotLines()
        val absoluteCursorOffset = absoluteCursorOffset(oldLines)
        val reflowedLines = oldLines.flatMap { wrapLine(it, newColumns) }
            .ifEmpty { listOf("") }

        screenLines.clear()
        reflowedLines.forEach { screenLines.add(StringBuilder(it)) }
        trimOverflow()
        restoreCursorFromAbsoluteOffset(absoluteCursorOffset)
    }

    private fun absoluteCursorOffset(lines: List<String>): Int {
        var offset = 0
        val maxRow = cursorRow.coerceAtMost(lines.lastIndex)
        repeat(maxRow) { row ->
            offset += lines[row].length
        }
        return offset + cursorColumn
    }

    private fun restoreCursorFromAbsoluteOffset(offset: Int) {
        var remaining = offset.coerceAtLeast(0)
        var row = 0
        while (row < screenLines.lastIndex) {
            val lineLength = screenLines[row].length
            if (remaining <= lineLength) {
                break
            }
            remaining -= lineLength
            row += 1
        }
        cursorRow = row.coerceIn(0, screenLines.lastIndex)
        cursorColumn = remaining.coerceAtLeast(0)
    }

    private fun wrapLine(
        line: String,
        columns: Int,
    ): List<String> {
        if (line.isEmpty()) {
            return listOf("")
        }
        return line.chunked(columns.coerceAtLeast(1))
    }

    private fun consumeEscapeSequence(character: Char): Boolean {
        when (escapeState) {
            EscapeState.NONE -> return false

            EscapeState.ESCAPE -> {
                when (character) {
                    '[' -> {
                        csiBuffer.clear()
                        escapeState = EscapeState.CSI
                    }

                    ']' -> escapeState = EscapeState.OSC
                    '7' -> {
                        saveCursor()
                        escapeState = EscapeState.NONE
                    }

                    '8' -> {
                        restoreCursor()
                        escapeState = EscapeState.NONE
                    }

                    else -> escapeState = EscapeState.NONE
                }
                return true
            }

            EscapeState.CSI -> {
                csiBuffer.append(character)
                if (character in '@'..'~') {
                    handleCsiSequence(csiBuffer.toString())
                    csiBuffer.clear()
                    escapeState = EscapeState.NONE
                }
                return true
            }

            EscapeState.OSC -> {
                escapeState = when (character) {
                    '\u0007' -> EscapeState.NONE
                    ESC -> EscapeState.OSC_ESCAPED
                    else -> EscapeState.OSC
                }
                return true
            }

            EscapeState.OSC_ESCAPED -> {
                escapeState = if (character == '\\') EscapeState.NONE else EscapeState.OSC
                return true
            }
        }
    }

    private fun appendVisibleText(text: String) {
        text.forEach(::appendVisibleCharacter)
    }

    private fun appendVisibleCharacter(character: Char) {
        val line = currentLine()
        if (cursorColumn < line.length) {
            line.setCharAt(cursorColumn, character)
        } else {
            while (line.length < cursorColumn) {
                line.append(' ')
            }
            line.append(character)
        }
        cursorColumn += 1
        if (cursorColumn >= columns) {
            lineWrap()
        }
    }

    private fun currentLine(): StringBuilder {
        ensureRow(cursorRow)
        return screenLines[cursorRow]
    }

    private fun lineFeed() {
        moveCursorToRow(cursorRow + 1)
        cursorColumn = 0
    }

    private fun lineWrap() {
        moveCursorToRow(cursorRow + 1)
        cursorColumn = 0
    }

    private fun moveCursorToRow(targetRow: Int) {
        cursorRow = targetRow.coerceAtLeast(0)
        ensureRow(cursorRow)
    }

    private fun ensureRow(row: Int) {
        while (screenLines.size <= row) {
            screenLines.add(StringBuilder())
        }
        trimOverflow()
        cursorRow = cursorRow.coerceIn(0, screenLines.lastIndex)
        savedCursorRow = savedCursorRow.coerceIn(0, screenLines.lastIndex)
    }

    private fun trimOverflow() {
        while (screenLines.size > maxLines.coerceAtLeast(1)) {
            screenLines.removeAt(0)
            cursorRow = (cursorRow - 1).coerceAtLeast(0)
            savedCursorRow = (savedCursorRow - 1).coerceAtLeast(0)
        }
        if (screenLines.isEmpty()) {
            screenLines.add(StringBuilder())
        }
    }

    private fun handleCsiSequence(sequence: String) {
        val finalChar = sequence.lastOrNull() ?: return
        val parameterText = sequence.dropLast(1)
        when (finalChar) {
            'A' -> moveCursorVertically(-firstParameter(parameterText, 1))
            'B' -> moveCursorVertically(firstParameter(parameterText, 1))
            'C' -> moveCursorHorizontally(firstParameter(parameterText, 1))
            'D' -> moveCursorHorizontally(-firstParameter(parameterText, 1))
            'E' -> {
                moveCursorVertically(firstParameter(parameterText, 1))
                cursorColumn = 0
            }

            'F' -> {
                moveCursorVertically(-firstParameter(parameterText, 1))
                cursorColumn = 0
            }

            'G' -> cursorColumn = (firstParameter(parameterText, 1) - 1).coerceAtLeast(0)
            'H',
            'f',
            -> {
                val parameters = parseParameters(parameterText)
                val row = parameters.getOrNull(0)?.takeIf { it > 0 } ?: 1
                val column = parameters.getOrNull(1)?.takeIf { it > 0 } ?: 1
                moveCursorTo(row - 1, column - 1)
            }

            'J' -> handleEraseDisplay(firstParameter(parameterText, 0))
            'K' -> handleEraseLine(firstParameter(parameterText, 0))
            'm' -> Unit
            's' -> saveCursor()
            'u' -> restoreCursor()
        }
    }

    private fun moveCursorTo(
        row: Int,
        column: Int,
    ) {
        moveCursorToRow(row)
        cursorColumn = column.coerceAtLeast(0)
    }

    private fun moveCursorVertically(delta: Int) {
        moveCursorToRow(cursorRow + delta)
    }

    private fun moveCursorHorizontally(delta: Int) {
        cursorColumn = (cursorColumn + delta).coerceAtLeast(0)
    }

    private fun handleEraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                handleEraseLine(0)
                for (row in cursorRow + 1..screenLines.lastIndex) {
                    screenLines[row] = StringBuilder()
                }
            }

            1 -> {
                handleEraseLine(1)
                for (row in 0 until cursorRow) {
                    screenLines[row] = StringBuilder()
                }
            }

            2,
            3,
            -> clear()
        }
    }

    private fun handleEraseLine(mode: Int) {
        val line = currentLine()
        when (mode) {
            0 -> {
                if (cursorColumn < line.length) {
                    line.delete(cursorColumn, line.length)
                }
            }

            1 -> {
                val end = cursorColumn.coerceAtMost(line.length)
                repeat(end) { index ->
                    line.setCharAt(index, ' ')
                }
            }

            2 -> screenLines[cursorRow] = StringBuilder()
        }
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorColumn = cursorColumn
    }

    private fun restoreCursor() {
        moveCursorTo(savedCursorRow, savedCursorColumn)
    }

    private fun parseParameters(parameters: String): List<Int> {
        return parameters
            .trimStart('?', '>')
            .split(';')
            .mapNotNull(String::toIntOrNull)
    }

    private fun firstParameter(
        parameters: String,
        defaultValue: Int,
    ): Int {
        return parseParameters(parameters)
            .firstOrNull()
            ?.takeIf { it > 0 }
            ?: defaultValue
    }

    private enum class EscapeState {
        NONE,
        ESCAPE,
        CSI,
        OSC,
        OSC_ESCAPED,
    }

    private companion object {
        private const val ESC = '\u001b'
        private const val MIN_COLUMNS = 12
        private const val MIN_ROWS = 6
    }
}
