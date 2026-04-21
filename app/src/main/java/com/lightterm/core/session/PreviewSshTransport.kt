package com.lightterm.core.session

import com.lightterm.R
import com.lightterm.core.device.DeviceProfile
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.VirtualKey
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope

private val previewVirtualKeys = VirtualKey.defaults().associateBy { it.id }

class PreviewSshTransport(
    private val messageResolver: (Int, Array<out Any>) -> String,
) : SshTransport {
    override suspend fun open(
        config: ServerConfig,
        deviceProfile: DeviceProfile,
        initialColumns: Int,
        initialRows: Int,
        scope: CoroutineScope,
        listener: SshTransport.Listener,
    ): SshTransport.ConnectedShell {
        val fileSystem = PreviewRemoteFileSystem()
        listener.onConnected(message(R.string.session_status_preview_connected))
        listener.onLatencyMeasured(12)
        listener.onOutput(
            buildString {
                appendLine("LightTerm Preview Shell")
                appendLine("Connected to ${config.alias} (${config.targetLabel()})")
                appendLine("Type `help` to inspect preview commands.")
                append(previewPrompt(config, fileSystem.homePath))
            },
        )
        return PreviewConnectedShell(
            config = config,
            listener = listener,
            disconnectDetail = message(R.string.session_status_preview_closed),
            fileSystem = fileSystem,
        )
    }

    private inner class PreviewConnectedShell(
        private val config: ServerConfig,
        private val listener: SshTransport.Listener,
        private val disconnectDetail: String,
        private val fileSystem: PreviewRemoteFileSystem,
    ) : SshTransport.ConnectedShell {
        private var currentDirectory = fileSystem.homePath

        override suspend fun write(input: String) {
            when (input) {
                previewVirtualKeys.getValue("ctrl").sequence -> {
                    listener.onOutput("^C\n${previewPrompt(config, currentDirectory)}")
                    return
                }

                previewVirtualKeys.getValue("up").sequence,
                previewVirtualKeys.getValue("down").sequence,
                previewVirtualKeys.getValue("left").sequence,
                previewVirtualKeys.getValue("right").sequence,
                previewVirtualKeys.getValue("tab").sequence,
                previewVirtualKeys.getValue("alt").sequence,
                previewVirtualKeys.getValue("esc").sequence,
                -> return
            }

            val commands = input
                .replace("\r", "\n")
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (commands.isEmpty()) {
                listener.onOutput(previewPrompt(config, currentDirectory))
                return
            }

            commands.forEach { command ->
                listener.onOutput("$command\n")
                val output = responseFor(command)
                if (output == "<clear>") {
                    listener.onOutput("\u000c")
                } else if (output.isNotBlank()) {
                    listener.onOutput("$output\n")
                }
                listener.onOutput(previewPrompt(config, currentDirectory))
            }
        }

        override suspend fun resize(columns: Int, rows: Int) = Unit

        override suspend fun keepAlive() = Unit

        override suspend fun listDirectory(path: String?): RemoteDirectoryListing {
            return fileSystem.listDirectory(path)
        }

        override suspend fun readTextFile(path: String): RemoteTextFile {
            return fileSystem.readTextFile(
                path = path,
                tooLargeMessage = message(R.string.file_manager_error_too_large),
                binaryMessage = message(R.string.file_manager_error_binary),
            )
        }

        override suspend fun writeTextFile(path: String, content: String) {
            fileSystem.writeTextFile(path, content)
        }

        override suspend fun uploadFile(
            remoteDirectoryPath: String,
            remoteFileName: String,
            source: InputStream,
        ) {
            fileSystem.uploadFile(remoteDirectoryPath, remoteFileName, source)
        }

        override suspend fun downloadFile(
            remoteFilePath: String,
            sink: OutputStream,
        ) {
            fileSystem.downloadFile(remoteFilePath, sink)
        }

        override suspend fun close() {
            listener.onDisconnected(disconnectDetail)
        }

        private fun responseFor(command: String): String {
            val trimmed = command.trim()
            if (trimmed.equals("help", ignoreCase = true)) {
                return """
                    Available commands:
                    help
                    ls
                    pwd
                    cd <dir>
                    cat <file>
                    uname -a
                    top
                    clear
                    exit
                """.trimIndent()
            }
            if (trimmed.equals("ls", ignoreCase = true)) {
                return fileSystem.listNames(currentDirectory)
            }
            if (trimmed.equals("pwd", ignoreCase = true)) {
                return currentDirectory
            }
            if (trimmed.startsWith("cd ", ignoreCase = true)) {
                val target = trimmed.substringAfter(' ', "").trim()
                return runCatching {
                    val resolvedPath = fileSystem.resolvePath(target, currentDirectory)
                    fileSystem.requireDirectory(resolvedPath)
                    currentDirectory = resolvedPath
                    ""
                }.getOrElse { throwable ->
                    throwable.message ?: "cd failed"
                }
            }
            if (trimmed.startsWith("cat ", ignoreCase = true)) {
                val target = trimmed.substringAfter(' ', "").trim()
                return runCatching {
                    fileSystem.readTextFile(
                        path = fileSystem.resolvePath(target, currentDirectory),
                        tooLargeMessage = message(R.string.file_manager_error_too_large),
                        binaryMessage = message(R.string.file_manager_error_binary),
                    ).content
                }.getOrElse { throwable ->
                    throwable.message ?: "cat failed"
                }
            }

            return when (trimmed.lowercase()) {
                "uname -a" -> "Linux ${config.host} 6.6.21-android14 #1 SMP PREEMPT"
                "top" -> """
                    Tasks: 143 total, 1 running, 142 sleeping
                    CPU: 2.1% usr 0.7% sys 97.2% idle
                    Mem: 7840M total, 1120M used, 6720M free
                """.trimIndent()

                "clear" -> "<clear>"
                "exit" -> "Session remains open in preview mode."
                else -> "bash: $trimmed: command not found"
            }
        }
    }

    private fun message(
        resId: Int,
        vararg args: Any,
    ): String = messageResolver(resId, args)
}

