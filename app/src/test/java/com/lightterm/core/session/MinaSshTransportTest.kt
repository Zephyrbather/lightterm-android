package com.lightterm.core.session

import org.apache.sshd.common.util.net.SshdSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `buildShellPromptInitCommand sets simple prompt and clears screen`() {
        val command = buildShellPromptInitCommand()

        assertTrue(command.contains("PS1='$ '"))
        assertTrue(command.contains("PROMPT='$ '"))
        assertTrue(command.contains("\\033[2J\\033[H"))
        assertTrue(command.endsWith("\r"))
    }
}
