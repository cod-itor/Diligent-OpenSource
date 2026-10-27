package com.vscodroid.diligent.laptop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LaptopSshTest {

    private val mac = Machine("mac", "192.168.1.20", "me", MachineOs.MacOs)

    @Test
    fun `the test is the guide's exact command`() {
        assertEquals(
            "ssh -o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new 'me@192.168.1.20' 'uname -sm; command -v node git'",
            LaptopSsh.test(mac),
        )
    }

    @Test
    fun `the authorized_keys line`() {
        assertEquals(
            "mkdir -p ~/.ssh && echo 'ssh-ed25519 AAAA diligent@android' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys",
            LaptopSsh.authorizeCommand("ssh-ed25519 AAAA diligent@android\n"),
        )
    }

    @Test
    fun `a connection with both tools`() {
        assertEquals(
            LaptopTest.Connected("Darwin arm64", node = true, git = true),
            LaptopSsh.parseTest(0, listOf("Warning: Permanently added '192.168.1.20' (ED25519) to the list of known hosts.", "Darwin arm64", "/opt/homebrew/bin/node", "/usr/bin/git")),
        )
    }

    @Test
    fun `a connection without node still connects`() {
        assertEquals(LaptopTest.Connected("Linux x86_64", node = false, git = true), LaptopSsh.parseTest(1, listOf("Linux x86_64", "/usr/bin/git")))
    }

    @Test
    fun `ssh failures are told apart`() {
        assertEquals(SshFailure.Unreachable, (LaptopSsh.parseTest(255, listOf("ssh: connect to host 1.2.3.4 port 22: Operation timed out")) as LaptopTest.Failed).failure)
        assertEquals(SshFailure.KeyRefused, LaptopSsh.failureOf(listOf("me@1.2.3.4: Permission denied (publickey,password).")))
        assertEquals(SshFailure.HostKeyChanged, LaptopSsh.failureOf(listOf("Host key verification failed.")))
    }
}
