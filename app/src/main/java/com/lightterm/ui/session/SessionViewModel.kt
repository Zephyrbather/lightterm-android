package com.lightterm.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lightterm.core.session.SessionManager
import com.lightterm.core.session.SessionUiState
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
