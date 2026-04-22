package com.lightterm.ui.session

import com.lightterm.domain.model.CommandTemplate
import com.lightterm.domain.model.renderCommandTemplate

internal object CommandAutocompleteEngine {
    fun buildSuggestions(
        query: String,
        history: List<String>,
        templates: List<CommandTemplate>,
        recentRemoteDirectories: List<String>,
        recentRemoteFiles: List<String>,
        maxItems: Int,
    ): List<CommandAutocompleteSuggestion> {
        val context = CompletionContext(query)
        val candidates = mutableListOf<AutocompleteCandidate>()

        addHistoryCandidates(candidates, context, history)
        addTemplateCandidates(candidates, context, templates)

        if (context.shouldSuggestCommands) {
            addCommandCandidates(candidates, context)
        }

        context.commandName?.let { commandName ->
            if (!context.shouldSuggestCommands) {
                addCommandDetailCandidates(candidates, context, commandName)
                addPathCandidates(
                    candidates = candidates,
                    context = context,
                    commandName = commandName,
                    recentRemoteDirectories = recentRemoteDirectories,
                    recentRemoteFiles = recentRemoteFiles,
                )
            }
        }

        return candidates
            .sortedWith(
                compareBy<AutocompleteCandidate> { it.categoryPriority }
                    .thenBy { it.matchPriority }
                    .thenBy { it.sourcePriority }
                    .thenBy { it.suggestion.displayLabel.length },
            )
            .distinctBy { it.suggestion.fillValue.lowercase() }
            .take(maxItems)
            .map(AutocompleteCandidate::suggestion)
    }

    private fun addHistoryCandidates(
        candidates: MutableList<AutocompleteCandidate>,
        context: CompletionContext,
        history: List<String>,
    ) {
        history.forEachIndexed { index, command ->
            if (!matchesQuery(command, context.fullQuery)) {
                return@forEachIndexed
            }
            candidates += AutocompleteCandidate(
                suggestion = CommandAutocompleteSuggestion(
                    displayLabel = command,
                    fillValue = command,
                    source = CommandAutocompleteSource.HISTORY,
                ),
                categoryPriority = if (context.fullQuery.isBlank()) 0 else 2,
                matchPriority = if (context.fullQuery.isBlank()) index else matchScore(command, context.fullQuery),
                sourcePriority = 0,
            )
        }
    }

    private fun addTemplateCandidates(
        candidates: MutableList<AutocompleteCandidate>,
        context: CompletionContext,
        templates: List<CommandTemplate>,
    ) {
        templates.forEachIndexed { index, template ->
            val rendered = renderCommandTemplate(
                template = template.template,
                values = template.placeholders().associate { placeholder ->
                    placeholder.key to (placeholder.defaultValue ?: placeholder.key)
                },
            )
            if (!matchesQuery(template.label, context.fullQuery) && !matchesQuery(rendered, context.fullQuery)) {
                return@forEachIndexed
            }
            candidates += AutocompleteCandidate(
                suggestion = CommandAutocompleteSuggestion(
                    displayLabel = template.label,
                    fillValue = rendered,
                    source = CommandAutocompleteSource.TEMPLATE,
                ),
                categoryPriority = if (context.fullQuery.isBlank()) 3 else 4,
                matchPriority = minOf(
                    matchScore(template.label, context.fullQuery),
                    matchScore(rendered, context.fullQuery),
                ) + index,
                sourcePriority = 4,
            )
        }
    }

    private fun addCommandCandidates(
        candidates: MutableList<AutocompleteCandidate>,
        context: CompletionContext,
    ) {
        COMMAND_SPECS.forEachIndexed { index, spec ->
            if (!matchesQuery(spec.command, context.currentTokenQuery)) {
                return@forEachIndexed
            }
            candidates += AutocompleteCandidate(
                suggestion = CommandAutocompleteSuggestion(
                    displayLabel = spec.command,
                    fillValue = context.replaceCurrentToken(spec.command),
                    source = CommandAutocompleteSource.COMMAND,
                ),
                categoryPriority = if (context.fullQuery.isBlank()) 1 else 0,
                matchPriority = matchScore(spec.command, context.currentTokenQuery) + index,
                sourcePriority = 1,
            )
        }
    }

