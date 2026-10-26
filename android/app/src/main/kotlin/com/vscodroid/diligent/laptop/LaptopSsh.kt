package com.vscodroid.diligent.laptop

import com.vscodroid.diligent.process.BundledShell.Companion.quote

/** Why ssh (exit code 255) did not get a command to the laptop. */
enum class SshFailure { Unreachable, KeyRefused, HostKeyChanged }

/** The answer of the Laptop screen's Test. */
sealed interface LaptopTest {
    /** Logged in: [system] is `uname -sm` (for example `Darwin arm64`). */
    data class Connected(val system: String, val node: Boolean, val git: Boolean) : LaptopTest
    data class Failed(val failure: SshFailure, val output: List<String>) : LaptopTest
}

/**
 * The ssh command lines Diligent runs against a laptop, as `bash -c` scripts.
 *
 * Every connection is non-interactive (`BatchMode`: a password prompt would
 * hang with nobody to answer it), gives up after 8 seconds, and trusts a
 * laptop's host key the first time it is seen but never a changed one
 * (`accept-new`). [Machine.target] reaches the command line quoted; the
 * [Machines] rules keep it from starting with `-`.
 */
object LaptopSsh {
    const val OPTIONS = "-o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new"

    /** What ssh itself exits with when it could not run the command. */
    const val SSH_FAILED = 255

    /** The Laptop screen's Test, exactly as the screen shows it. */
    fun test(machine: Machine): String = "ssh $OPTIONS ${quote(machine.target)} 'uname -sm; command -v node git'"

    /** The line to paste on the laptop so it accepts [publicKey]. */
    fun authorizeCommand(publicKey: String): String =
        "mkdir -p ~/.ssh && echo ${quote(publicKey.trim())} >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"

    /** Reads what ssh printed when it exited with [SSH_FAILED]. */
    fun failureOf(output: List<String>): SshFailure = when {
        output.any { "Permission denied" in it } -> SshFailure.KeyRefused
        output.any { "Host key verification failed" in it || "IDENTIFICATION HAS CHANGED" in it } -> SshFailure.HostKeyChanged
        else -> SshFailure.Unreachable
    }

    /**
     * Reads the Test's answer. `command -v node git` prints a path per tool it
     * finds and exits 1 when one is missing, which is still a connection.
     */
    fun parseTest(exitCode: Int, output: List<String>): LaptopTest {
        if (exitCode == SSH_FAILED) return LaptopTest.Failed(failureOf(output), output)
        val lines = output.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("Warning: Permanently added") }
        fun has(tool: String) = lines.any { it == tool || it.endsWith("/$tool") }
        return LaptopTest.Connected(system = lines.firstOrNull { !it.startsWith("/") }.orEmpty(), node = has("node"), git = has("git"))
    }
}
