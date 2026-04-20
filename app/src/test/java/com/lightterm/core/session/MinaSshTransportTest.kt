package com.lightterm.core.session

import org.apache.sshd.common.util.net.SshdSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class MinaSshTransportTest {
    @Test
    fun `resolvePortForwardConnectTarget uses bound port`() {
        val target = resolvePortForwardConnectTarget(
            SshdSocketAddress(SshdSocketAddress.LOCALHOST_NAME, 40417),
        )

        assertEquals(SshdSocketAddress.LOCALHOST_NAME, target.hostName)
        assertEquals(40417, target.port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolvePortForwardConnectTarget rejects zero port`() {
        resolvePortForwardConnectTarget(
            SshdSocketAddress(SshdSocketAddress.LOCALHOST_NAME, 0),
        )
    }
}
