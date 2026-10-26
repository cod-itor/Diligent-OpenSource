package com.vscodroid.diligent.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

class RunScriptsTest {

    private val home = "/data/user/0/app/files/home"
    private val bash = "/data/user/0/app/files/usr/bin/bash"
    private val preload = "/data/user/0/app/files/usr/lib/libtermux-exec.so"
    private val scripts = RunScripts(home, bash, preload)

    @Nested
    inner class SessionNames {
        @Test
        fun `plain names`() {
            assertEquals("diligent-my-app-dev", RunSession.name("my-app", "dev"))
        }

        @Test
        fun `dots, colons, spaces, quotes and capitals are made safe`() {
            assertEquals("diligent-my-app-v1-2-web-dev", RunSession.name("My App v1.2", "web:dev"))
            assertEquals("diligent-it-s-a-test-x", RunSession.name("it's \"a\" test", "\$x"))
            assertEquals("diligent-hello_world-dev", RunSession.name("hello_world", "dev"))
        }

        @Test
        fun `a name of nothing safe still names something`() {
            assertEquals("diligent-project-run", RunSession.name("日本語", "..."))
            assertEquals("diligent-project-run", RunSession.name("", ""))
        }

        @Test
        fun `long names are cut without a trailing dash`() {
            val name = RunSession.name("a".repeat(31) + "-bcdef", "dev")
            assertEquals("diligent-" + "a".repeat(31) + "-dev", name)
        }

        @Test
        fun `the name has only characters tmux and a file name accept`() {
            val name = RunSession.name("we/ird .:name\t\n\$(id)`x`", "p r;o|f&i<l>e")
            assertTrue(name.matches(Regex("[a-z0-9_-]+")), name)
        }
    }

    @Nested
    inner class Paths {
        @Test
        fun `logs live in the terminal's home`() {
            assertEquals("$home/.diligent/logs", scripts.logDir)
            assertEquals("$home/.diligent/logs/diligent-a-dev.log", scripts.logPath("diligent-a-dev"))
            assertEquals("$home/.diligent/logs/diligent-a-dev.status", scripts.statusPath("diligent-a-dev"))
        }
    }

