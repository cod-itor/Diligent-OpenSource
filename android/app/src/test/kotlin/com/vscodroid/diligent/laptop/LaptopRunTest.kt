package com.vscodroid.diligent.laptop

import com.vscodroid.diligent.run.RunProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The scripts through a real bash and a real Git, with a stand-in `ssh` on
 * PATH that runs the remote command with `sh -c` in a fake laptop home, the
 * way sshd hands it to the login shell. A host named `down` answers as an
 * unreachable laptop does.
 */
class LaptopRunTest {

    @TempDir
    lateinit var tmp: File

    private lateinit var laptopHome: File
    private lateinit var bin: File
    private lateinit var project: File

    private val hostileName = "Dita's \"Mac\" \$(touch pwned) `x`"
    private val machine = Machine(hostileName, "mac.local", "me", MachineOs.MacOs)
    private val messages = LaptopMessages(
        unreachable = "Can't reach $hostileName. Is it awake and on the same network or Tailscale?",
        keyRefused = "refused",
        hostKeyChanged = "host key",
        noGit = "no git",
    )
    private val run = LaptopRun(machine, "My App'; rm -rf ~", messages)

    @BeforeEach
    fun setUp() {
        assumeTrue(File("/bin/bash").canExecute() && hasGit(), "bash and git are needed")
        laptopHome = File(tmp, "laptop home").apply { mkdirs() }
        bin = File(tmp, "bin").apply { mkdirs() }
        project = File(tmp, "it's a \$project").apply { mkdirs() }
        File(bin, "ssh").apply {
            writeText(
                """
                #!/bin/bash
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -o|-L) shift 2 ;;
                    -t|-tt) shift ;;
                    *) break ;;
                  esac
                done
                target="$1"; shift
                if [ "${'$'}{target#*@}" = down ]; then
                  echo "ssh: connect to host down port 22: Operation timed out" >&2
                  exit 255
                fi
                cd "${'$'}LAPTOP_HOME" && HOME="${'$'}LAPTOP_HOME" exec /bin/sh -c "$*"
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
    }

    private fun hasGit() = try {
        ProcessBuilder("git", "--version").start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun bash(script: String, cwd: File = tmp): Pair<Int, List<String>> {
        val p = ProcessBuilder("/bin/bash", "-c", script).directory(cwd).redirectErrorStream(true)
        p.environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        p.environment()["LAPTOP_HOME"] = laptopHome.path
        p.environment()["SHELL"] = "/bin/sh"
        p.environment()["HOME"] = File(tmp, "phone home").apply { mkdirs() }.path
        p.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readLines()
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS))
        return proc.exitValue() to out
    }

    private val profile = RunProfile(
        name = "dev",
        command = "pwd; echo \"\$GREETING\"",
        port = 3000,
        where = "laptop:x",
        env = mapOf("GREETING" to "it's \"quoted\" \$(nope) `no`"),
        prepare = "echo prepared >> prep.log",
    )

    private val work get() = File(laptopHome, "diligent-work/${run.remoteName}")

    @Test
    fun `the project name on the laptop is cut to safe characters`() {
        assertEquals("my-app-rm-rf", run.remoteName)
    }

    @Test
    fun `sync pushes a snapshot, checks it out and prepares only when the lock file changed`() {
        File(project, "app.txt").writeText("one\n")
        File(project, "old.txt").writeText("old\n")
        File(project, "package-lock.json").writeText("{}\n")
        File(project, "node_modules").mkdirs()
        File(project, "node_modules/dep.js").writeText("x\n")

        val (code, out) = bash(run.sync(project.path, profile))
        assertEquals(0, code, out.joinToString("\n"))
        assertEquals("one\n", File(work, "app.txt").readText())
        assertFalse(File(work, "node_modules").exists(), "a fresh repository leaves dependencies out")
        assertEquals(listOf("prepared"), File(work, "prep.log").readLines())
        assertTrue(File(laptopHome, "diligent-sync/my-app-rm-rf.git").isDirectory)
        val (refCode, _) = bash("git rev-parse --verify -q refs/diligent/snapshots", project)
        assertEquals(0, refCode)
        assertFalse(File(tmp, "pwned").exists() || File(project, "pwned").exists() || File(laptopHome, "pwned").exists())

        // Edits and deletions arrive; the lock file did not change, so no prepare.
        File(project, "app.txt").writeText("two\n")
        File(project, "old.txt").delete()
        val (code2, out2) = bash(run.sync(project.path, profile))
        assertEquals(0, code2, out2.joinToString("\n"))
        assertEquals("two\n", File(work, "app.txt").readText())
        assertFalse(File(work, "old.txt").exists())
        assertEquals(listOf("prepared"), File(work, "prep.log").readLines())

        // A changed lock file prepares again.
        File(project, "package-lock.json").writeText("{\"v\":2}\n")
        val (code3, out3) = bash(run.sync(project.path, profile))
        assertEquals(0, code3, out3.joinToString("\n"))
        assertEquals(listOf("prepared", "prepared"), File(work, "prep.log").readLines())

        // The user's own index is untouched: nothing was staged.
        val (_, staged) = bash("git diff --cached --name-only", project)
        assertEquals(emptyList<String>(), staged)
    }

    @Test
    fun `an unreachable laptop gives the exact sentence`() {
        File(project, "a.txt").writeText("a\n")
        val down = LaptopRun(machine.copy(host = "down"), "app", messages)
        val (code, out) = bash(down.sync(project.path, profile))
        assertEquals(255, code)
        assertEquals(messages.unreachable, out.last())
        val (code2, out2) = bash(down.command(profile, 3000))
        assertEquals(255, code2)
        assertEquals(messages.unreachable, out2.last())
    }

    @Test
    fun `the run command reaches the work folder with its environment`() {
        File(project, "a.txt").writeText("a\n")
        assertEquals(0, bash(run.sync(project.path, profile.copy(prepare = null))).first)
        val (code, out) = bash(run.command(profile, 3001))
        assertEquals(0, code, out.joinToString("\n"))
        assertEquals(listOf(work.canonicalPath, "it's \"quoted\" \$(nope) `no`"), out.map { File(it).let { f -> if (f.isAbsolute) f.canonicalPath else it } })
    }

    @Test
    fun `the run command forwards the port`() {
        val c = run.command(profile, 3001)
        assertTrue(c.startsWith("ssh -o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new -tt -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -L 3001:localhost:3000 'me@mac.local' "), c)
        assertFalse(run.command(profile.copy(port = null), null).contains(" -L "))
    }
}
