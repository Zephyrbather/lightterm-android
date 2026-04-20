package com.lightterm.core.session

import com.lightterm.data.security.SecureCredentialStore
import com.lightterm.data.security.SshKeyManager
import com.lightterm.domain.model.ServerConfig
import java.io.File

class SshTransportFactory(
    private val credentialStore: SecureCredentialStore,
    private val sshKeyManager: SshKeyManager,
    private val appHomeDir: File,
    private val messageResolver: (Int, Array<out Any>) -> String,
) {
    fun create(config: ServerConfig): SshTransport {
        return if (config.demoMode) {
            PreviewSshTransport(messageResolver)
        } else {
            MinaSshTransport(credentialStore, sshKeyManager, appHomeDir, messageResolver)
        }
    }
}