    @Nested
    inner class ScriptText {
        private val session = "diligent-my-app-dev"
        private val dir = "/home/projects/My 'App' \"x\""
        private val profile = RunProfile("dev", "npm run dev -- --webpack", 3000, env = mapOf("NODE_ENV" to "it's \$HOME"))

        @Test
        fun `start uses a detached session in the project folder, with the exact name`() {
            val start = scripts.start(session, dir, profile)
            val log = "'$home/.diligent/logs/diligent-my-app-dev.log'"
            val dirQ = "'/home/projects/My '\\''App'\\'' \"x\"'"
            assertEquals(
                listOf(
                    "command -v tmux >/dev/null 2>&1 || { echo 'tmux was not found'; exit 127; }",
                    "[ -d $dirQ ] || { echo 'The project folder is missing'; exit 2; }",
                    "mkdir -p '$home/.diligent/logs' || exit 1",
                    "if tmux has-session -t '=diligent-my-app-dev' 2>/dev/null; then exit 0; fi",
                    "rm -f '$home/.diligent/logs/diligent-my-app-dev.status'",
                    "printf '%s\\n' '[diligent] \$ npm run dev -- --webpack' > $log",
                ),
                start.lines().take(6),
            )
            assertTrue(
                start.lines()[6].startsWith("exec tmux new-session -d -s 'diligent-my-app-dev' -c $dirQ '$bash' -lc '"),
                start.lines()[6],
            )
        }

        @Test
        fun `the inner script sets the environment, logs through tee and records the exit code`() {
            val inner = scripts.inner(session, dir, profile)
            assertEquals(
                """
                cd '/home/projects/My '\''App'\'' "x"' || exit 2
                export PYTHONUNBUFFERED=1
                [ -f '$preload' ] && export LD_PRELOAD='$preload'
                export NODE_ENV='it'\''s ${'$'}HOME'
                { npm run dev -- --webpack
                } 2>&1 | tee -ia '$home/.diligent/logs/diligent-my-app-dev.log'
                printf 'exit %s\n' "${'$'}{PIPESTATUS[0]}" > '$home/.diligent/logs/diligent-my-app-dev.status'
                """.trimIndent(),
                inner,
            )
        }

        @Test
        fun `without an exec interceptor there is no LD_PRELOAD line`() {
            assertFalse(RunScripts(home, bash, null).inner(session, dir, profile).contains("LD_PRELOAD"))
        }

        @Test
        fun `an env name that slipped past the config is refused, not run`() {
            val bad = profile.copy(env = mapOf("A;id" to "1"))
            assertThrows(IllegalArgumentException::class.java) { scripts.start(session, dir, bad) }
        }

        @Test
        fun `stop sends Ctrl-C, waits, then kills, and records stopped`() {
            assertEquals(
                """
                if tmux has-session -t '=diligent-my-app-dev' 2>/dev/null; then
                  tmux send-keys -t '=diligent-my-app-dev:' C-c 2>/dev/null
                  i=0
                  while [ "${'$'}i" -lt 30 ] && tmux has-session -t '=diligent-my-app-dev' 2>/dev/null; do sleep 0.1; i=${'$'}((i + 1)); done
                  tmux kill-session -t '=diligent-my-app-dev' 2>/dev/null
                fi
                mkdir -p '$home/.diligent/logs'
                printf 'stopped\n' > '$home/.diligent/logs/diligent-my-app-dev.status'
                printf '%s\n' '[diligent] Stopped' >> '$home/.diligent/logs/diligent-my-app-dev.log'
                true
                """.trimIndent(),
                scripts.stop(session),
            )
        }

        @Test
        fun `poll asks has-session for status and tails 200 lines only when asked`() {
            val withLog = scripts.poll(session, withLog = true)
            assertTrue(withLog.contains("tmux has-session -t '=diligent-my-app-dev'"))
            assertTrue(withLog.contains("tail -n 200 '$home/.diligent/logs/diligent-my-app-dev.log' 2>/dev/null"))
            assertFalse(scripts.poll(session, withLog = false).contains("tail"))
        }
    }

    @Nested
    inner class Parsing {
        @Test
        fun `a running session with its log`() {
            val poll = RunScripts.parsePoll(
                listOf("session diligent-a-dev", "session other", "session diligent-a-api", "status running", "@@diligent-log", "one", "two"),
            )
            assertEquals(setOf("diligent-a-dev", "diligent-a-api"), poll.sessions)
            assertEquals(RunStatus.Running, poll.status)
            assertEquals(listOf("one", "two"), poll.log)
        }

        @Test
        fun `statuses`() {
            assertEquals(RunStatus.Exited(1), RunScripts.statusOf("exit 1"))
            assertEquals(RunStatus.Exited(0), RunScripts.statusOf("exit 0"))
            assertEquals(RunStatus.Stopped, RunScripts.statusOf("stopped"))
            assertEquals(RunStatus.NoTmux, RunScripts.statusOf("notmux"))
            assertEquals(RunStatus.Idle, RunScripts.statusOf("none"))
            assertEquals(RunStatus.Idle, RunScripts.statusOf("exit "))
            assertEquals(RunStatus.Idle, RunScripts.statusOf("garbage"))
            assertEquals(RunStatus.Idle, RunScripts.statusOf(null))
        }

        @Test
        fun `a log line that looks like a marker after the mark is still log`() {
            val poll = RunScripts.parsePoll(listOf("status none", "@@diligent-log", "status running", "session diligent-x"))
            assertEquals(RunStatus.Idle, poll.status)
            assertEquals(emptySet<String>(), poll.sessions)
            assertEquals(listOf("status running", "session diligent-x"), poll.log)
        }

        @Test
        fun `no answer at all is idle`() {
            assertEquals(RunPoll(emptySet(), RunStatus.Idle, emptyList()), RunScripts.parsePoll(emptyList()))
        }

        @Test
        fun `colour codes and carriage-return progress are cleaned`() {
            assertEquals("ready in 300 ms", RunScripts.cleanLogLine("\u001B[32mready\u001B[39m in \u001B[1m300\u001B[22m ms"))
            assertEquals("100%", RunScripts.cleanLogLine("10%\r50%\r100%"))
            assertEquals("done", RunScripts.cleanLogLine("done\r"))
            assertEquals("title", RunScripts.cleanLogLine("\u001B]0;npm\u0007title"))
        }
    }

