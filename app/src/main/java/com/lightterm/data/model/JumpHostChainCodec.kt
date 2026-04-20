package com.lightterm.data.model

import com.lightterm.domain.model.AuthenticationMode
import com.lightterm.domain.model.JumpHostConfig
import com.lightterm.domain.model.SshKeyAlgorithm
import org.json.JSONArray
import org.json.JSONObject

fun encodeJumpHosts(jumpHosts: List<JumpHostConfig>): String? {
    if (jumpHosts.isEmpty()) {
        return null
    }

    return JSONArray().apply {
        jumpHosts.forEach { hop ->
            put(
                JSONObject().apply {
                    put(KEY_HOST, hop.host)
                    put(KEY_PORT, hop.port)
                    put(KEY_USERNAME, hop.username)
                    put(KEY_AUTH_MODE, hop.authMode.name)
                    putOpt(KEY_CREDENTIAL_REF, hop.credentialRef)
                    putOpt(KEY_KEY_ALIAS, hop.keyAlias)
                    putOpt(KEY_KEY_ALGORITHM, hop.keyAlgorithm?.name)
                },
            )
        }
    }.toString()
}

fun decodeJumpHosts(
    jumpChainJson: String?,
    legacyJumpHost: String?,
    legacyJumpPort: Int?,
    legacyJumpUsername: String?,
    legacyJumpAuthMode: String?,
    legacyJumpCredentialRef: String?,
    legacyJumpKeyAlias: String?,
    legacyJumpKeyAlgorithm: String?,
): List<JumpHostConfig> {
    decodeJumpHosts(jumpChainJson)?.takeIf { it.isNotEmpty() }?.let { return it }

    if (legacyJumpHost.isNullOrBlank() || legacyJumpUsername.isNullOrBlank()) {
        return emptyList()
    }

    return listOf(
        JumpHostConfig(
            host = legacyJumpHost,
            port = legacyJumpPort ?: 22,
            username = legacyJumpUsername,
            authMode = legacyJumpAuthMode
                ?.takeIf { it.isNotBlank() }
                ?.let(AuthenticationMode::valueOf)
                ?: AuthenticationMode.PASSWORD,
            credentialRef = legacyJumpCredentialRef,
            keyAlias = legacyJumpKeyAlias,
            keyAlgorithm = legacyJumpKeyAlgorithm
                ?.takeIf { it.isNotBlank() }
                ?.let(SshKeyAlgorithm::valueOf),
        ),
    )
}

private fun decodeJumpHosts(jumpChainJson: String?): List<JumpHostConfig>? {
    if (jumpChainJson.isNullOrBlank()) {
        return null
    }

    return runCatching {
        val chainArray = JSONArray(jumpChainJson)
        buildList {
            repeat(chainArray.length()) { index ->
                val item = chainArray.getJSONObject(index)
                val host = item.optString(KEY_HOST).trim()
                val username = item.optString(KEY_USERNAME).trim()
                if (host.isBlank() || username.isBlank()) {
                    return@repeat
                }

                add(
                    JumpHostConfig(
                        host = host,
                        port = item.optInt(KEY_PORT, 22),
                        username = username,
                        authMode = item.optString(KEY_AUTH_MODE)
                            .takeIf { it.isNotBlank() }
                            ?.let(AuthenticationMode::valueOf)
                            ?: AuthenticationMode.PASSWORD,
                        credentialRef = item.optString(KEY_CREDENTIAL_REF).ifBlank { null },
                        keyAlias = item.optString(KEY_KEY_ALIAS).ifBlank { null },
                        keyAlgorithm = item.optString(KEY_KEY_ALGORITHM)
                            .takeIf { it.isNotBlank() }
                            ?.let(SshKeyAlgorithm::valueOf),
                    ),
                )
            }
        }
    }.getOrNull()
}

private const val KEY_HOST = "host"
private const val KEY_PORT = "port"
private const val KEY_USERNAME = "username"
private const val KEY_AUTH_MODE = "authMode"
private const val KEY_CREDENTIAL_REF = "credentialRef"
private const val KEY_KEY_ALIAS = "keyAlias"
private const val KEY_KEY_ALGORITHM = "keyAlgorithm"