private class PreviewRemoteFileSystem {
    val homePath: String = "/srv/lightterm"

    private val directories = linkedSetOf(
        "/",
        homePath,
        "$homePath/logs",
        "$homePath/releases",
        "$homePath/.ssh",
    )
    private val files = linkedMapOf(
        "$homePath/deploy.sh" to """
            #!/usr/bin/env bash
            set -euo pipefail
            ./healthcheck.sh
            echo "Deploying LightTerm preview build"
        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        "$homePath/healthcheck.sh" to """
            #!/usr/bin/env bash
            curl -f http://127.0.0.1:8080/health
        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        "$homePath/logs/app.log" to """
            2026-04-21 10:21:33 INFO boot complete
            2026-04-21 10:21:34 INFO ssh session accepted
        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        "$homePath/releases/README.txt" to """
            Preview files are editable in the LightTerm file manager.
        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        "$homePath/.ssh/config" to """
            Host preview
              HostName preview.lightterm.local
              User lightterm
        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
    )

    fun listDirectory(path: String?): RemoteDirectoryListing {
        val resolvedPath = resolvePath(path ?: homePath, homePath)
        requireDirectory(resolvedPath)

        val entries = buildList {
            directories
                .asSequence()
                .filter { child -> child != resolvedPath && remoteParentPath(child) == resolvedPath }
                .forEach { child ->
                    add(
                        RemoteFileEntry(
                            name = remoteBaseName(child),
                            path = child,
                            kind = RemoteFileKind.DIRECTORY,
                            sizeBytes = null,
                            modifiedAtEpochMs = null,
                        ),
                    )
                }

            files.keys
                .asSequence()
                .filter { child -> remoteParentPath(child) == resolvedPath }
                .forEach { child ->
                    add(
                        RemoteFileEntry(
                            name = remoteBaseName(child),
                            path = child,
                            kind = RemoteFileKind.FILE,
                            sizeBytes = files.getValue(child).size.toLong(),
                            modifiedAtEpochMs = null,
                        ),
                    )
                }
        }

        return RemoteDirectoryListing(
            homePath = homePath,
            currentPath = resolvedPath,
            entries = sortRemoteFileEntries(entries),
        )
    }

    fun readTextFile(
        path: String,
        tooLargeMessage: String,
        binaryMessage: String,
    ): RemoteTextFile {
        val resolvedPath = resolvePath(path, homePath)
        val bytes = files[resolvedPath]
            ?: throw IllegalStateException("No such file: $resolvedPath")
        if (directories.contains(resolvedPath)) {
            throw IllegalStateException("Is a directory: $resolvedPath")
        }
        if (bytes.size > MAX_INLINE_TEXT_FILE_BYTES) {
            throw IllegalStateException(tooLargeMessage)
        }

        return RemoteTextFile(
            name = remoteBaseName(resolvedPath),
            path = resolvedPath,
            content = decodeEditableUtf8Text(bytes, binaryMessage),
            sizeBytes = bytes.size.toLong(),
            modifiedAtEpochMs = null,
        )
    }

    fun writeTextFile(path: String, content: String) {
        val resolvedPath = resolvePath(path, homePath)
        if (directories.contains(resolvedPath)) {
            throw IllegalStateException("Is a directory: $resolvedPath")
        }
        files[resolvedPath] = content.toByteArray(StandardCharsets.UTF_8)
    }

    fun uploadFile(
        remoteDirectoryPath: String,
        remoteFileName: String,
        source: InputStream,
    ) {
        val resolvedDirectoryPath = resolvePath(remoteDirectoryPath, homePath)
        requireDirectory(resolvedDirectoryPath)
        files[remoteChildPath(resolvedDirectoryPath, remoteFileName)] = source.readBytes()
    }

    fun downloadFile(
        remoteFilePath: String,
        sink: OutputStream,
    ) {
        val resolvedPath = resolvePath(remoteFilePath, homePath)
        val bytes = files[resolvedPath]
            ?: throw IllegalStateException("No such file: $resolvedPath")
        sink.write(bytes)
        sink.flush()
    }

    fun listNames(path: String): String {
        val listing = listDirectory(path)
        return listing.entries.joinToString("\n") { entry ->
            if (entry.isDirectory) "${entry.name}/" else entry.name
        }
    }

    fun requireDirectory(path: String) {
        if (!directories.contains(path)) {
            throw IllegalStateException("No such directory: $path")
        }
    }

    fun resolvePath(path: String, currentDirectory: String): String {
        val candidate = if (path.startsWith("/")) {
            path
        } else if (path.isBlank() || path == ".") {
            currentDirectory
        } else {
            remoteChildPath(currentDirectory, path)
        }

        val normalizedSegments = mutableListOf<String>()
        candidate.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (normalizedSegments.isNotEmpty()) {
                    normalizedSegments.removeAt(normalizedSegments.lastIndex)
                }

                else -> normalizedSegments += segment
            }
        }
        return if (normalizedSegments.isEmpty()) {
            "/"
        } else {
            "/" + normalizedSegments.joinToString("/")
        }
    }
}

private fun previewPrompt(config: ServerConfig, currentDirectory: String): String {
    return "${config.username}@${config.host}:$currentDirectory$ "
}
