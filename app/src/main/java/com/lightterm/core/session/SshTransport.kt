package com.lightterm.core.session

import com.lightterm.core.device.DeviceProfile
import com.lightterm.domain.model.ServerConfig
import kotlinx.coroutines.CoroutineScope

interface SshTransport {
    suspend fun open(
        config: ServerConfig,
        deviceProfile: DeviceProfile,
        initialColumns: Int,
        initialRows: Int,
        scope: CoroutineScope,
        listener: Listener,
    ): ConnectedShell

    interface Listener {
        suspend fun onConnected(detail: String)
        suspend fun onOutput(text: String)
        suspend fun onDisconnected(detail: String)
        suspend fun onLatencyMeasured(latencyMs: Long)
    }

    interface ConnectedShell {
        suspend fun write(input: String)
        suspend fun resize(columns: Int, rows: Int)
        suspend fun keepAlive()
        suspend fun close()
    }
}