    /**
     * Runs the real scripts through a real bash, with a stand-in `tmux` that
     * runs the session's command in the foreground. That checks what string
     * comparisons cannot: that every layer of quoting survives bash, with a
     * project folder and values full of spaces, quotes and `$`.
     */
    @Nested
    inner class ThroughBash {
        @TempDir
        lateinit var tmp: File

        private val hostBash = File("/bin/bash")

        private fun run(script: String, home: File, bin: File): Int {
            val process = ProcessBuilder(hostBash.path, "-c", script)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "${bin.path}:/usr/bin:/bin"
                    environment()["HOME"] = home.path
                }
                .start()
            process.inputStream.readBytes()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            return process.exitValue()
        }

        private fun fakeTmux(bin: File) {
            bin.mkdirs()
            File(bin, "tmux").apply {
                writeText(
                    """
                    #!/bin/bash
                    sub=${'$'}1; shift
                    case "${'$'}sub" in
                      has-session) exit 1 ;;
                      list-sessions) exit 0 ;;
                      new-session)
                        while [ ${'$'}# -gt 0 ]; do
                          case "${'$'}1" in
                            -d) shift ;;
                            -s) shift 2 ;;
                            -c) dir=${'$'}2; shift 2 ;;
                            *) break ;;
                          esac
                        done
                        cd "${'$'}dir" && "${'$'}@" ;;
                    esac
                    exit 0

                    """.trimIndent(),
                )
                setExecutable(true)
            }
        }

        @Test
        fun `start, poll and stop survive hostile names and values`() {
            assumeTrue(hostBash.canExecute(), "no bash on this machine")
            val home = File(tmp, "home").apply { mkdirs() }
            val bin = File(tmp, "bin").also(::fakeTmux)
            val project = File(tmp, "my 'app' \"x\" \$(id)").apply { mkdirs() }
            File(project, "marker.txt").writeText("here")
            val scripts = RunScripts(home.path, hostBash.path, File(tmp, "no-such-preload.so").path)
            val session = RunSession.name(project.name, "dev")
            val profile = RunProfile(
                "dev",
                "printf '%s\\n' \"\$GREETING\"; cat marker.txt; echo; echo \"py=\$PYTHONUNBUFFERED\"; exit 3",
                env = mapOf("GREETING" to "hello 'world' \$(id) `id` \"q\" \\"),
            )

            assertEquals(0, run(scripts.start(session, project.path, profile), home, bin))

            val log = File(scripts.logPath(session)).readLines()
            assertEquals(
                listOf("[diligent] \$ ${profile.command}", "hello 'world' \$(id) `id` \"q\" \\", "here", "py=1"),
                log,
            )
            assertEquals("exit 3", File(scripts.statusPath(session)).readText().trim())

            val out = ProcessBuilder(hostBash.path, "-c", scripts.poll(session, withLog = true))
                .apply {
                    environment().clear()
                    environment()["PATH"] = "${bin.path}:/usr/bin:/bin"
                }
                .start()
            val poll = RunScripts.parsePoll(out.inputStream.bufferedReader().readLines())
            out.waitFor(30, TimeUnit.SECONDS)
            assertEquals(RunStatus.Exited(3), poll.status)
            assertEquals(log, poll.log)

            assertEquals(0, run(scripts.stop(session), home, bin))
            assertEquals("stopped", File(scripts.statusPath(session)).readText().trim())
            assertEquals("[diligent] Stopped", File(scripts.logPath(session)).readLines().last())
        }

        @Test
        fun `a missing project folder is refused before tmux is asked`() {
            assumeTrue(hostBash.canExecute(), "no bash on this machine")
            val home = File(tmp, "home").apply { mkdirs() }
            val bin = File(tmp, "bin").also(::fakeTmux)
            val scripts = RunScripts(home.path, hostBash.path, null)
            assertEquals(2, run(scripts.start("diligent-x-dev", File(tmp, "gone").path, RunProfile("dev", "true")), home, bin))
        }
    }
}
