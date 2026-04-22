package com.lightterm.ui.session

import com.lightterm.domain.model.CommandTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAutocompleteEngineTest {
    @Test
    fun buildSuggestions_blankQueryShowsRecentHistoryFirst() {
        val suggestions = CommandAutocompleteEngine.buildSuggestions(
            query = "",
            history = listOf("git status"),
            templates = listOf(
                CommandTemplate(
                    id = "tail-log",
                    label = "Tail Log",
                    template = "tail -n {{lines|200}} -f {{path|/var/log/syslog}}",
                ),
            ),
            recentRemoteDirectories = emptyList(),
            recentRemoteFiles = emptyList(),
            maxItems = 8,
        )

        assertEquals("git status", suggestions.first().fillValue)
        assertTrue(suggestions.any { it.source == CommandAutocompleteSource.COMMAND && it.fillValue == "ls " })
    }

    @Test
    fun buildSuggestions_sudoPrefixSuggestsUnderlyingCommand() {
        val suggestions = CommandAutocompleteEngine.buildSuggestions(
            query = "sudo sy",
            history = emptyList(),
            templates = emptyList(),
            recentRemoteDirectories = emptyList(),
            recentRemoteFiles = emptyList(),
            maxItems = 8,
        )

        assertEquals("sudo systemctl ", suggestions.first().fillValue)
        assertEquals(CommandAutocompleteSource.COMMAND, suggestions.first().source)
    }

    @Test
    fun buildSuggestions_gitSubcommandReplacesOnlyCurrentToken() {
        val suggestions = CommandAutocompleteEngine.buildSuggestions(
            query = "git st",
            history = emptyList(),
            templates = emptyList(),
            recentRemoteDirectories = emptyList(),
            recentRemoteFiles = emptyList(),
            maxItems = 8,
        )

        assertEquals("git status ", suggestions.first().fillValue)
        assertEquals(CommandAutocompleteSource.OPTION, suggestions.first().source)
    }

    @Test
    fun buildSuggestions_cdUsesRecentDirectoriesAsPathCompletions() {
        val suggestions = CommandAutocompleteEngine.buildSuggestions(
            query = "cd /var",
            history = emptyList(),
            templates = emptyList(),
            recentRemoteDirectories = listOf("/var/log", "/etc"),
            recentRemoteFiles = listOf("/var/log/syslog"),
            maxItems = 8,
        )

        assertEquals("cd /var/log/", suggestions.first().fillValue)
        assertEquals(CommandAutocompleteSource.PATH, suggestions.first().source)
    }
}
