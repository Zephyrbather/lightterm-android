package com.lightterm.core.network

import android.content.Context
import android.net.ConnectivityManager
import com.lightterm.R
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class ServerConnectivityTester(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    fun test(host: String, port: Int): Result {
        val targetHost = host.trim()
        require(targetHost.isNotEmpty()) { appContext.getString(R.string.server_message_enter_host_for_test) }
        require(port in 1..65535) { appContext.getString(R.string.server_message_enter_valid_port_for_test) }

        val resolvedAddress = resolveAddress(targetHost)
            ?: return Result(
                resolvedAddress = null,
                localAddress = resolveActiveIpv4Address()?.let { "${it.address.hostAddress}/${it.prefixLength}" },
                sameSubnet = null,
                pingSuccessful = false,
                portReachable = false,
                noteResId = R.string.server_connectivity_host_parse_failed,
            )

        val localAddress = resolveActiveIpv4Address()
        val sameSubnet = if (localAddress != null && resolvedAddress is Inet4Address) {
            isSameSubnet(localAddress.address.address, resolvedAddress.address, localAddress.prefixLength)
        } else {
            null
        }

        val resolvedHost = resolvedAddress.hostAddress ?: targetHost
        val pingSuccessful = pingHost(resolvedHost)
        val portReachable = testTcpPort(resolvedAddress, port)

        return Result(
            resolvedAddress = resolvedHost,
            localAddress = localAddress?.let { "${it.address.hostAddress}/${it.prefixLength}" },
            sameSubnet = sameSubnet,
            pingSuccessful = pingSuccessful,
            portReachable = portReachable,
            noteResId = null,
        )
    }

    private fun resolveAddress(host: String): InetAddress? {
        val allAddresses = runCatching { InetAddress.getAllByName(host).toList() }
            .getOrElse { return null }
        return allAddresses.firstOrNull { it is Inet4Address } ?: allAddresses.firstOrNull()
    }

    private fun resolveActiveIpv4Address(): ActiveIpv4Address? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
        val linkAddress = linkProperties.linkAddresses.firstOrNull { address ->
            address.address is Inet4Address && !address.address.isLoopbackAddress
        } ?: return null

        return ActiveIpv4Address(
            address = linkAddress.address as Inet4Address,
            prefixLength = linkAddress.prefixLength,
        )
    }

    private fun pingHost(host: String): Boolean {
        val command = listOf("/system/bin/ping", "-c", "1", "-W", "2", host)
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return runCatching {
                InetAddress.getByName(host).isReachable(2_000)
            }.getOrDefault(false)
        }

        return try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        } finally {
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }

    private fun testTcpPort(address: InetAddress, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), 2_000)
            }
            true
        }.getOrDefault(false)
    }

    private fun isSameSubnet(localAddress: ByteArray, remoteAddress: ByteArray, prefixLength: Int): Boolean {
        val fullBytes = prefixLength / 8
        val partialBits = prefixLength % 8

        for (index in 0 until fullBytes) {
            if (localAddress[index] != remoteAddress[index]) {
                return false
            }
        }

        if (partialBits == 0) {
            return true
        }

        val mask = (0xFF shl (8 - partialBits)) and 0xFF
        return (localAddress[fullBytes].toInt() and mask) == (remoteAddress[fullBytes].toInt() and mask)
    }

    data class Result(
        val resolvedAddress: String?,
        val localAddress: String?,
        val sameSubnet: Boolean?,
        val pingSuccessful: Boolean,
        val portReachable: Boolean,
        val noteResId: Int?,
    ) {
        fun summary(
            port: Int,
            messageResolver: (Int, Array<out Any>) -> String,
        ): String {
            noteResId?.let { return messageResolver(it, emptyArray()) }

            val parts = buildList {
                add(
                    messageResolver(
                        R.string.server_connectivity_server_value,
                        arrayOf(resolvedAddress ?: messageResolver(R.string.server_connectivity_unknown_address, emptyArray())),
                    ),
                )
                localAddress?.let {
                    add(messageResolver(R.string.server_connectivity_phone_value, arrayOf(it)))
                }
                add(
                    messageResolver(
                        when (sameSubnet) {
                            true -> R.string.server_connectivity_same_subnet
                            false -> R.string.server_connectivity_different_subnet
                            null -> R.string.server_connectivity_subnet_unknown
                        },
                        emptyArray(),
                    ),
                )
                add(
                    messageResolver(
                        if (pingSuccessful) {
                            R.string.server_connectivity_ping_reachable
                        } else {
                            R.string.server_connectivity_ping_unreachable
                        },
                        emptyArray(),
                    ),
                )
                add(
                    messageResolver(
                        if (portReachable) {
                            R.string.server_connectivity_port_reachable
                        } else {
                            R.string.server_connectivity_port_unreachable
                        },
                        arrayOf(port),
                    ),
                )
            }
            return parts.joinToString(" · ")
        }
    }

    private data class ActiveIpv4Address(
        val address: Inet4Address,
        val prefixLength: Int,
    )
}
