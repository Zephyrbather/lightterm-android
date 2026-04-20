package com.lightterm.core.session

import com.lightterm.R
import com.lightterm.core.device.DeviceProfile
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.VirtualKey
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
        listener.onConnected(message(R.string.session_status_preview_connected))
        listener.onLatencyMeasured(12)
        listener.onOutput(
            buildString {
                appendLine("LightTerm Preview Shell")
                appendLine("Connected to ${config.alias} (${config.targetLabel()})")
                appendLine("Type `help` to inspect preview commands.")
                append(prompt(config))
            },
        )
        return PreviewConnectedShell(
            config = config,
            listener = listener,
            disconnectDetail = message(R.string.session_status_preview_closed),
        )
    }

    private class PreviewConnectedShell(
        private val config: ServerConfig,
        private val listener: SshTransport.Listener,
        private val disconnectDetail: String,
    ) : SshTransport.ConnectedShell {
        override suspend fun write(input: String) {
            when (input) {
                previewVirtualKeys.getValue("ctrl").sequence -> {
                    listener.onOutput("^C\n${prompt(config)}")
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
                listener.onOutput(prompt(config))
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
                listener.onOutput(prompt(config))
            }
        }

        override suspend fun resize(columns: Int, rows: Int) = Unit

        override suspend fun keepAlive() = Unit

        override suspend fun close() {
            listener.onDisconnected(disconnectDetail)
        }

        private fun responseFor(command: String): String = when (command.lowercase()) {
            "help" -> """
                Available commands:
                help
                ls
                pwd
                uname -a
                top
                clear
                exit
            """.trimIndent()

            "ls" -> """
                logs/
                releases/
                .ssh/
                deploy.sh
                healthcheck.sh
            """.trimIndent()

            "pwd" -> "/srv/lightterm"
            "uname -a" -> "Linux ${config.host} 6.6.21-android14 #1 SMP PREEMPT"
            "top" -> """
                Tasks: 143 total, 1 running, 142 sleeping
                CPU: 2.1% usr 0.7% sys 97.2% idle
                Mem: 7840M total, 1120M used, 6720M free
            """.trimIndent()

            "clear" -> "<clear>"
            "exit" -> "Session remains open in preview mode."
            else -> "bash: $command: command not found"
        }
    }

    private fun message(
        resId: Int,
        vararg args: Any,
    ): String = messageResolver(resId, args)
}

private fun prompt(config: ServerConfig): String = "${config.username}@${config.host}:~$ "
