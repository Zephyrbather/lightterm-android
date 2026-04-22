package com.lightterm.ui.main

import com.lightterm.core.device.DeviceProfile
import com.lightterm.core.session.SessionTabUiModel
import com.lightterm.data.repository.AppSettings
import com.lightterm.data.repository.AppThemeMode
import com.lightterm.data.repository.ServerSortOrder
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.VirtualKey

data class MainUiState(
    val availableServers: List<ServerConfig> = emptyList(),
    val recentServers: List<ServerConfig> = emptyList(),
    val sessionTabs: List<SessionTabUiModel> = emptyList(),
    val activeSessionId: String? = null,
    val shortcuts: List<VirtualKey> = VirtualKey.defaults(),
    val appSettings: AppSettings = AppSettings(
        terminalFontSizeSp = 12f,
        languageTag = AppSettings.LANGUAGE_ZH,
        themeMode = AppThemeMode.PURE_BLACK,
        serverSortOrder = ServerSortOrder.RECENTLY_USED,
    ),
    val deviceProfile: DeviceProfile = DeviceProfile.generic(),
)
