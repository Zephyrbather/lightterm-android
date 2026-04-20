package com.lightterm.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalEmulatorTest {
    @Test
    fun appendText_keepsCrLfTerminatedOutputVisible() {
        val emulator = TerminalEmulator(maxLines = 32, timeProvider = { 0L })

        emulator.appendText("ready\r\nvalue\r\n")

        assertEquals(listOf("ready", "value"), emulator.snapshot().lines)
    }

    @Test
    fun appendText_treatsBareCarriageReturnAsReadableLineAdvance() {
        val emulator = TerminalEmulator(maxLines = 32, timeProvider = { 0L })

        emulator.appendText("first\rsecond")

        assertEquals(listOf("first", "second"), emulator.snapshot().lines)
    }

    @Test
    fun appendText_stripsAnsiSequencesAndKeepsVisibleText() {
        val emulator = TerminalEmulator(maxLines = 32, timeProvider = { 0L })

        emulator.appendText("\u001b[32mhello\u001b[0m\r\n")

        assertEquals(listOf("hello"), emulator.snapshot().lines)
    }

    @Test
    fun appendText_stripsOscTitleSequences() {
        val emulator = TerminalEmulator(maxLines = 32, timeProvider = { 0L })

        emulator.appendText("\u001b]0;remote-title\u0007pwd\r\n")

        assertEquals(listOf("pwd"), emulator.snapshot().lines)
    }
}
