package com.lightterm.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lightterm.domain.model.SshKeyAlgorithm
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

class SshKeyManager {
    private val keyStoreName = "AndroidKeyStore"

    fun ensureKeyPair(alias: String, algorithm: SshKeyAlgorithm): KeyPair? {
        loadKeyPair(alias)?.let { return it }

        val generator = KeyPairGenerator.getInstance(resolveAlgorithm(algorithm), keyStoreName)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).setUserAuthenticationRequired(false)

        when (algorithm) {
            SshKeyAlgorithm.RSA -> {
                builder
                    .setKeySize(3072)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            }

            SshKeyAlgorithm.ECDSA -> {
                builder
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                    )
            }

            SshKeyAlgorithm.ED25519 -> {
                builder.setDigests(KeyProperties.DIGEST_NONE)
            }
        }

        return runCatching {
            generator.initialize(builder.build())
            generator.generateKeyPair()
        }.getOrNull()
    }

    fun loadKeyPair(alias: String?): KeyPair? {
        if (alias.isNullOrBlank()) {
            return null
        }
        val keyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    private fun resolveAlgorithm(algorithm: SshKeyAlgorithm): String = when (algorithm) {
        SshKeyAlgorithm.RSA -> KeyProperties.KEY_ALGORITHM_RSA
        SshKeyAlgorithm.ECDSA -> KeyProperties.KEY_ALGORITHM_EC
        SshKeyAlgorithm.ED25519 -> "Ed25519"
    }
}

