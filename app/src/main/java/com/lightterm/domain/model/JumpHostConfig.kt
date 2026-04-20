package com.lightterm.domain.model

data class JumpHostConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMode: AuthenticationMode,
    val credentialRef: String? = null,
    val keyAlias: String? = null,
    val keyAlgorithm: SshKeyAlgorithm? = null,
) {
    fun targetLabel(): String = "$username@$host:$port"
}