    private fun addCommandDetailCandidates(
        candidates: MutableList<AutocompleteCandidate>,
        context: CompletionContext,
        commandName: String,
    ) {
        val spec = COMMAND_SPECS_BY_NAME[commandName] ?: return
        val prefersOptions = context.currentTokenQuery.startsWith("-") || context.currentTokenQuery.isBlank()

        if (!context.currentTokenQuery.startsWith("-")) {
            spec.suggestions.forEachIndexed { index, suggestion ->
                if (!matchesQuery(suggestion, context.currentTokenQuery)) {
                    return@forEachIndexed
                }
                candidates += AutocompleteCandidate(
                    suggestion = CommandAutocompleteSuggestion(
                        displayLabel = suggestion,
                        fillValue = context.replaceCurrentToken(suggestion),
                        source = CommandAutocompleteSource.OPTION,
                    ),
                    categoryPriority = 0,
                    matchPriority = matchScore(suggestion, context.currentTokenQuery) + index,
                    sourcePriority = 2,
                )
            }
        }

        if (!prefersOptions) {
            return
        }

        spec.options.forEachIndexed { index, option ->
            if (!matchesQuery(option, context.currentTokenQuery)) {
                return@forEachIndexed
            }
            candidates += AutocompleteCandidate(
                suggestion = CommandAutocompleteSuggestion(
                    displayLabel = option,
                    fillValue = context.replaceCurrentToken(option),
                    source = CommandAutocompleteSource.OPTION,
                ),
                categoryPriority = 1,
                matchPriority = matchScore(option, context.currentTokenQuery) + index,
                sourcePriority = 2,
            )
        }
    }

    private fun addPathCandidates(
        candidates: MutableList<AutocompleteCandidate>,
        context: CompletionContext,
        commandName: String,
        recentRemoteDirectories: List<String>,
        recentRemoteFiles: List<String>,
    ) {
        val pathMode = COMMAND_SPECS_BY_NAME[commandName]?.pathMode ?: PathMode.NONE
        if (pathMode == PathMode.NONE) {
            return
        }

        recentPathsForMode(pathMode, recentRemoteDirectories, recentRemoteFiles)
            .forEachIndexed { index, path ->
                if (!matchesPath(path, context.currentTokenQuery)) {
                    return@forEachIndexed
                }
                val isDirectory = recentRemoteDirectories.contains(path)
                val replacement = if (isDirectory) {
                    path.trimEnd('/') + "/"
                } else {
                    path
                }
                candidates += AutocompleteCandidate(
                    suggestion = CommandAutocompleteSuggestion(
                        displayLabel = replacement,
                        fillValue = context.replaceCurrentToken(
                            replacement = replacement,
                            appendTrailingSpace = !isDirectory,
                        ),
                        source = CommandAutocompleteSource.PATH,
                    ),
                    categoryPriority = if (pathMode == PathMode.DIRECTORY_ONLY || looksLikePathQuery(context.currentTokenQuery)) 0 else 1,
                    matchPriority = pathMatchScore(path, context.currentTokenQuery) + index,
                    sourcePriority = 3,
                )
            }
    }

    private fun recentPathsForMode(
        pathMode: PathMode,
        directories: List<String>,
        files: List<String>,
    ): List<String> {
        return when (pathMode) {
            PathMode.NONE -> emptyList()
            PathMode.DIRECTORY_ONLY -> directories
            PathMode.DIRECTORY_FIRST -> directories + files
            PathMode.FILE_FIRST -> files + directories
        }.distinct()
    }

    private fun matchesQuery(candidate: String, query: String): Boolean {
        return query.isBlank() || candidate.contains(query.trim(), ignoreCase = true)
    }

