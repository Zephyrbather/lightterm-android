package com.lightterm.core.session

import com.lightterm.R
import com.lightterm.core.device.DeviceProfile
import com.lightterm.domain.model.PowerMode
import com.lightterm.domain.model.ServerConfig
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionManager(
    private val transportFactory: SshTransportFactory,
    val deviceProfile: DeviceProfile,
    private val messageResolver: (Int, Array<out Any>) -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = linkedMapOf<String, SshSession>()
    private val sessionCollectors = mutableMapOf<String, Job>()
    private val _sessionTabs = MutableStateFlow<List<SessionTabUiModel>>(emptyList())
    private val _activeSessionId = MutableStateFlow<String?>(null)
    private val _powerMode = MutableStateFlow(PowerMode.BALANCED)
    private var appForeground = true

    val sessionTabs: StateFlow<List<SessionTabUiModel>> = _sessionTabs.asStateFlow()
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()
    val powerMode: StateFlow<PowerMode> = _powerMode.asStateFlow()

    fun openSession(server: ServerConfig): String {
        val sessionId = "server-${server.id}"
        val existing = sessions[sessionId]
        if (existing != null) {
            existing.updateServerConfig(server)
            selectSession(sessionId)
            if (existing.uiState.value.connectionState == com.lightterm.domain.model.SessionConnectionState.ERROR ||
                existing.uiState.value.connectionState == com.lightterm.domain.model.SessionConnectionState.DISCONNECTED
            ) {
                existing.reconnect()
            }
            return sessionId
        }

        val session = SshSession(
            sessionId = sessionId,
            initialServer = server,
            transportFactory = transportFactory,
            deviceProfile = deviceProfile,
            initialPowerMode = _powerMode.value,
            messageResolver = messageResolver,
        )

        sessions[sessionId] = session
        session.setAppForeground(appForeground)
        sessionCollectors[sessionId] = scope.launch {
            session.uiState.collect {
                publishTabs()
            }
        }
        publishTabs()
        selectSession(sessionId)
        session.connect()
        return sessionId
    }

    fun observeSession(sessionId: String): StateFlow<SessionUiState> {
        return sessions[sessionId]?.uiState ?: MutableStateFlow(
            SessionUiState.closedPlaceholder(
                sessionId = sessionId,
                title = message(R.string.session_placeholder_title),
                statusDetail = message(R.string.session_status_closed),
            ),
        )
    }

    fun selectSession(sessionId: String) {
        if (sessions.containsKey(sessionId)) {
            _activeSessionId.value = sessionId
        }
    }

    fun sendToActiveSession(input: String, appendNewLine: Boolean) {
        val active = _activeSessionId.value ?: return
        sessions[active]?.send(input, appendNewLine)
    }

    fun sendToSession(sessionId: String, input: String, appendNewLine: Boolean) {
        sessions[sessionId]?.send(input, appendNewLine)
    }

    fun resizeSession(sessionId: String, columns: Int, rows: Int) {
        sessions[sessionId]?.resize(columns, rows)
    }

    fun togglePowerMode() {
        val next = if (_powerMode.value == PowerMode.BALANCED) {
            PowerMode.BATTERY_SAVER
        } else {
            PowerMode.BALANCED
        }
        setPowerMode(next)
    }

    fun setPowerMode(powerMode: PowerMode) {
        _powerMode.value = powerMode
        sessions.values.forEach { it.setPowerMode(powerMode) }
        publishTabs()
    }

    fun setAppForeground(isForeground: Boolean) {
        appForeground = isForeground
        sessions.values.forEach { it.setAppForeground(isForeground) }
    }

    fun closeSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        sessionCollectors.remove(sessionId)?.cancel()
        scope.launch {
            session.close()
        }
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = sessions.keys.lastOrNull()
        }
        publishTabs()
    }

    fun closeServerSession(serverId: Long) {
        closeSession("server-$serverId")
    }

    fun reconnectSession(sessionId: String) {
        sessions[sessionId]?.reconnect()
    }

    fun refreshSession(sessionId: String) {
        sessions[sessionId]?.refresh()
    }

    suspend fun listRemoteDirectory(
        sessionId: String,
        path: String? = null,
    ): RemoteDirectoryListing {
        return requireSession(sessionId).listRemoteDirectory(path)
    }

    suspend fun readRemoteTextFile(
        sessionId: String,
        path: String,
    ): RemoteTextFile {
        return requireSession(sessionId).readRemoteTextFile(path)
    }

    suspend fun writeRemoteTextFile(
        sessionId: String,
        path: String,
        content: String,
    ) {
        requireSession(sessionId).writeRemoteTextFile(path, content)
    }

    suspend fun uploadRemoteFile(
        sessionId: String,
        remoteDirectoryPath: String,
        remoteFileName: String,
        source: InputStream,
    ) {
        requireSession(sessionId).uploadRemoteFile(remoteDirectoryPath, remoteFileName, source)
    }

    suspend fun downloadRemoteFile(
        sessionId: String,
        remoteFilePath: String,
        sink: OutputStream,
    ) {
        requireSession(sessionId).downloadRemoteFile(remoteFilePath, sink)
    }

    fun hasSession(serverId: Long): Boolean = sessions.containsKey("server-$serverId")

    fun updateServerConfig(server: ServerConfig) {
        val session = sessions["server-${server.id}"] ?: return
        session.updateServerConfig(server)
        publishTabs()
    }

    private fun publishTabs() {
        _sessionTabs.value = sessions.values.map { session ->
            val state = session.uiState.value
            SessionTabUiModel(
                sessionId = session.sessionId,
                title = state.title,
                subtitle = state.server.targetLabel(),
                state = state.connectionState,
            )
        }
    }

    private fun message(
        resId: Int,
        vararg args: Any,
    ): String = messageResolver(resId, args)

    private fun requireSession(sessionId: String): SshSession {
        return sessions[sessionId]
            ?: throw IllegalStateException(message(R.string.session_status_closed))
    }
}
