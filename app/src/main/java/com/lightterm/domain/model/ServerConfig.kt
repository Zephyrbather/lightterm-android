package com.lightterm.domain.model

data class ServerConfig(
    val id: Long = 0,
    val alias: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMode: AuthenticationMode,
    val credentialRef: String? = null,
    val keyAlias: String? = null,
    val keyAlgorithm: SshKeyAlgorithm? = null,
    val jumpHosts: List<JumpHostConfig> = emptyList(),
    val demoMode: Boolean = false,
    val reconnectAttempts: Int = 3,
    val reconnectIntervalSeconds: Int = 8,
    val lastUsedAtEpochMillis: Long = 0L,
) {
    val jumpHost: String?
        get() = jumpHosts.firstOrNull()?.host

    val jumpPort: Int?
        get() = jumpHosts.firstOrNull()?.port

    val jumpUsername: String?
        get() = jumpHosts.firstOrNull()?.username

    val jumpAuthMode: AuthenticationMode?
        get() = jumpHosts.firstOrNull()?.authMode

    val jumpCredentialRef: String?
        get() = jumpHosts.firstOrNull()?.credentialRef

    val jumpKeyAlias: String?
        get() = jumpHosts.firstOrNull()?.keyAlias

    val jumpKeyAlgorithm: SshKeyAlgorithm?
        get() = jumpHosts.firstOrNull()?.keyAlgorithm

    fun targetLabel(): String = "$username@$host:$port"

    fun jumpTargetLabel(): String? = jumpHosts.firstOrNull()?.targetLabel()

    fun routeLabel(): String = (jumpHosts.map { it.targetLabel() } + targetLabel()).joinToString(" -> ")

    fun hasJumpHost(): Boolean = jumpHosts.isNotEmpty()

    fun allCredentialRefs(): Set<String> = buildSet {
        credentialRef?.takeIf { it.isNotBlank() }?.let(::add)
        jumpHosts.mapNotNullTo(this) { it.credentialRef?.takeIf(String::isNotBlank) }
    }
}
