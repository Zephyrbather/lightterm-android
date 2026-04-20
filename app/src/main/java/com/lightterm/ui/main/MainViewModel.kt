package com.lightterm.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lightterm.AppContainer
import com.lightterm.data.repository.AppSettings
import com.lightterm.data.repository.AppThemeMode
import com.lightterm.data.repository.AppSettingsRepository
import com.lightterm.data.repository.ServerRepository
import com.lightterm.data.repository.sortedForDisplay
import com.lightterm.data.repository.VirtualKeyRepository
import com.lightterm.domain.model.VirtualKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: ServerRepository,
    private val sessionManager: com.lightterm.core.session.SessionManager,
    private val appSettingsRepository: AppSettingsRepository,
    private val virtualKeyRepository: VirtualKeyRepository,
) : ViewModel() {
    private var openServerCursor = 0

    val uiState: StateFlow<MainUiState> = combine(
        repository.observeServers(),
        sessionManager.sessionTabs,
        sessionManager.activeSessionId,
        appSettingsRepository.settings,
        virtualKeyRepository.keys,
    ) { servers, tabs, activeSessionId, appSettings, shortcuts ->
        val sortedServers = servers.sortedForDisplay(appSettings.serverSortOrder)
        MainUiState(
            availableServers = sortedServers,
            sessionTabs = tabs,
            activeSessionId = activeSessionId,
            shortcuts = shortcuts,
            appSettings = appSettings,
            deviceProfile = sessionManager.deviceProfile,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState(deviceProfile = sessionManager.deviceProfile),
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.seedDefaults()
        }
    }

    fun openNextSession() {
        viewModelScope.launch {
            val servers = repository.listServers().sortedForDisplay(uiState.value.appSettings.serverSortOrder)
            if (servers.isEmpty()) {
                return@launch
            }

            if (openServerCursor >= servers.size) {
                openServerCursor = 0
            }
            val targetServer = servers[openServerCursor]
            openServerCursor = (openServerCursor + 1) % servers.size
            val usedServer = repository.markUsed(targetServer.id) ?: targetServer
            sessionManager.openSession(usedServer)
        }
    }

    fun selectSession(position: Int) {
        val sessionId = uiState.value.sessionTabs.getOrNull(position)?.sessionId ?: return
        sessionManager.selectSession(sessionId)
    }

    fun closeActiveSession() {
        uiState.value.activeSessionId?.let(sessionManager::closeSession)
    }

    fun sendVirtualKey(key: VirtualKey) {
        sessionManager.sendToActiveSession(key.sequence, appendNewLine = false)
    }

    fun decreaseTerminalFont() {
        appSettingsRepository.decreaseTerminalFont()
    }

    fun increaseTerminalFont() {
        appSettingsRepository.increaseTerminalFont()
    }

    fun setLanguage(languageTag: String) {
        appSettingsRepository.updateLanguage(languageTag)
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        appSettingsRepository.updateThemeMode(themeMode)
    }

    fun currentLanguageTag(): String = uiState.value.appSettings.languageTag

    fun currentThemeMode(): AppThemeMode = uiState.value.appSettings.themeMode

    fun addVirtualKey(
        label: String,
        definition: String,
    ): String? = runCatching {
        virtualKeyRepository.addKey(label, definition)
    }.exceptionOrNull()?.message

    fun moveVirtualKey(
        id: String,
        offset: Int,
    ) {
        virtualKeyRepository.moveKey(id, offset)
    }

    fun removeVirtualKey(id: String) {
        virtualKeyRepository.removeKey(id)
    }

    fun resetVirtualKeys() {
        virtualKeyRepository.resetDefaults()
    }

    class Factory(
        private val appContainer: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(
                repository = appContainer.serverRepository,
                sessionManager = appContainer.sessionManager,
                appSettingsRepository = appContainer.appSettingsRepository,
                virtualKeyRepository = appContainer.virtualKeyRepository,
            ) as T
        }
    }
}
