package com.lightterm.ui.serverconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lightterm.AppContainer
import com.lightterm.R
import com.lightterm.core.network.ServerConnectivityTester
import com.lightterm.core.session.SessionManager
import com.lightterm.data.repository.AppSettingsRepository
import com.lightterm.data.repository.JumpHostDraft
import com.lightterm.data.repository.ServerRepository
import com.lightterm.data.repository.ServerSortOrder
import com.lightterm.data.repository.sortedForDisplay
import com.lightterm.domain.model.AuthenticationMode
import com.lightterm.domain.model.JumpHostConfig
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.SshKeyAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ServerConfigViewModel(
    private val repository: ServerRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val sessionManager: SessionManager,
    private val connectivityTester: ServerConnectivityTester,
    private val messageResolver: (Int, Array<out Any>) -> String,
) : ViewModel() {
    private val formState = MutableStateFlow(ServerConfigUiState())
    private val _events = MutableSharedFlow<ServerConfigEvent>()

    val events = _events.asSharedFlow()

    val uiState: StateFlow<ServerConfigUiState> = combine(
        repository.observeServers(),
        appSettingsRepository.settings,
        formState,
    ) { servers, appSettings, form ->
        form.copy(
            servers = servers.sortedForDisplay(appSettings.serverSortOrder),
            serverSortOrder = appSettings.serverSortOrder,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ServerConfigUiState(),
    )

    fun updateAlias(value: String) = formState.update { it.copy(alias = value) }

    fun updateUseJumpHosts(enabled: Boolean) = formState.update { current ->
        current.resetConnectivityResult().copy(
            jumpHosts = if (enabled) {
                current.jumpHosts.ifEmpty { listOf(defaultJumpHostUiState()) }
            } else {
                emptyList()
            },
        )
    }

    fun addJumpHost() = formState.update { current ->
        current.resetConnectivityResult().copy(
            jumpHosts = current.jumpHosts + defaultJumpHostUiState(),
        )
    }

    fun removeJumpHost(index: Int) = formState.update { current ->
        if (index !in current.jumpHosts.indices) {
            current
        } else {
            current.resetConnectivityResult().copy(
                jumpHosts = current.jumpHosts.toMutableList().apply { removeAt(index) },
            )
        }
    }

    fun updateJumpHost(index: Int, value: String) = updateJumpHop(index, resetConnectivity = true) {
        it.copy(host = value)
    }

    fun updateJumpPort(index: Int, value: String) = updateJumpHop(index, resetConnectivity = true) {
        it.copy(port = value)
    }

    fun updateJumpUsername(index: Int, value: String) = updateJumpHop(index) {
        it.copy(username = value)
    }

    fun updateJumpPassword(index: Int, value: String) = updateJumpHop(index) {
        it.copy(password = value)
    }

    fun updatePreserveExistingJumpPassword(index: Int, enabled: Boolean) = updateJumpHop(index) { hop ->
        hop.copy(
            preserveExistingPassword = enabled,
            password = if (enabled) "" else hop.password,
        )
    }

    fun updateJumpKeyAlias(index: Int, value: String) = updateJumpHop(index) {
        it.copy(keyAlias = value)
    }

    fun updateJumpKeyAlgorithm(index: Int, algorithm: SshKeyAlgorithm) = updateJumpHop(index) {
        it.copy(keyAlgorithm = algorithm)
    }

    fun updateJumpAuthMode(index: Int, mode: AuthenticationMode) = updateJumpHop(index) { hop ->
        hop.copy(
            authMode = mode,
            password = if (mode == AuthenticationMode.PASSWORD) hop.password else "",
            preserveExistingPassword = if (mode == AuthenticationMode.PASSWORD) {
                hop.existingCredentialRef != null
            } else {
                false
            },
        )
    }

    fun updateHost(value: String) = formState.update { it.resetConnectivityResult().copy(host = value) }
    fun updatePort(value: String) = formState.update { it.resetConnectivityResult().copy(port = value) }
    fun updateUsername(value: String) = formState.update { it.copy(username = value) }
    fun updatePassword(value: String) = formState.update { it.copy(password = value) }

    fun updatePreserveExistingPassword(enabled: Boolean) {
        formState.update { current ->
            current.copy(
                preserveExistingPassword = enabled,
                password = if (enabled) "" else current.password,
            )
        }
    }

    fun updateKeyAlias(value: String) = formState.update { it.copy(keyAlias = value) }
    fun updateReconnectAttempts(value: String) = formState.update { it.copy(reconnectAttempts = value) }
    fun updateReconnectIntervalSeconds(value: String) = formState.update { it.copy(reconnectIntervalSeconds = value) }
    fun updateDemoMode(enabled: Boolean) = formState.update { it.resetConnectivityResult().copy(demoMode = enabled) }

    fun updateAuthMode(mode: AuthenticationMode) {
        formState.update { current ->
            current.copy(
                authMode = mode,
                password = if (mode == AuthenticationMode.PASSWORD) current.password else "",
                preserveExistingPassword = if (mode == AuthenticationMode.PASSWORD) {
                    current.existingCredentialRef != null
                } else {
                    false
                },
            )
        }
    }

    fun updateKeyAlgorithm(algorithm: SshKeyAlgorithm) {
        formState.update { it.copy(keyAlgorithm = algorithm) }
    }

    fun startNew() {
        formState.value = ServerConfigUiState()
    }

    fun updateServerSortOrder(serverSortOrder: ServerSortOrder) {
        appSettingsRepository.updateServerSortOrder(serverSortOrder)
    }

    fun editServer(serverId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val server = repository.getServer(serverId) ?: return@launch
            formState.value = server.toUiState()
            _events.emit(ServerConfigEvent.ScrollToForm)
        }
    }

    fun deleteServer(serverId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = repository.deleteServer(serverId) ?: return@launch
            sessionManager.closeServerSession(serverId)
            if (formState.value.editingServerId == serverId) {
                formState.value = ServerConfigUiState()
            }
            _events.emit(ServerConfigEvent.ShowMessage(message(R.string.server_message_deleted, deleted.alias)))
            _events.emit(ServerConfigEvent.ScrollToSavedList)
        }
    }

    fun save() {
        val snapshot = formState.value
        val validationError = validate(snapshot)
        if (validationError != null) {
            viewModelScope.launch {
                _events.emit(ServerConfigEvent.ShowMessage(validationError))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            formState.update { it.copy(isSaving = true) }
            val current = formState.value

            runCatching {
                repository.saveServer(
                    serverId = current.editingServerId,
                    alias = current.alias,
                    host = current.host,
                    port = current.port.toInt(),
                    username = current.username,
                    authMode = current.authMode,
                    password = if (current.authMode == AuthenticationMode.PASSWORD && current.preserveExistingPassword) {
                        null
                    } else {
                        current.password
                    },
                    existingCredentialRef = current.existingCredentialRef,
                    keyAlias = current.keyAlias,
                    keyAlgorithm = current.keyAlgorithm,
                    jumpHosts = current.jumpHosts.map { hop ->
                        JumpHostDraft(
                            host = hop.host,
                            port = hop.port.toIntOrNull(),
                            username = hop.username,
                            authMode = hop.authMode,
                            password = if (hop.authMode == AuthenticationMode.PASSWORD && hop.preserveExistingPassword) {
                                null
                            } else {
                                hop.password
                            },
                            existingCredentialRef = hop.existingCredentialRef,
                            keyAlias = hop.keyAlias,
                            keyAlgorithm = hop.keyAlgorithm,
                        )
                    },
                    demoMode = current.demoMode,
                    reconnectAttempts = current.reconnectAttempts.toInt(),
                    reconnectIntervalSeconds = current.reconnectIntervalSeconds.toInt(),
                )
            }.onSuccess { saved ->
                formState.value = saved.toUiState()
                sessionManager.updateServerConfig(saved)
                _events.emit(ServerConfigEvent.ShowMessage(message(R.string.server_message_saved, saved.alias)))
                _events.emit(ServerConfigEvent.ScrollToSavedList)
            }.onFailure { throwable ->
                formState.update { it.copy(isSaving = false) }
                _events.emit(ServerConfigEvent.ShowMessage(throwable.message ?: message(R.string.server_message_save_failed)))
            }
        }
    }

    fun testConnectivity() {
        val snapshot = formState.value
        if (snapshot.demoMode) {
            viewModelScope.launch {
                _events.emit(ServerConfigEvent.ShowMessage(message(R.string.server_message_preview_skip_connectivity)))
            }
            return
        }

        val firstJumpHost = snapshot.jumpHosts.firstOrNull()
        val targetHost = firstJumpHost?.host ?: snapshot.host
        if (targetHost.isBlank()) {
            viewModelScope.launch {
                _events.emit(
                    ServerConfigEvent.ShowMessage(
                        message(
                            if (firstJumpHost != null) {
                                R.string.server_message_enter_jump_host_for_test
                            } else {
                                R.string.server_message_enter_host_for_test
                            },
                        ),
                    ),
                )
            }
            return
        }

        val port = firstJumpHost?.port?.toIntOrNull() ?: snapshot.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            viewModelScope.launch {
                _events.emit(ServerConfigEvent.ShowMessage(message(R.string.server_message_enter_valid_port_for_test)))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            formState.update { it.copy(isTestingConnectivity = true, connectivitySummary = "") }
            runCatching {
                val summary = connectivityTester.test(targetHost, port).summary(
                    port = port,
                    messageResolver = { resId, args -> message(resId, *args) },
                )
                if (snapshot.useJumpHosts) {
                    val jumpNote = if (snapshot.jumpHosts.size > 1) {
                        message(
                            R.string.server_connectivity_jump_note_multi,
                            snapshot.host,
                            snapshot.port,
                            snapshot.jumpHosts.size,
                        )
                    } else {
                        message(R.string.server_connectivity_jump_note, snapshot.host, snapshot.port)
                    }
                    val limitationNote = message(
                        R.string.server_connectivity_limit_note_with_jump,
                        targetHost,
                        port,
                        snapshot.host,
                        snapshot.port,
                    )
                    message(
                        R.string.server_connectivity_summary_with_jump,
                        summary,
                        "$jumpNote · $limitationNote",
                    )
                } else {
                    val limitationNote = message(
                        R.string.server_connectivity_limit_note_direct,
                        targetHost,
                        port,
                    )
                    "$summary · $limitationNote"
                }
            }.onSuccess { summary ->
                formState.update {
                    it.copy(
                        isTestingConnectivity = false,
                        connectivitySummary = summary,
                    )
                }
            }.onFailure { throwable ->
                formState.update {
                    it.copy(
                        isTestingConnectivity = false,
                        connectivitySummary = throwable.message ?: message(R.string.server_message_connectivity_failed),
                    )
                }
            }
        }
    }

    fun openServer(serverId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val server = repository.markUsed(serverId) ?: repository.getServer(serverId) ?: return@launch
            sessionManager.openSession(server)
            _events.emit(ServerConfigEvent.ShowMessage(message(R.string.server_message_opened, server.alias)))
            _events.emit(ServerConfigEvent.NavigateBack)
        }
    }

    private fun updateJumpHop(
        index: Int,
        resetConnectivity: Boolean = false,
        transform: (JumpHostUiState) -> JumpHostUiState,
    ) {
        formState.update { current ->
            if (index !in current.jumpHosts.indices) {
                current
            } else {
                val updatedHops = current.jumpHosts.mapIndexed { hopIndex, hop ->
                    if (hopIndex == index) transform(hop) else hop
                }
                if (resetConnectivity) {
                    current.resetConnectivityResult().copy(jumpHosts = updatedHops)
                } else {
                    current.copy(jumpHosts = updatedHops)
                }
            }
        }
    }

    private fun validate(state: ServerConfigUiState): String? {
        if (state.alias.isBlank()) {
            return message(R.string.server_validation_alias_required)
        }
        if (state.host.isBlank()) {
            return message(R.string.server_validation_host_required)
        }
        if (state.username.isBlank()) {
            return message(R.string.server_validation_username_required)
        }

        val port = state.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            return message(R.string.server_validation_port_range)
        }

        val reconnectAttempts = state.reconnectAttempts.toIntOrNull()
        if (reconnectAttempts == null || reconnectAttempts !in 0..20) {
            return message(R.string.server_validation_reconnect_attempts_range)
        }

        val reconnectIntervalSeconds = state.reconnectIntervalSeconds.toIntOrNull()
        if (reconnectIntervalSeconds == null || reconnectIntervalSeconds !in 1..300) {
            return message(R.string.server_validation_reconnect_interval_range)
        }

        if (state.authMode == AuthenticationMode.PUBLIC_KEY && state.keyAlias.isBlank()) {
            return message(R.string.server_validation_key_alias_required)
        }

        state.jumpHosts.forEachIndexed { index, hop ->
            val displayIndex = index + 1
            if (hop.host.isBlank()) {
                return message(R.string.server_validation_jump_host_required_at, displayIndex)
            }
            if (hop.username.isBlank()) {
                return message(R.string.server_validation_jump_username_required_at, displayIndex)
            }
            val jumpPort = hop.port.toIntOrNull()
            if (jumpPort == null || jumpPort !in 1..65535) {
                return message(R.string.server_validation_jump_port_range_at, displayIndex)
            }
            if (hop.authMode == AuthenticationMode.PUBLIC_KEY && hop.keyAlias.isBlank()) {
                return message(R.string.server_validation_jump_key_alias_required_at, displayIndex)
            }
        }

        return null
    }

    private fun message(
        resId: Int,
        vararg args: Any,
    ): String = messageResolver(resId, args)

    class Factory(
        private val appContainer: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ServerConfigViewModel(
                repository = appContainer.serverRepository,
                appSettingsRepository = appContainer.appSettingsRepository,
                sessionManager = appContainer.sessionManager,
                connectivityTester = appContainer.serverConnectivityTester,
                messageResolver = { resId, args ->
                    appContainer.appContext.getString(resId, *args)
                },
            ) as T
        }
    }
}

