package com.lightterm.data.repository

import com.lightterm.data.local.ServerConfigDao
import com.lightterm.data.model.toDomain
import com.lightterm.data.model.toEntity
import com.lightterm.data.security.SecureCredentialStore
import com.lightterm.data.security.SshKeyManager
import com.lightterm.domain.model.AuthenticationMode
import com.lightterm.domain.model.JumpHostConfig
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.SshKeyAlgorithm
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class JumpHostDraft(
    val host: String,
    val port: Int?,
    val username: String,
    val authMode: AuthenticationMode,
    val password: String?,
    val existingCredentialRef: String?,
    val keyAlias: String?,
    val keyAlgorithm: SshKeyAlgorithm?,
)

class ServerRepository(
    private val dao: ServerConfigDao,
    private val credentialStore: SecureCredentialStore,
    private val sshKeyManager: SshKeyManager,
) {
    fun observeServers(): Flow<List<ServerConfig>> = dao.observeAll().map { items ->
        items.map { it.toDomain() }
    }

    suspend fun listServers(): List<ServerConfig> = dao.listAll().map { it.toDomain() }

    suspend fun getServer(id: Long): ServerConfig? = dao.getById(id)?.toDomain()

    suspend fun upsert(serverConfig: ServerConfig) {
        dao.upsert(serverConfig.toEntity())
    }

    suspend fun markUsed(serverId: Long, usedAtEpochMillis: Long = System.currentTimeMillis()): ServerConfig? {
        dao.updateLastUsedAt(serverId, usedAtEpochMillis)
        return dao.getById(serverId)?.toDomain()
    }

    suspend fun deleteServer(serverId: Long): ServerConfig? {
        val existing = dao.getById(serverId)?.toDomain() ?: return null
        dao.deleteById(serverId)
        cleanupCredentials(previousRefs = existing.allCredentialRefs(), currentRefs = emptySet())
        return existing
    }

    suspend fun saveServer(
        serverId: Long?,
        alias: String,
        host: String,
        port: Int,
        username: String,
        authMode: AuthenticationMode,
        password: String?,
        existingCredentialRef: String?,
        keyAlias: String?,
        keyAlgorithm: SshKeyAlgorithm?,
        jumpHosts: List<JumpHostDraft>,
        demoMode: Boolean,
        reconnectAttempts: Int,
        reconnectIntervalSeconds: Int,
    ): ServerConfig {
        val existing = serverId?.takeIf { it > 0L }?.let { dao.getById(it)?.toDomain() }
        val trimmedAlias = alias.trim()
        val trimmedHost = host.trim()
        val trimmedUsername = username.trim()
        val trimmedKeyAlias = keyAlias?.trim().orEmpty().ifBlank { null }

        val credentialRef = when (authMode) {
            AuthenticationMode.PASSWORD -> existingCredentialRef
                ?: buildCredentialReference(trimmedAlias, trimmedHost, trimmedUsername, "target")

            AuthenticationMode.PUBLIC_KEY -> null
        }

        if (authMode == AuthenticationMode.PASSWORD && password != null) {
            credentialStore.savePassword(credentialRef ?: error("Missing credential reference"), password)
        }

        if (authMode == AuthenticationMode.PUBLIC_KEY) {
            ensureKeyPair(trimmedKeyAlias, keyAlgorithm)
        }

        val resolvedJumpHosts = jumpHosts.mapIndexed { index, hop ->
            val trimmedJumpHost = hop.host.trim()
            val trimmedJumpUsername = hop.username.trim()
            val trimmedJumpKeyAlias = hop.keyAlias?.trim().orEmpty().ifBlank { null }
            val resolvedJumpPort = hop.port ?: 22
            val hopCredentialRef = when (hop.authMode) {
                AuthenticationMode.PASSWORD -> hop.existingCredentialRef
                    ?: existing?.jumpHosts?.getOrNull(index)?.credentialRef
                    ?: buildCredentialReference(
                        trimmedAlias,
                        trimmedJumpHost,
                        trimmedJumpUsername,
                        "jump-${index + 1}",
                    )

                AuthenticationMode.PUBLIC_KEY -> null
            }

            if (hop.authMode == AuthenticationMode.PASSWORD && hop.password != null) {
                credentialStore.savePassword(
                    hopCredentialRef ?: error("Missing jump credential reference"),
                    hop.password,
                )
            }

            if (hop.authMode == AuthenticationMode.PUBLIC_KEY) {
                ensureKeyPair(trimmedJumpKeyAlias, hop.keyAlgorithm)
            }

            JumpHostConfig(
                host = trimmedJumpHost,
                port = resolvedJumpPort,
                username = trimmedJumpUsername,
                authMode = hop.authMode,
                credentialRef = hopCredentialRef,
                keyAlias = trimmedJumpKeyAlias.takeIf { hop.authMode == AuthenticationMode.PUBLIC_KEY },
                keyAlgorithm = hop.keyAlgorithm.takeIf { hop.authMode == AuthenticationMode.PUBLIC_KEY },
            )
        }

        val config = ServerConfig(
            id = serverId ?: 0L,
            alias = trimmedAlias,
            host = trimmedHost,
            port = port,
            username = trimmedUsername,
            authMode = authMode,
            credentialRef = credentialRef,
            keyAlias = trimmedKeyAlias,
            keyAlgorithm = if (authMode == AuthenticationMode.PUBLIC_KEY) keyAlgorithm else null,
            jumpHosts = resolvedJumpHosts,
            demoMode = demoMode,
            reconnectAttempts = reconnectAttempts,
            reconnectIntervalSeconds = reconnectIntervalSeconds,
            lastUsedAtEpochMillis = existing?.lastUsedAtEpochMillis ?: 0L,
        )

        dao.upsert(config.toEntity())
        cleanupCredentials(
            previousRefs = existing?.allCredentialRefs().orEmpty(),
            currentRefs = config.allCredentialRefs(),
        )

        return if ((serverId ?: 0L) > 0L) {
            dao.getById(serverId ?: 0L)?.toDomain() ?: config
        } else {
            dao.findBySignature(trimmedAlias, trimmedHost, port, trimmedUsername)?.toDomain() ?: config
        }
    }

    suspend fun seedDefaults() {
        if (dao.count() > 0) {
            return
        }

        credentialStore.savePassword("preview-bastion-password", "preview")
        credentialStore.savePassword("preview-relay-password", "preview")
        credentialStore.savePassword("preview-homelab-password", "preview")
        sshKeyManager.ensureKeyPair("preview-ecdsa-key", SshKeyAlgorithm.ECDSA)

        dao.insertAll(
            listOf(
                ServerConfig(
                    alias = "Bastion Preview",
                    host = "198.51.100.10",
                    username = "ops",
                    authMode = AuthenticationMode.PASSWORD,
                    credentialRef = "preview-bastion-password",
                    demoMode = true,
                    reconnectAttempts = 4,
                    reconnectIntervalSeconds = 6,
                ),
                ServerConfig(
                    alias = "API Logs Preview",
                    host = "203.0.113.22",
                    username = "deploy",
                    authMode = AuthenticationMode.PUBLIC_KEY,
                    keyAlias = "preview-ecdsa-key",
                    keyAlgorithm = SshKeyAlgorithm.ECDSA,
                    demoMode = true,
                    reconnectAttempts = 5,
                    reconnectIntervalSeconds = 5,
                ),
                ServerConfig(
                    alias = "Home Lab Preview",
                    host = "192.0.2.40",
                    username = "root",
                    authMode = AuthenticationMode.PASSWORD,
                    credentialRef = "preview-homelab-password",
                    jumpHosts = listOf(
                        JumpHostConfig(
                            host = "198.51.100.10",
                            port = 22,
                            username = "ops",
                            authMode = AuthenticationMode.PASSWORD,
                            credentialRef = "preview-bastion-password",
                        ),
                        JumpHostConfig(
                            host = "198.51.100.20",
                            port = 22,
                            username = "relay",
                            authMode = AuthenticationMode.PASSWORD,
                            credentialRef = "preview-relay-password",
                        ),
                    ),
                    demoMode = true,
                    reconnectAttempts = 3,
                    reconnectIntervalSeconds = 8,
                ),
            ).map { it.toEntity() },
        )
    }

    private suspend fun cleanupCredentials(previousRefs: Set<String>, currentRefs: Set<String>) {
        previousRefs.minus(currentRefs).forEach { previousRef ->
            if (!isCredentialReferenceUsed(previousRef)) {
                credentialStore.deletePassword(previousRef)
            }
        }
    }

    private suspend fun isCredentialReferenceUsed(credentialRef: String): Boolean {
        return dao.listAll()
            .map { it.toDomain() }
            .any { credentialRef in it.allCredentialRefs() }
    }

    private fun buildCredentialReference(alias: String, host: String, username: String, role: String): String {
        val normalized = listOf(alias, host, username, role)
            .joinToString("-")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return "server-$normalized-password"
    }

    private fun ensureKeyPair(keyAlias: String?, keyAlgorithm: SshKeyAlgorithm?) {
        val resolvedKeyAlias = checkNotNull(keyAlias) { "Missing key alias" }
        val resolvedAlgorithm = checkNotNull(keyAlgorithm) { "Missing key algorithm" }
        checkNotNull(sshKeyManager.ensureKeyPair(resolvedKeyAlias, resolvedAlgorithm)) {
            "Unable to create or load key pair for $resolvedKeyAlias"
        }
    }
}
