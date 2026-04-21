package com.lightterm.core.session

import android.util.Log
import com.lightterm.R
import com.lightterm.core.device.DeviceProfile
import com.lightterm.data.security.SecureCredentialStore
import com.lightterm.data.security.SshKeyManager
import com.lightterm.domain.model.AuthenticationMode
import com.lightterm.domain.model.ServerConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker
import org.apache.sshd.common.channel.PtyChannelConfiguration
import org.apache.sshd.common.channel.PtyMode
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory

class MinaSshTransport(
    private val credentialStore: SecureCredentialStore,
    private val sshKeyManager: SshKeyManager,
    private val appHomeDir: File,
    private val messageResolver: (Int, Array<out Any>) -> String,
) : SshTransport {
    override suspend fun open(
        config: ServerConfig,
        deviceProfile: DeviceProfile,
        initialColumns: Int,
        initialRows: Int,
        scope: CoroutineScope,
        listener: SshTransport.Listener,
    ): SshTransport.ConnectedShell = withContext(Dispatchers.IO) {
        configureAndroidCompatibleRuntime(appHomeDir)
        val startedAt = System.currentTimeMillis()
        var client: SshClient? = null

        try {
            client = SshClient.setUpDefaultClient()
            client.start()
            val connectionChain = openConnectionChain(client, config)
            val session = connectionChain.finalSession

            val channel = createInteractiveShellChannel(
                session = session,
                initialColumns = initialColumns,
                initialRows = initialRows,
            )
            channel.open().verify(15_000L)

            val disconnected = AtomicBoolean(false)
            startPump(scope, channel.invertedOut, listener, disconnected)
            startPump(scope, channel.invertedErr, listener, disconnected)

            listener.onConnected("SSHv2")
            listener.onLatencyMeasured(System.currentTimeMillis() - startedAt)

            MinaConnectedShell(
                client = client,
                session = session,
                channel = channel,
                jumpSessions = connectionChain.jumpSessions,
                portForwardTrackers = connectionChain.portForwardTrackers,
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "SSH open failed for ${config.alias} (${config.targetLabel()})", throwable)
            runCatching { client?.stop() }
            throw IllegalStateException(
                throwable.message?.takeIf { it.isNotBlank() } ?: buildDiagnosticMessage(throwable),
                throwable,
            )
        }
    }

    private fun createInteractiveShellChannel(
        session: ClientSession,
        initialColumns: Int,
        initialRows: Int,
    ): ChannelShell {
        val columns = initialColumns.coerceAtLeast(MIN_PTY_COLUMNS)
        val rows = initialRows.coerceAtLeast(MIN_PTY_ROWS)
        val ptyConfiguration = PtyChannelConfiguration().apply {
            ptyType = DEFAULT_TERM_TYPE
            ptyColumns = columns
            ptyLines = rows
            ptyWidth = columns * DEFAULT_PTY_CELL_WIDTH_PIXELS
            ptyHeight = rows * DEFAULT_PTY_CELL_HEIGHT_PIXELS
            ptyModes = mapOf(
                PtyMode.ECHO to ENABLED_MODE,
                PtyMode.ICANON to ENABLED_MODE,
                PtyMode.ISIG to ENABLED_MODE,
                PtyMode.ICRNL to ENABLED_MODE,
                PtyMode.ONLCR to ENABLED_MODE,
                PtyMode.OPOST to ENABLED_MODE,
            )
        }

        return session.createShellChannel(
            ptyConfiguration,
            mapOf("TERM" to DEFAULT_TERM_TYPE),
        )
    }

    private fun openConnectionChain(client: SshClient, config: ServerConfig): ConnectionChain {
        if (!config.hasJumpHost()) {
            val targetLabel = config.targetLabel()
            val finalSession = openClientSession(
                client = client,
                username = config.username,
                host = config.host,
                port = config.port,
                stepLabel = "connect target $targetLabel",
            )
            authenticateSession(
                session = finalSession,
                authMode = config.authMode,
                credentialRef = config.credentialRef,
                keyAlias = config.keyAlias,
                label = config.alias,
                stepLabel = "authenticate target $targetLabel",
            )
            return ConnectionChain(finalSession = finalSession)
        }

        val jumpSessions = mutableListOf<ClientSession>()
        val portForwardTrackers = mutableListOf<ExplicitPortForwardingTracker>()
        var connectHost = config.jumpHosts.first().host
        var connectPort = config.jumpHosts.first().port

        config.jumpHosts.forEachIndexed { index, hop ->
            val hopLabel = "jump ${index + 1} ${hop.targetLabel()}"
            val jumpSession = openClientSession(
                client = client,
                username = hop.username,
                host = connectHost,
                port = connectPort,
                stepLabel = "connect $hopLabel",
            )
            authenticateSession(
                session = jumpSession,
                authMode = hop.authMode,
                credentialRef = hop.credentialRef,
                keyAlias = hop.keyAlias,
                label = "${config.alias} jump ${index + 1}",
                stepLabel = "authenticate $hopLabel",
            )
            jumpSessions += jumpSession

            val nextTargetHost = config.jumpHosts.getOrNull(index + 1)?.host ?: config.host
            val nextTargetPort = config.jumpHosts.getOrNull(index + 1)?.port ?: config.port
            val nextTargetLabel = config.jumpHosts.getOrNull(index + 1)?.targetLabel() ?: config.targetLabel()
            val portForwardTracker = step("forward $hopLabel -> $nextTargetLabel") {
                jumpSession.createLocalPortForwardingTracker(
                    SshdSocketAddress(SshdSocketAddress.LOCALHOST_NAME, 0),
                    SshdSocketAddress(nextTargetHost, nextTargetPort),
                )
            }
            portForwardTrackers += portForwardTracker

            val connectTarget = resolvePortForwardConnectTarget(portForwardTracker.boundAddress)
            connectHost = connectTarget.hostName
            connectPort = connectTarget.port
        }

        val targetLabel = config.targetLabel()
        val finalSession = openClientSession(
            client = client,
            username = config.username,
            host = connectHost,
            port = connectPort,
            stepLabel = "connect target $targetLabel through jump chain",
        )
        authenticateSession(
            session = finalSession,
            authMode = config.authMode,
            credentialRef = config.credentialRef,
            keyAlias = config.keyAlias,
            label = config.alias,
            stepLabel = "authenticate target $targetLabel through jump chain",
        )

        return ConnectionChain(
            finalSession = finalSession,
            jumpSessions = jumpSessions,
            portForwardTrackers = portForwardTrackers,
        )
    }

    private fun openClientSession(
        client: SshClient,
        username: String,
        host: String,
        port: Int,
        stepLabel: String,
    ): ClientSession = step(stepLabel) {
        client.connect(username, host, port).verify(15_000L).session
    }

    private fun authenticateSession(
        session: ClientSession,
        authMode: AuthenticationMode,
        credentialRef: String?,
        keyAlias: String?,
        label: String,
        stepLabel: String,
    ) {
        step(stepLabel) {
            authenticate(
                session = session,
                authMode = authMode,
                credentialRef = credentialRef,
                keyAlias = keyAlias,
                label = label,
            )
            session.auth().verify(15_000L)
        }
    }

    private fun authenticate(
        session: ClientSession,
        authMode: AuthenticationMode,
        credentialRef: String?,
        keyAlias: String?,
        label: String,
    ) {
        when (authMode) {
            AuthenticationMode.PASSWORD -> {
                val password = credentialStore.readPassword(credentialRef)
                    ?: error("Missing password for $label")
                session.addPasswordIdentity(password)
            }

            AuthenticationMode.PUBLIC_KEY -> {
                val keyPair = sshKeyManager.loadKeyPair(keyAlias)
                    ?: error("Missing key pair for $label")
                session.addPublicKeyIdentity(keyPair)
            }
        }
    }

    private fun startPump(
        scope: CoroutineScope,
        stream: InputStream,
        listener: SshTransport.Listener,
        disconnected: AtomicBoolean,
    ) {
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            try {
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    val chunk = String(buffer, 0, read, StandardCharsets.UTF_8)
                    listener.onOutput(chunk)
                }
            } finally {
                if (disconnected.compareAndSet(false, true)) {
                    listener.onDisconnected(message(R.string.session_status_remote_closed))
                }
            }
        }
    }

    private inner class MinaConnectedShell(
        private val client: SshClient,
        private val session: ClientSession,
        private val channel: ChannelShell,
        private val jumpSessions: List<ClientSession>,
        private val portForwardTrackers: List<ExplicitPortForwardingTracker>,
    ) : SshTransport.ConnectedShell {
        override suspend fun write(input: String) {
            withContext(Dispatchers.IO) {
                channel.invertedIn.write(input.toByteArray(StandardCharsets.UTF_8))
                channel.invertedIn.flush()
            }
        }

        override suspend fun resize(columns: Int, rows: Int) {
            withContext(Dispatchers.IO) {
                runCatching {
                    channel.sendWindowChange(
                        columns,
                        rows,
                        rows * DEFAULT_PTY_CELL_HEIGHT_PIXELS,
                        columns * DEFAULT_PTY_CELL_WIDTH_PIXELS,
                    )
                }
            }
        }

        override suspend fun keepAlive() {
            // Apache MINA supports richer heartbeat configuration, but this MVP keeps it
            // transport-neutral and relies on session-level timers for future extension.
        }

        override suspend fun listDirectory(path: String?): RemoteDirectoryListing = withSftpClient { sftp ->
            val homePath = sftp.canonicalPath(".")
            val resolvedPath = sftp.canonicalPath(path?.takeIf { it.isNotBlank() } ?: homePath)
            val directoryAttributes = sftp.stat(resolvedPath)
            ensureDirectory(attributes = directoryAttributes, path = resolvedPath)

            val entries = sftp.readDir(resolvedPath)
                .asSequence()
                .filter { entry -> entry.filename !in setOf(".", "..") }
                .map { entry ->
                    val attributes = entry.attributes
                    RemoteFileEntry(
                        name = entry.filename,
                        path = remoteChildPath(resolvedPath, entry.filename),
                        kind = when {
                            attributes.isDirectory -> RemoteFileKind.DIRECTORY
                            attributes.isSymbolicLink -> RemoteFileKind.SYMLINK
                            else -> RemoteFileKind.FILE
                        },
                        sizeBytes = attributes.size,
                        modifiedAtEpochMs = attributes.modifyTime?.toMillis(),
                    )
                }
                .toList()

            RemoteDirectoryListing(
                homePath = homePath,
                currentPath = resolvedPath,
                entries = sortRemoteFileEntries(entries),
            )
        }

        override suspend fun readTextFile(path: String): RemoteTextFile = withSftpClient { sftp ->
            val resolvedPath = sftp.canonicalPath(path)
            val attributes = sftp.stat(resolvedPath)
            ensureRegularFile(attributes = attributes, path = resolvedPath)

            val bytes = sftp.read(resolvedPath).use { input ->
                readLimitedBytes(
                    source = input,
                    maxBytes = MAX_INLINE_TEXT_FILE_BYTES,
                    tooLargeMessage = message(R.string.file_manager_error_too_large),
                )
            }
            val content = decodeEditableUtf8Text(
                bytes = bytes,
                binaryMessage = message(R.string.file_manager_error_binary),
            )

            RemoteTextFile(
                name = remoteBaseName(resolvedPath),
                path = resolvedPath,
                content = content,
                sizeBytes = attributes.size,
                modifiedAtEpochMs = attributes.modifyTime?.toMillis(),
            )
        }

        override suspend fun writeTextFile(path: String, content: String) {
            withSftpClient { sftp ->
                val resolvedPath = sftp.canonicalPath(path)
                val attributes = sftp.stat(resolvedPath)
                ensureRegularFile(attributes = attributes, path = resolvedPath)

                sftp.write(
                    resolvedPath,
                    SftpClient.OpenMode.Write,
                    SftpClient.OpenMode.Create,
                    SftpClient.OpenMode.Truncate,
                ).use { output ->
                    output.write(content.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }
        }

        override suspend fun uploadFile(
            remoteDirectoryPath: String,
            remoteFileName: String,
            source: InputStream,
        ) {
            withSftpClient { sftp ->
                val resolvedDirectoryPath = sftp.canonicalPath(remoteDirectoryPath)
                val directoryAttributes = sftp.stat(resolvedDirectoryPath)
                ensureDirectory(attributes = directoryAttributes, path = resolvedDirectoryPath)

                val remoteFilePath = remoteChildPath(resolvedDirectoryPath, remoteFileName)
                sftp.write(
                    remoteFilePath,
                    SftpClient.OpenMode.Write,
                    SftpClient.OpenMode.Create,
                    SftpClient.OpenMode.Truncate,
                ).use { output ->
                    source.copyTo(output)
                    output.flush()
                }
            }
        }

        override suspend fun downloadFile(
            remoteFilePath: String,
            sink: OutputStream,
        ) {
            withSftpClient { sftp ->
                val resolvedPath = sftp.canonicalPath(remoteFilePath)
                val attributes = sftp.stat(resolvedPath)
                ensureRegularFile(attributes = attributes, path = resolvedPath)

                sftp.read(resolvedPath).use { input ->
                    input.copyTo(sink)
                }
                sink.flush()
            }
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) {
                runCatching { channel.close(false) }
                runCatching { session.close(false) }
                portForwardTrackers.asReversed().forEach { tracker ->
                    runCatching { tracker.close() }
                }
                jumpSessions.asReversed().forEach { jumpSession ->
                    runCatching { jumpSession.close(false) }
                }
                runCatching { client.stop() }
            }
        }

        private suspend fun <T> withSftpClient(
            block: (SftpClient) -> T,
        ): T = withContext(Dispatchers.IO) {
            SftpClientFactory.instance().createSftpClient(session).use { sftpClient ->
                block(sftpClient)
            }
        }

        private fun ensureDirectory(
            attributes: SftpClient.Attributes,
            path: String,
        ) {
            if (!attributes.isDirectory) {
                throw IllegalStateException(message(R.string.file_manager_error_not_directory, path))
            }
        }

        private fun ensureRegularFile(
            attributes: SftpClient.Attributes,
            path: String,
        ) {
            if (attributes.isDirectory) {
                throw IllegalStateException(message(R.string.file_manager_error_is_directory, path))
            }
        }
    }

    private data class ConnectionChain(
        val finalSession: ClientSession,
        val jumpSessions: List<ClientSession> = emptyList(),
        val portForwardTrackers: List<ExplicitPortForwardingTracker> = emptyList(),
    )

    private fun buildDiagnosticMessage(throwable: Throwable): String {
        val chain = generateSequence(throwable) { it.cause }
            .take(4)
            .map { cause ->
                val summary = cause.message?.takeIf { it.isNotBlank() } ?: "no message"
                "${cause::class.java.simpleName}: $summary"
            }
            .toList()

        return chain.joinToString(" <- ")
    }

    private inline fun <T> step(
        stepLabel: String,
        block: () -> T,
    ): T {
        return try {
            block()
        } catch (throwable: Throwable) {
            throw IllegalStateException("$stepLabel failed: ${buildDiagnosticMessage(throwable)}", throwable)
        }
    }

    private fun message(
        resId: Int,
        vararg args: Any,
    ): String = messageResolver(resId, args)

    private fun configureAndroidCompatibleRuntime(appHomeDir: File) {
        val normalizedHomeDir = appHomeDir.absoluteFile
        File(normalizedHomeDir, ".ssh").mkdirs()

        // MINA SSHD probes optional BC/EdDSA registrars via ThreadUtils, which falls back to
        // ClassLoader.getSystemClassLoader(). On this vivo Android 16 build that path crashes
        // inside android.os.Trace, so we disable optional registrar discovery entirely.
        System.setProperty("user.home", normalizedHomeDir.absolutePath)
        System.setProperty("user.dir", normalizedHomeDir.absolutePath)
        System.setProperty("org.apache.sshd.security.registrars", "none")
        System.setProperty("org.apache.sshd.security.defaultProvider", "none")
        System.setProperty("org.apache.sshd.registerBouncyCastle", "false")
        System.setProperty("org.apache.sshd.eddsaSupport", "false")
    }

    companion object {
        private const val TAG = "LightTermSsh"
        private const val DEFAULT_TERM_TYPE = "xterm-256color"
        private const val ENABLED_MODE = 1
        private const val MIN_PTY_COLUMNS = 40
        private const val MIN_PTY_ROWS = 10
        private const val DEFAULT_SSH_PORT = 22
        private const val DEFAULT_PTY_CELL_WIDTH_PIXELS = 9
        private const val DEFAULT_PTY_CELL_HEIGHT_PIXELS = 18
    }
}

internal fun resolvePortForwardConnectTarget(boundAddress: SshdSocketAddress): SshdSocketAddress {
    val port = boundAddress.port
    require(port in 1..65535) { "Invalid port: $port" }

    val host = boundAddress.hostName
        .takeUnless { it.isNullOrBlank() || SshdSocketAddress.isWildcardAddress(it) }
        ?: SshdSocketAddress.LOCALHOST_NAME

    return SshdSocketAddress(host, port)
}
