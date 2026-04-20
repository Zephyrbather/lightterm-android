package com.lightterm.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lightterm.domain.model.AuthenticationMode
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.SshKeyAlgorithm

@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val alias: String,
    val host: String,
    val port: Int,
    val username: String,
    val authMode: String,
    val credentialRef: String?,
    val keyAlias: String?,
    val keyAlgorithm: String?,
    val jumpHost: String?,
    val jumpPort: Int?,
    val jumpUsername: String?,
    val jumpAuthMode: String?,
    val jumpCredentialRef: String?,
    val jumpKeyAlias: String?,
    val jumpKeyAlgorithm: String?,
    val jumpChainJson: String?,
    val demoMode: Boolean,
    val reconnectAttempts: Int,
    val reconnectIntervalSeconds: Int,
    val lastUsedAtEpochMillis: Long,
)

fun ServerConfigEntity.toDomain(): ServerConfig = ServerConfig(
    id = id,
    alias = alias,
    host = host,
    port = port,
    username = username,
    authMode = AuthenticationMode.valueOf(authMode),
    credentialRef = credentialRef,
    keyAlias = keyAlias,
    keyAlgorithm = keyAlgorithm?.let(SshKeyAlgorithm::valueOf),
    jumpHosts = decodeJumpHosts(
        jumpChainJson = jumpChainJson,
        legacyJumpHost = jumpHost,
        legacyJumpPort = jumpPort,
        legacyJumpUsername = jumpUsername,
        legacyJumpAuthMode = jumpAuthMode,
        legacyJumpCredentialRef = jumpCredentialRef,
        legacyJumpKeyAlias = jumpKeyAlias,
        legacyJumpKeyAlgorithm = jumpKeyAlgorithm,
    ),
    demoMode = demoMode,
    reconnectAttempts = reconnectAttempts,
    reconnectIntervalSeconds = reconnectIntervalSeconds,
    lastUsedAtEpochMillis = lastUsedAtEpochMillis,
)

fun ServerConfig.toEntity(): ServerConfigEntity {
    val firstJumpHost = jumpHosts.firstOrNull()
    return ServerConfigEntity(
        id = id,
        alias = alias,
        host = host,
        port = port,
        username = username,
        authMode = authMode.name,
        credentialRef = credentialRef,
        keyAlias = keyAlias,
        keyAlgorithm = keyAlgorithm?.name,
        jumpHost = firstJumpHost?.host,
        jumpPort = firstJumpHost?.port,
        jumpUsername = firstJumpHost?.username,
        jumpAuthMode = firstJumpHost?.authMode?.name,
        jumpCredentialRef = firstJumpHost?.credentialRef,
        jumpKeyAlias = firstJumpHost?.keyAlias,
        jumpKeyAlgorithm = firstJumpHost?.keyAlgorithm?.name,
        jumpChainJson = encodeJumpHosts(jumpHosts),
        demoMode = demoMode,
        reconnectAttempts = reconnectAttempts,
        reconnectIntervalSeconds = reconnectIntervalSeconds,
        lastUsedAtEpochMillis = lastUsedAtEpochMillis,
    )
}
