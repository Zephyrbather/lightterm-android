package com.lightterm.core.session

import com.lightterm.R
import com.lightterm.core.device.DeviceProfile
import com.lightterm.core.terminal.TerminalEmulator
import com.lightterm.domain.model.PowerMode
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.SessionConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SshSession(
    val sessionId: String,
    initialServer: ServerConfig,
    private val transportFactory: SshTransportFactory,
    private val deviceProfile: DeviceProfile,
    initialPowerMode: PowerMode,
    private val messageResolver: (Int, Array<out Any>) -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val terminal = TerminalEmulator(deviceProfile.ringBufferLineLimit)
    @Volatile
    private var server = initialServer
    private val _uiState = MutableStateFlow(
        SessionUiState(
            sessionId = sessionId,
            title = initialServer.alias,
            server = initialServer,
            terminalSnapshot = terminal.snapshot(),
            statusDetail = message(R.string.session_status_waiting),
            powerMode = initialPowerMode,
            keepAliveIntervalSeconds = resolveKeepAliveSeconds(
                powerMode = initialPowerMode,
                appForeground = true,
            ),
        ),
    )
    private var connectedShell: SshTransport.ConnectedShell? = null
    private var keepAliveJob: Job? = null
    private var connectJob: Job? = null
    private var closedByUser = false
    private var appForeground = true
    private var pendingManualRestart: ManualRestart? = null

    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    fun connect() {
        launchReconnect(initialAttempt = 0)
    }

    fun updateServerConfig(updatedServer: ServerConfig) {
        val previousServer = server
        server = updatedServer
        updateState(
            title = updatedServer.alias,
            serverConfig = updatedServer,
            statusDetail = if (uiState.value.connectionState == SessionConnectionState.CONNECTING) {
                message(R.string.session_status_connecting_to, updatedServer.targetLabel())
            } else {
                null
            },
        )

        if (shouldRestartForConfigChange(previousServer, updatedServer) &&
            uiState.value.connectionState != SessionConnectionState.CONNECTED
        ) {
            requestManualRestart(clearTerminal = false)
        }
    }

    fun reconnect() {
        requestManualRestart(clearTerminal = false)
    }

    fun refresh() {
        requestManualRestart(clearTerminal = true)
    }

    fun send(input: String, appendNewLine: Boolean) {
        scope.launch {
            val payload = if (appendNewLine && !input.endsWith("\n") && !input.endsWith("\r")) {
                "$input\r"
            } else {
                input
            }
            connectedShell?.write(payload)
        }
    }

    fun resize(columns: Int, rows: Int) {
        terminal.resize(columns, rows)
        updateState(terminalSnapshot = terminal.snapshot())
        scope.launch {
            connectedShell?.resize(columns, rows)
        }
    }

    fun setPowerMode(powerMode: PowerMode) {
        updateState(
            powerMode = powerMode,
            keepAliveIntervalSeconds = resolveKeepAliveSeconds(powerMode, appForeground),
        )
        restartKeepAliveLoop()
    }

    fun setAppForeground(isForeground: Boolean) {
        appForeground = isForeground
        restartKeepAliveLoop()
        updateState(
            keepAliveIntervalSeconds = resolveKeepAliveSeconds(
                powerMode = uiState.value.powerMode,
                appForeground = isForeground,
            ),
        )
    }

    suspend fun close() {
        closedByUser = true
        pendingManualRestart = null
        connectJob?.cancel()
        keepAliveJob?.cancel()
        connectedShell?.close()
        connectedShell = null
        updateState(
            connectionState = SessionConnectionState.DISCONNECTED,
            statusDetail = message(R.string.session_status_closed),
            inputEnabled = false,
        )
        scope.coroutineContext[Job]?.cancel()
    }

    private fun requestManualRestart(clearTerminal: Boolean) {
        if (closedByUser) {
            return
        }

        val shell = connectedShell
        connectJob?.cancel()
        connectJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        updateState(
            connectionState = SessionConnectionState.DISCONNECTED,
            statusDetail = if (clearTerminal) {
                message(R.string.session_status_refreshing)
            } else {
                message(R.string.session_status_manual_reconnect)
            },
            reconnectAttempt = 0,
            lastLatencyMs = null,
            inputEnabled = false,
        )

        if (shell == null) {
            if (clearTerminal) {
                terminal.clear()
                updateState(terminalSnapshot = terminal.snapshot())
            }
            launchReconnect(initialAttempt = 0)
            return
        }

        pendingManualRestart = ManualRestart(clearTerminal = clearTerminal)
        scope.launch {
            runCatching { shell.close() }
        }
    }

    private fun launchReconnect(initialAttempt: Int) {
        if (closedByUser || connectJob?.isActive == true) {
            return
        }
        connectJob = scope.launch {
            connectWithRetries(initialAttempt)
        }
    }

    private suspend fun connectWithRetries(initialAttempt: Int) {
        var attempt = initialAttempt
        while (!closedByUser) {
            val activeServer = server
            updateState(
                connectionState = if (attempt == 0) {
                    SessionConnectionState.CONNECTING
                } else {
                    SessionConnectionState.RECONNECTING
                },
                statusDetail = if (attempt == 0) {
                    message(R.string.session_status_connecting_to, activeServer.targetLabel())
                } else {
                    message(R.string.session_status_reconnect_attempt, attempt)
                },
                reconnectAttempt = attempt,
                inputEnabled = false,
            )

            try {
                connectedShell = transportFactory.create(activeServer).open(
                    config = activeServer,
                    deviceProfile = deviceProfile,
                    initialColumns = uiState.value.terminalSnapshot.columns,
                    initialRows = uiState.value.terminalSnapshot.rows,
                    scope = scope,
                    listener = object : SshTransport.Listener {
                        override suspend fun onConnected(detail: String) {
                            updateState(
                                connectionState = SessionConnectionState.CONNECTED,
                                statusDetail = detail,
                                reconnectAttempt = 0,
                                inputEnabled = true,
                            )
                            restartKeepAliveLoop()
                        }

                        override suspend fun onOutput(text: String) {
                            terminal.appendText(text)
                            updateState(terminalSnapshot = terminal.snapshot())
                        }

                        override suspend fun onDisconnected(detail: String) {
                            connectedShell = null
                            keepAliveJob?.cancel()
                            updateState(
                                connectionState = SessionConnectionState.DISCONNECTED,
                                statusDetail = detail,
                                lastLatencyMs = null,
                                inputEnabled = false,
                            )
                            val manualRestart = pendingManualRestart
                            if (manualRestart != null) {
                                pendingManualRestart = null
                                if (manualRestart.clearTerminal) {
                                    terminal.clear()
                                    updateState(terminalSnapshot = terminal.snapshot())
                                }
                                launchReconnect(initialAttempt = 0)
                            } else if (!closedByUser) {
                                launchReconnect(initialAttempt = 1)
                            }
                        }

                        override suspend fun onLatencyMeasured(latencyMs: Long) {
                            updateState(lastLatencyMs = latencyMs)
                        }
                    },
                )
                return
            } catch (throwable: Throwable) {
                connectedShell = null
                keepAliveJob?.cancel()
                attempt += 1

                if (attempt > activeServer.reconnectAttempts) {
                    updateState(
                        connectionState = SessionConnectionState.ERROR,
                        statusDetail = throwable.message ?: message(R.string.session_status_connection_failed),
                        reconnectAttempt = attempt - 1,
                        inputEnabled = false,
                    )
                    return
                }

                updateState(
                    connectionState = SessionConnectionState.RECONNECTING,
                    statusDetail = message(
                        R.string.session_status_retry_after,
                        activeServer.reconnectIntervalSeconds,
                    ),
                    reconnectAttempt = attempt,
                    inputEnabled = false,
                )
                delay(activeServer.reconnectIntervalSeconds * 1000L)
            }
        }
    }

    private fun restartKeepAliveLoop() {
        keepAliveJob?.cancel()
        val keepAliveSeconds = resolveKeepAliveSeconds(uiState.value.powerMode, appForeground)
        if (uiState.value.connectionState != SessionConnectionState.CONNECTED) {
            return
        }

        keepAliveJob = scope.launch {
            while (true) {
                delay(keepAliveSeconds * 1000L)
                connectedShell?.keepAlive()
            }
        }
    }

    private fun resolveKeepAliveSeconds(powerMode: PowerMode, appForeground: Boolean): Long {
        val base = if (appForeground) {
            deviceProfile.keepAliveForegroundSeconds
        } else {
            deviceProfile.keepAliveBackgroundSeconds
        }
        return if (powerMode == PowerMode.BATTERY_SAVER) {
            (base + 10).coerceAtMost(90)
        } else {
            base
        }
    }

    private fun updateState(
        title: String? = null,
        serverConfig: ServerConfig? = null,
        connectionState: SessionConnectionState? = null,
        terminalSnapshot: com.lightterm.domain.model.TerminalSnapshot? = null,
        statusDetail: String? = null,
        powerMode: PowerMode? = null,
        reconnectAttempt: Int? = null,
        keepAliveIntervalSeconds: Long? = null,
        lastLatencyMs: Long? = null,
        inputEnabled: Boolean? = null,
    ) {
        _uiState.value = _uiState.value.copy(
            title = title ?: _uiState.value.title,
            server = serverConfig ?: _uiState.value.server,
            connectionState = connectionState ?: _uiState.value.connectionState,
            terminalSnapshot = terminalSnapshot ?: _uiState.value.terminalSnapshot,
            statusDetail = statusDetail ?: _uiState.value.statusDetail,
            powerMode = powerMode ?: _uiState.value.powerMode,
            reconnectAttempt = reconnectAttempt ?: _uiState.value.reconnectAttempt,
            keepAliveIntervalSeconds = keepAliveIntervalSeconds ?: _uiState.value.keepAliveIntervalSeconds,
            lastLatencyMs = lastLatencyMs ?: _uiState.value.lastLatencyMs,
            inputEnabled = inputEnabled ?: _uiState.value.inputEnabled,
        )
    }

    private data class ManualRestart(
        val clearTerminal: Boolean,
    )

    private fun shouldRestartForConfigChange(
        previousServer: ServerConfig,
        updatedServer: ServerConfig,
    ): Boolean {
        return previousServer.host != updatedServer.host ||
            previousServer.port != updatedServer.port ||
            previousServer.username != updatedServer.username ||
            previousServer.authMode != updatedServer.authMode ||
            previousServer.credentialRef != updatedServer.credentialRef ||
            previousServer.keyAlias != updatedServer.keyAlias ||
            previousServer.keyAlgorithm != updatedServer.keyAlgorithm ||
            previousServer.jumpHosts != updatedServer.jumpHosts ||
            previousServer.demoMode != updatedServer.demoMode
    }

    private fun message(
        resId: Int,
        vararg args: Any,
    ): String = messageResolver(resId, args)
}
