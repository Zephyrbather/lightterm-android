package com.lightterm.ui.serverconfig

import com.lightterm.domain.model.AuthenticationMode
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.SshKeyAlgorithm
import com.lightterm.data.repository.ServerSortOrder

data class ServerConfigUiState(
    val servers: List<ServerConfig> = emptyList(),
    val serverSortOrder: ServerSortOrder = ServerSortOrder.RECENTLY_USED,
    val editingServerId: Long? = null,
    val alias: String = "",
    val jumpHosts: List<JumpHostUiState> = emptyList(),
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authMode: AuthenticationMode = AuthenticationMode.PASSWORD,
    val password: String = "",
    val existingCredentialRef: String? = null,
    val preserveExistingPassword: Boolean = false,
    val keyAlias: String = "",
    val keyAlgorithm: SshKeyAlgorithm = SshKeyAlgorithm.ECDSA,
    val demoMode: Boolean = false,
    val reconnectAttempts: String = "3",
    val reconnectIntervalSeconds: String = "8",
    val isTestingConnectivity: Boolean = false,
    val connectivitySummary: String = "",
    val isSaving: Boolean = false,
) {
    val isEditing: Boolean
        get() = editingServerId != null

    val useJumpHosts: Boolean
        get() = jumpHosts.isNotEmpty()

    val isPasswordMode: Boolean
        get() = authMode == AuthenticationMode.PASSWORD

    val showPreservePasswordToggle: Boolean
        get() = isPasswordMode && existingCredentialRef != null

    val shouldEditPassword: Boolean
        get() = isPasswordMode && !preserveExistingPassword

    val hasConnectivitySummary: Boolean
        get() = connectivitySummary.isNotBlank()
}

data class JumpHostUiState(
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authMode: AuthenticationMode = AuthenticationMode.PASSWORD,
    val password: String = "",
    val existingCredentialRef: String? = null,
    val preserveExistingPassword: Boolean = false,
    val keyAlias: String = "",
    val keyAlgorithm: SshKeyAlgorithm = SshKeyAlgorithm.ECDSA,
) {
    val isPasswordMode: Boolean
        get() = authMode == AuthenticationMode.PASSWORD

    val showPreservePasswordToggle: Boolean
        get() = isPasswordMode && existingCredentialRef != null

    val shouldEditPassword: Boolean
        get() = isPasswordMode && !preserveExistingPassword
}