    private fun matchesPath(path: String, query: String): Boolean {
        val normalizedQuery = normalizePathQuery(query)
        if (normalizedQuery.isBlank()) {
            return true
        }
        val basename = path.trimEnd('/').substringAfterLast('/')
        val queryTail = normalizedQuery.trimEnd('/').substringAfterLast('/')
        return path.contains(normalizedQuery, ignoreCase = true) ||
            basename.startsWith(queryTail, ignoreCase = true) ||
            basename.contains(queryTail, ignoreCase = true)
    }

    private fun normalizePathQuery(query: String): String {
        return query.trim()
            .removePrefix("~/")
            .removePrefix("./")
    }

    private fun looksLikePathQuery(query: String): Boolean {
        val normalized = query.trim()
        return normalized.startsWith("/") ||
            normalized.startsWith("~") ||
            normalized.startsWith(".") ||
            normalized.contains("/")
    }

    private fun matchScore(candidate: String, query: String): Int {
        if (query.isBlank()) {
            return 0
        }
        val normalizedCandidate = candidate.lowercase()
        val normalizedQuery = query.trim().lowercase()
        val wordStarts = normalizedCandidate.split(WORD_BOUNDARY_REGEX)
        return when {
            normalizedCandidate == normalizedQuery -> 0
            normalizedCandidate.startsWith(normalizedQuery) -> 1
            wordStarts.any { it.startsWith(normalizedQuery) } -> 2
            normalizedCandidate.contains(normalizedQuery) -> 3
            else -> 4
        }
    }

    private fun pathMatchScore(path: String, query: String): Int {
        val normalizedQuery = normalizePathQuery(query)
        if (normalizedQuery.isBlank()) {
            return 0
        }
        val basename = path.trimEnd('/').substringAfterLast('/').lowercase()
        val queryTail = normalizedQuery.trimEnd('/').substringAfterLast('/').lowercase()
        return when {
            basename == queryTail -> 0
            basename.startsWith(queryTail) -> 1
            path.lowercase().startsWith(normalizedQuery.lowercase()) -> 2
            basename.contains(queryTail) -> 3
            else -> 4
        }
    }

    private data class AutocompleteCandidate(
        val suggestion: CommandAutocompleteSuggestion,
        val categoryPriority: Int,
        val matchPriority: Int,
        val sourcePriority: Int,
    )

    private data class CompletionContext(
        val rawQuery: String,
    ) {
        private val trimmedQuery = rawQuery.trim()
        private val endsWithWhitespace = rawQuery.lastOrNull()?.isWhitespace() == true
        private val tokens = if (trimmedQuery.isBlank()) emptyList() else trimmedQuery.split(WHITESPACE_REGEX)
        private val commandTokenIndex = if (tokens.firstOrNull() in COMMAND_WRAPPERS) 1 else 0
        private val currentTokenStart = when {
            rawQuery.isBlank() -> 0
            endsWithWhitespace -> rawQuery.length
            else -> rawQuery.indexOfLast(Char::isWhitespace).let { whitespaceIndex ->
                if (whitespaceIndex < 0) 0 else whitespaceIndex + 1
            }
        }
        private val currentTokenIndex = when {
            trimmedQuery.isBlank() -> 0
            endsWithWhitespace -> tokens.size
            else -> tokens.lastIndex
        }

        val fullQuery: String
            get() = trimmedQuery

        val currentTokenQuery: String
            get() = rawQuery.substring(currentTokenStart).trim()

        val commandName: String?
            get() = tokens.getOrNull(commandTokenIndex)?.lowercase()

        val shouldSuggestCommands: Boolean
            get() = currentTokenIndex == 0 ||
                currentTokenIndex == commandTokenIndex ||
                tokens.size <= commandTokenIndex

        fun replaceCurrentToken(
            replacement: String,
            appendTrailingSpace: Boolean = true,
        ): String {
            val suffix = if (appendTrailingSpace && replacement.isNotBlank() && !replacement.last().isWhitespace()) {
                " "
            } else {
                ""
            }
            return rawQuery.substring(0, currentTokenStart) + replacement + suffix
        }
    }

    private data class CommandSpec(
        val command: String,
        val suggestions: List<String> = emptyList(),
        val options: List<String> = emptyList(),
        val pathMode: PathMode = PathMode.NONE,
    )

