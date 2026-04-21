package com.lightterm.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lightterm.core.session.RemoteDirectoryListing
import com.lightterm.core.session.RemoteTextFile
import com.lightterm.core.session.SessionManager
import com.lightterm.core.session.SessionUiState
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

class SessionViewModel(
    private val sessionId: String,
    private val sessionManager: SessionManager,
) : ViewModel() {
    val uiState: StateFlow<SessionUiState> = sessionManager.observeSession(sessionId)

    fun sendCommand(command: String) {
        sessionManager.sendToSession(sessionId, command, appendNewLine = true)
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SessionViewModel(
                sessionId = sessionId,
                sessionManager = sessionManager,
            ) as T
        }
    }
}
