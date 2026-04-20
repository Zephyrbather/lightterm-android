package com.lightterm.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualKeyDefinitionParserTest {
    @Test
    fun parse_ctrlCombination_returnsControlCharacter() {
        assertEquals("\u0003", VirtualKeyDefinitionParser.parse("Ctrl+C"))
    }

    @Test
    fun parse_altCombination_prefixesEscape() {
        assertEquals("\u001bl", VirtualKeyDefinitionParser.parse("Alt+l"))
    }

    @Test
    fun parse_plainEscapes_decodesReadableSequence() {
        assertEquals("pwd\r", VirtualKeyDefinitionParser.parse("pwd\\r"))
    }
}