    private enum class PathMode {
        NONE,
        DIRECTORY_ONLY,
        DIRECTORY_FIRST,
        FILE_FIRST,
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val WORD_BOUNDARY_REGEX = Regex("[\\s/\\-_:]+")
    private val COMMAND_WRAPPERS = setOf("sudo")
    private val COMMAND_SPECS = listOf(
        CommandSpec("ls", options = listOf("-lah", "-ltr", "--color=auto"), pathMode = PathMode.DIRECTORY_FIRST),
        CommandSpec("cd", suggestions = listOf("~", "/etc", "/var/log"), pathMode = PathMode.DIRECTORY_ONLY),
        CommandSpec("pwd"),
        CommandSpec("clear"),
        CommandSpec("cat", pathMode = PathMode.FILE_FIRST),
        CommandSpec("less", pathMode = PathMode.FILE_FIRST),
        CommandSpec("tail", options = listOf("-f", "-n 200"), pathMode = PathMode.FILE_FIRST),
        CommandSpec("head", options = listOf("-n 100"), pathMode = PathMode.FILE_FIRST),
        CommandSpec("grep", options = listOf("-n", "-i", "-R", "--color=auto"), pathMode = PathMode.FILE_FIRST),
        CommandSpec("find", options = listOf("-name", "-type f", "-mtime -1"), pathMode = PathMode.DIRECTORY_FIRST),
        CommandSpec("ssh", options = listOf("-p 22", "-i ~/.ssh/id_rsa")),
        CommandSpec("docker", suggestions = listOf("ps", "ps -a", "logs --tail 200 -f", "exec -it", "images", "compose logs -f")),
        CommandSpec("kubectl", suggestions = listOf("get pods", "get svc", "describe pod", "logs -f", "exec -it")),
        CommandSpec("systemctl", suggestions = listOf("status", "restart", "stop", "start", "daemon-reload")),
        CommandSpec("journalctl", options = listOf("-u", "-n 200", "-f", "--since today")),
        CommandSpec("git", suggestions = listOf("status", "pull --rebase", "push", "log --oneline --graph --decorate", "diff", "switch", "checkout", "stash", "fetch --all --prune")),
        CommandSpec("ps", suggestions = listOf("aux", "-ef")),
        CommandSpec("df", options = listOf("-h")),
        CommandSpec("du", options = listOf("-sh"), pathMode = PathMode.DIRECTORY_FIRST),
        CommandSpec("chmod", suggestions = listOf("755", "+x"), pathMode = PathMode.FILE_FIRST),
        CommandSpec("chown", suggestions = listOf("root:root"), pathMode = PathMode.FILE_FIRST),
        CommandSpec("rm", options = listOf("-rf", "-f"), pathMode = PathMode.FILE_FIRST),
        CommandSpec("cp", options = listOf("-r"), pathMode = PathMode.FILE_FIRST),
        CommandSpec("mv", pathMode = PathMode.FILE_FIRST),
        CommandSpec("mkdir", options = listOf("-p"), pathMode = PathMode.DIRECTORY_FIRST),
        CommandSpec("touch", pathMode = PathMode.FILE_FIRST),
        CommandSpec("tar", options = listOf("-czf", "-xzf"), pathMode = PathMode.FILE_FIRST),
        CommandSpec("curl", options = listOf("-I", "-L", "-O")),
        CommandSpec("wget", options = listOf("-O")),
        CommandSpec("vim", pathMode = PathMode.FILE_FIRST),
        CommandSpec("nano", pathMode = PathMode.FILE_FIRST),
        CommandSpec("top"),
        CommandSpec("htop"),
        CommandSpec("sudo"),
    )
    private val COMMAND_SPECS_BY_NAME = COMMAND_SPECS.associateBy { it.command }
}

internal data class CommandAutocompleteSuggestion(
    val displayLabel: String,
    val fillValue: String,
    val source: CommandAutocompleteSource,
)

internal enum class CommandAutocompleteSource {
    HISTORY,
    TEMPLATE,
    COMMAND,
    OPTION,
    PATH,
}
