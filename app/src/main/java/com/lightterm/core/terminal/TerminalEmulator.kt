package com.lightterm.core.terminal

import android.os.SystemClock
import com.lightterm.domain.model.TerminalSnapshot

class TerminalEmulator(
    maxLines: Int,
    initialColumns: Int = 96,
    initialRows: Int = 32,
    private val timeProvider: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lineBuffer = RingBuffer<String>(maxLines)
    private var currentLine = StringBuilder()
    private var pendingCarriageReturn = false
    private var escapeState = EscapeState.NONE
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
                '\r' -> pendingCarriageReturn = true
                '\n' -> {
                    flushLine(forceBlankLine = true)
                    pendingCarriageReturn = false
                }

                '\b' -> {
                    pendingCarriageReturn = false
                    if (currentLine.isNotEmpty()) {
                        currentLine.deleteCharAt(currentLine.lastIndex)
                    }
                }

                '\u000c' -> clear()
                else -> {
                    if (character.isISOControl()) {
                        return@forEach
                    }
                    if (pendingCarriageReturn) {
                        flushLine(forceBlankLine = currentLine.isNotEmpty())
                        pendingCarriageReturn = false
                    }
                    currentLine.append(if (character == '\t') "    " else character)
                    if (currentLine.length >= columns) {
                        flushLine()
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
        lineBuffer.clear()
        currentLine = StringBuilder()
        pendingCarriageReturn = false
        escapeState = EscapeState.NONE
    }

    @Synchronized
    fun snapshot(): TerminalSnapshot {
        val lines = buildList {
            addAll(lineBuffer.snapshot())
            if (currentLine.isNotEmpty() || isEmpty()) {
                add(currentLine.toString())
            }
        }

        return TerminalSnapshot(
            lines = lines,
            columns = columns,
            rows = rows,
            cursorRow = (lines.size - 1).coerceAtLeast(0),
            cursorColumn = currentLine.length,
            updatedAtMillis = timeProvider(),
        )
    }

    private fun flushLine(forceBlankLine: Boolean = false) {
        if (currentLine.isNotEmpty() || forceBlankLine) {
            lineBuffer.add(currentLine.toString())
        }
        currentLine = StringBuilder()
    }

    private fun reflowForColumns(newColumns: Int) {
        val bufferedLines = lineBuffer.snapshot()
        val trailingLine = currentLine.toString()

        lineBuffer.clear()
        bufferedLines
            .flatMap { wrapLine(it, newColumns) }
            .forEach(lineBuffer::add)

        if (trailingLine.isEmpty()) {
            currentLine = StringBuilder()
            return
        }

        val reflowedTrailingLine = wrapLine(trailingLine, newColumns)
        reflowedTrailingLine.dropLast(1).forEach(lineBuffer::add)
        currentLine = StringBuilder(reflowedTrailingLine.lastOrNull().orEmpty())
    }

    private fun wrapLine(line: String, columns: Int): List<String> {
        if (line.isEmpty()) {
            return listOf("")
        }
        return line.chunked(columns.coerceAtLeast(1))
    }

    private fun consumeEscapeSequence(character: Char): Boolean {
        when (escapeState) {
            EscapeState.NONE -> return false
            EscapeState.ESCAPE -> {
                escapeState = when (character) {
                    '[' -> EscapeState.CSI
                    ']' -> EscapeState.OSC
                    else -> EscapeState.NONE
                }
                return true
            }

            EscapeState.CSI -> {
                if (character in '@'..'~') {
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