sealed interface ServerConfigEvent {
    data class ShowMessage(val value: String) : ServerConfigEvent
    data object ScrollToForm : ServerConfigEvent
    data object ScrollToSavedList : ServerConfigEvent
    data object NavigateBack : ServerConfigEvent
}

private fun ServerConfig.toUiState(): ServerConfigUiState = ServerConfigUiState(
    editingServerId = id.takeIf { it > 0L },
    alias = alias,
    jumpHosts = jumpHosts.map(JumpHostConfig::toUiState),
    host = host,
    port = port.toString(),
    username = username,
    authMode = authMode,
    existingCredentialRef = credentialRef,
    preserveExistingPassword = authMode == AuthenticationMode.PASSWORD && credentialRef != null,
    keyAlias = keyAlias.orEmpty(),
    keyAlgorithm = keyAlgorithm ?: SshKeyAlgorithm.ECDSA,
    demoMode = demoMode,
    reconnectAttempts = reconnectAttempts.toString(),
    reconnectIntervalSeconds = reconnectIntervalSeconds.toString(),
    isSaving = false,
)

private fun JumpHostConfig.toUiState(): JumpHostUiState = JumpHostUiState(
    host = host,
    port = port.toString(),
    username = username,
    authMode = authMode,
    existingCredentialRef = credentialRef,
    preserveExistingPassword = authMode == AuthenticationMode.PASSWORD && credentialRef != null,
    keyAlias = keyAlias.orEmpty(),
    keyAlgorithm = keyAlgorithm ?: SshKeyAlgorithm.ECDSA,
)

private fun ServerConfigUiState.resetConnectivityResult(): ServerConfigUiState = copy(
    isTestingConnectivity = false,
    connectivitySummary = "",
)

private fun defaultJumpHostUiState(): JumpHostUiState = JumpHostUiState()
