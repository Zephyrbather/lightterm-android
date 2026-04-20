package com.lightterm.core.session

import com.lightterm.domain.model.PowerMode
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.SessionConnectionState
import com.lightterm.domain.model.TerminalSnapshot

data class SessionUiState(
    val sessionId: String,
    val title: String,
    val server: ServerConfig,
    val connectionState: SessionConnectionState = SessionConnectionState.DISCONNECTED,
    val terminalSnapshot: TerminalSnapshot = TerminalSnapshot.EMPTY,
    val statusDetail: String = "",
    val powerMode: PowerMode = PowerMode.BALANCED,
    val reconnectAttempt: Int = 0,
    val keepAliveIntervalSeconds: Long = 20,
    val lastLatencyMs: Long? = null,
    val inputEnabled: Boolean = false,
) {
    companion object {
        fun closedPlaceholder(
            sessionId: String,
            title: String,
            statusDetail: String,
        ): SessionUiState = SessionUiState(
            sessionId = sessionId,
            title = title,
            server = ServerConfig(
                alias = title,
                host = "127.0.0.1",
                username = "lightterm",
                authMode = com.lightterm.domain.model.AuthenticationMode.PASSWORD,
            ),
            connectionState = SessionConnectionState.DISCONNECTED,
            statusDetail = statusDetail,
        )
    }
}

data class SessionTabUiModel(
    val sessionId: String,
    val title: String,
    val subtitle: String,
    val state: SessionConnectionState,
)
