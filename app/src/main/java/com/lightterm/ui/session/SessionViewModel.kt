package com.lightterm.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lightterm.data.repository.CommandHistoryRepository
import com.lightterm.data.repository.CommandTemplateRepository
import com.lightterm.core.session.RemoteDirectoryListing
import com.lightterm.core.session.RemoteTextFile
import com.lightterm.core.session.SessionManager
import com.lightterm.core.session.SessionUiState
import com.lightterm.domain.model.CommandTemplate
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

class SessionViewModel(
    private val sessionId: String,
    private val sessionManager: SessionManager,
    private val commandTemplateRepository: CommandTemplateRepository,
    private val commandHistoryRepository: CommandHistoryRepository,
) : ViewModel() {
    val uiState: StateFlow<SessionUiState> = sessionManager.observeSession(sessionId)
    val commandTemplates: StateFlow<List<CommandTemplate>> = commandTemplateRepository.templates
    private val pendingTerminalCommand = StringBuilder()

    fun sendCommand(command: String) {
        sessionManager.sendToSession(sessionId, command, appendNewLine = true)
        commandHistoryRepository.record(uiState.value.server, command)
        pendingTerminalCommand.clear()
    }

    fun sendTerminalInput(input: String) {
        sessionManager.sendToSession(sessionId, input, appendNewLine = false)
        trackTerminalInput(input)
    }

    fun currentCommandHistory(): List<String> = commandHistoryRepository.historyFor(uiState.value.server)

    fun searchCommandHistory(query: String): List<String> {
        return commandHistoryRepository.search(uiState.value.server, query)
    }

    fun clearCommandHistory() {
        commandHistoryRepository.clear(uiState.value.server)
    }

    fun addCommandTemplate(
        label: String,
        template: String,
    ): String? = runCatching {
        commandTemplateRepository.addTemplate(label, template)
    }.exceptionOrNull()?.message

    fun updateCommandTemplate(
        id: String,
        label: String,
        template: String,
    ): String? = runCatching {
        commandTemplateRepository.updateTemplate(id, label, template)
    }.exceptionOrNull()?.message

    fun removeCommandTemplate(id: String) {
        commandTemplateRepository.removeTemplate(id)
    }

    fun resetCommandTemplates() {
        commandTemplateRepository.resetDefaults()
    }

    fun resize(columns: Int, rows: Int) {
        sessionManager.resizeSession(sessionId, columns, rows)
    }

    fun reconnect() {
        sessionManager.reconnectSession(sessionId)
    }

    fun refresh() {
        sessionManager.refreshSession(sessionId)
    }

    suspend fun listRemoteDirectory(path: String? = null): RemoteDirectoryListing {
        return sessionManager.listRemoteDirectory(sessionId, path)
    }

    suspend fun readRemoteTextFile(path: String): RemoteTextFile {
        return sessionManager.readRemoteTextFile(sessionId, path)
    }

    suspend fun writeRemoteTextFile(
        path: String,
        content: String,
    ) {
        sessionManager.writeRemoteTextFile(sessionId, path, content)
    }

    suspend fun uploadRemoteFile(
        remoteDirectoryPath: String,
        remoteFileName: String,
        source: InputStream,
    ) {
        sessionManager.uploadRemoteFile(sessionId, remoteDirectoryPath, remoteFileName, source)
    }

    suspend fun downloadRemoteFile(
        remoteFilePath: String,
        sink: OutputStream,
    ) {
        sessionManager.downloadRemoteFile(sessionId, remoteFilePath, sink)
    }

    class Factory(
        private val sessionId: String,
        private val sessionManager: SessionManager,
        private val commandTemplateRepository: CommandTemplateRepository,
        private val commandHistoryRepository: CommandHistoryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SessionViewModel(
                sessionId = sessionId,
                sessionManager = sessionManager,
                commandTemplateRepository = commandTemplateRepository,
                commandHistoryRepository = commandHistoryRepository,
            ) as T
        }
    }

    private fun trackTerminalInput(input: String) {
        input.forEach { character ->
            when (character) {
                '\r',
                '\n',
                -> {
                    val command = pendingTerminalCommand.toString().trim()
                    if (command.isNotEmpty()) {
                        commandHistoryRepository.record(uiState.value.server, command)
                    }
                    pendingTerminalCommand.clear()
                }

                '\u0003',
                '\u001b',
                -> pendingTerminalCommand.clear()

                '\b',
                '\u007f',
                -> if (pendingTerminalCommand.isNotEmpty()) {
                    pendingTerminalCommand.deleteCharAt(pendingTerminalCommand.lastIndex)
                }

                else -> if (!character.isISOControl()) {
                    pendingTerminalCommand.append(character)
                }
            }
        }
    }
}
