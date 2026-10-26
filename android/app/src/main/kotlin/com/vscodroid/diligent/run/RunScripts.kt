package com.vscodroid.diligent.run

import com.vscodroid.diligent.process.BundledShell.Companion.quote

/**
 * The tmux session a profile runs in: `diligent-<project>-<profile>`.
 *
 * tmux refuses `.` and `:` in a session name (they separate window and pane in
 * a target), and a name with spaces or quotes is a trap for anyone typing
 * `tmux attach -t ...` in the terminal. So each part is cut down to lowercase
 * letters, digits, `_` and `-`, which also makes the name safe as a file name
 * for the log.
 */
object RunSession {
    const val PREFIX = "diligent-"
    private const val MAX_PART = 32

    fun name(project: String, profile: String): String = PREFIX + part(project, "project") + "-" + part(profile, "run")

    internal fun part(raw: String, fallback: String): String {
        val cleaned = raw.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_PART)
            .trim('-')
        return cleaned.ifEmpty { fallback }
    }
}

/** What tmux and the status file say about a session. */
sealed interface RunStatus {
    /** Never started, or its records are gone. */
    data object Idle : RunStatus
    data object Running : RunStatus

    /** The command ended by itself with [code]; 0 is a clean finish. */
    data class Exited(val code: Int) : RunStatus

    /** Stopped from Diligent. */
    data object Stopped : RunStatus

    /** No `tmux` on PATH: the bundled tools are missing or broken. */
    data object NoTmux : RunStatus
}

/** One poll's answer: which `diligent-` sessions are alive, how the watched one is, and its log tail. */
data class RunPoll(val sessions: Set<String>, val status: RunStatus, val log: List<String>)

/**
 * The exact shell scripts Diligent runs to start, stop and watch a profile,
 * each a `bash -c` script for [com.vscodroid.diligent.process.BundledShell].
 *
 * **Why tmux.** A dev server must outlive the screen that started it, and the
 * app's own process too: tmux keeps it running, and the terminal can attach to
 * the same session (`tmux attach -t diligent-...`) because both use the same
 * `TMUX_TMPDIR`.
 *
 * **The log.** The command runs as `{ cmd } 2>&1 | tee -ia <log>` inside the
 * session, not through `pipe-pane`: pipe-pane copies the terminal's bytes,
 * cursor movement and colour codes included, where tee gets the program's own
 * output lines. The pane shows the same lines live for anyone who attaches.
 * `tee -i` ignores the Ctrl-C that Stop sends, so the last lines a server
 * prints while shutting down still reach the log. Output going to a pipe makes
 * Python buffer it, so `PYTHONUNBUFFERED=1` is set unless the profile says
 * otherwise.
 *
 * **The status file** (`<session>.status`, beside the log) answers what tmux
 * cannot after a session is gone: `exit N` when the command ended by itself,
 * `stopped` when Stop ended it. A session that is alive is running whatever
 * the file says.
 *
 * **Quoting.** Every value (paths, names, env values, the whole inner script)
 * goes through [quote]. The profile's command is the one thing that is not:
 * it is shell text the user wrote to be run. Env names are checked by
 * [RunConfig.isEnvName] before they get here and are refused again below.
 *
 * Sessions are always named with tmux's `=` prefix, which means "exactly this
 * name": tmux otherwise also accepts a unique prefix, so `has-session -t
 * diligent-app-dev` would answer yes for a running `diligent-app-dev2`.
 *
 * @param homeDir the terminal's HOME; logs go in `~/.diligent/logs`.
 * @param shell the bash to run the command with (the terminal's own).
 * @param execPreload the exec interceptor the terminal preloads, exported for
 *   the command when the file is there, so a run behaves as the same command
 *   typed into the terminal does (`./node_modules/.bin/...` included).
 */
class RunScripts(
    private val homeDir: String,
    private val shell: String,
    private val execPreload: String?,
) {
    val logDir: String get() = "$homeDir/.diligent/logs"

    fun logPath(session: String) = "$logDir/$session.log"
    fun statusPath(session: String) = "$logDir/$session.status"

    /**
     * Where the supervisor (the `diligent.diligent-core` extension) finds the
     * runs to keep alive: one `<session>.sh` per run started from Diligent.
     */
    val runsDir: String get() = "$homeDir/.diligent/runs"

    fun runScriptPath(session: String) = "$runsDir/$session.sh"

    /**
     * Starts [profile] in [session] with [projectDir] as its working directory.
     * Does nothing when the session is already running. Exits 127 when tmux is
     * missing and 2 when the folder is gone.
     *
     * A new session also leaves its own launch script, [launch], at
     * [runScriptPath], so the supervisor restarts a run with `bash <that file>`
     * and never builds a start command of its own: one place decides how a run
     * starts, with the quoting below.
     */
    fun start(session: String, projectDir: String, profile: RunProfile): String =
        lines(session, projectDir, profile, save = true).joinToString("\n")

    /**
     * The file the supervisor runs to restart [session]: the start script
     * without the step that writes this file, under a first line
     * `# diligent-run: {"dir":...,"name":...,"port":...}` naming the project
     * folder, the profile and its port. The JSON is written by
     * [RunConfig.jsonString], which escapes every line break, so a name cannot
     * end the comment and become a command.
     */
    fun launch(session: String, projectDir: String, profile: RunProfile): String {
        val meta = buildString {
            append("{\"dir\":").append(RunConfig.jsonString(projectDir))
            append(",\"name\":").append(RunConfig.jsonString(profile.name))
            if (profile.port != null) append(",\"port\":").append(profile.port)
            append('}')
        }
        return (listOf("$RUN_META$meta") + lines(session, projectDir, profile, save = false)).joinToString("\n") + "\n"
    }

    private fun lines(session: String, projectDir: String, profile: RunProfile, save: Boolean): List<String> {
        val log = quote(logPath(session))
        val target = quote("=$session")
        return buildList {
            add("command -v tmux >/dev/null 2>&1 || { echo 'tmux was not found'; exit 127; }")
            add("[ -d ${quote(projectDir)} ] || { echo 'The project folder is missing'; exit 2; }")
            add("mkdir -p ${quote(logDir)} || exit 1")
            add("if tmux has-session -t $target 2>/dev/null; then exit 0; fi")
            add("rm -f ${quote(statusPath(session))}")
            if (save) {
                add("mkdir -p ${quote(runsDir)} && printf '%s' ${quote(launch(session, projectDir, profile))} > ${quote(runScriptPath(session))}")
            }
            add("printf '%s\\n' ${quote("[diligent] $ " + profile.command)} > $log")
            add(
                "exec tmux new-session -d -s ${quote(session)} -c ${quote(projectDir)} " +
                    "${quote(shell)} -lc ${quote(inner(session, projectDir, profile))}",
            )
        }
    }

    /**
     * What runs inside the session: environment, the command into the log,
     * then the exit code into the status file and, as the log's last line,
     * `DILIGENT_EXIT:<code>`. A log that ends without that line belongs to a
     * session that was killed (by Android, or by Stop) rather than one whose
     * command ended, which is how the supervisor tells the two apart.
     */
    internal fun inner(session: String, projectDir: String, profile: RunProfile): String = buildList {
        // A login shell may cd elsewhere in .bash_profile; the command runs in the project.
        add("cd ${quote(projectDir)} || exit 2")
        add("export PYTHONUNBUFFERED=1")
        if (execPreload != null) add("[ -f ${quote(execPreload)} ] && export LD_PRELOAD=${quote(execPreload)}")
        profile.env.forEach { (k, v) ->
            require(RunConfig.isEnvName(k)) { "not an environment variable name: $k" }
            add("export $k=${quote(v)}")
        }
        // The newline before the brace lets the command end in a comment or a `;`.
        add("{ ${profile.command}\n} 2>&1 | tee -ia ${quote(logPath(session))}")
        add("diligent_code=\${PIPESTATUS[0]}")
        add("printf 'exit %s\\n' \"\$diligent_code\" > ${quote(statusPath(session))}")
        add("printf '$EXIT_MARK%s\\n' \"\$diligent_code\" >> ${quote(logPath(session))}")
    }.joinToString("\n")

    /**
     * Stops [session]: Ctrl-C first, as a person at the terminal would, so a
     * dev server can shut down cleanly; then, if it is still there after about
     * three seconds, kills the session. Records `stopped` either way.
     */
    fun stop(session: String): String {
        val target = quote("=$session")
        return listOf(
            "if tmux has-session -t $target 2>/dev/null; then",
            "  tmux send-keys -t ${quote("=$session:")} C-c 2>/dev/null",
            "  i=0",
            "  while [ \"\$i\" -lt $STOP_WAIT_STEPS ] && tmux has-session -t $target 2>/dev/null; do sleep 0.1; i=\$((i + 1)); done",
            "  tmux kill-session -t $target 2>/dev/null",
            "fi",
            "mkdir -p ${quote(logDir)}",
            "printf 'stopped\\n' > ${quote(statusPath(session))}",
            "printf '%s\\n' '[diligent] Stopped' >> ${quote(logPath(session))}",
            "true",
        ).joinToString("\n")
    }

    /**
     * One poll: the `diligent-` sessions that are alive, the state of
     * [session], and, when [withLog], the last [LOG_LINES] lines of its log.
     * Read back with [parsePoll].
     */
    fun poll(session: String, withLog: Boolean): String {
        val target = quote("=$session")
        val lines = mutableListOf(
            "if ! command -v tmux >/dev/null 2>&1; then echo 'status notmux'; else",
            "  tmux list-sessions -F 'session #{session_name}' 2>/dev/null",
            "  if tmux has-session -t $target 2>/dev/null; then echo 'status running'",
            "  elif [ -f ${quote(statusPath(session))} ]; then printf 'status %s\\n' \"\$(head -n 1 ${quote(statusPath(session))})\"",
            "  else echo 'status none'; fi",
            "fi",
            "echo '$LOG_MARK'",
        )
        if (withLog) lines += "tail -n $LOG_LINES ${quote(logPath(session))} 2>/dev/null"
        lines += "true"
        return lines.joinToString("\n")
    }

    companion object {
        const val LOG_LINES = 200
        const val STOP_WAIT_STEPS = 30
        internal const val LOG_MARK = "@@diligent-log"

        /** The log's last line when the command ended by itself: `DILIGENT_EXIT:<code>`. */
        const val EXIT_MARK = "DILIGENT_EXIT:"

        /** The first line of a saved launch script, before its JSON. */
        const val RUN_META = "# diligent-run: "

        private val EXIT_LINE = Regex("""^DILIGENT_EXIT:-?\d+$""")

        /** Reads what [poll] printed. A garbled answer reads as idle with no log rather than failing. */
        fun parsePoll(lines: List<String>): RunPoll {
            val mark = lines.indexOf(LOG_MARK)
            val head = if (mark < 0) lines else lines.subList(0, mark)
            // The exit marker is for the supervisor; the screen shows the exit in its status.
            val log = if (mark < 0) emptyList() else lines.subList(mark + 1, lines.size).map(::cleanLogLine).filterNot { EXIT_LINE.matches(it) }
            val sessions = head.filter { it.startsWith("session " + RunSession.PREFIX) }.map { it.removePrefix("session ") }.toSet()
            val status = head.lastOrNull { it.startsWith("status ") }?.removePrefix("status ")?.trim()
            return RunPoll(sessions, statusOf(status), log.takeLast(LOG_LINES))
        }

        internal fun statusOf(word: String?): RunStatus = when {
            word == null || word == "none" -> RunStatus.Idle
            word == "running" -> RunStatus.Running
            word == "stopped" -> RunStatus.Stopped
            word == "notmux" -> RunStatus.NoTmux
            word.startsWith("exit ") -> word.removePrefix("exit ").trim().toIntOrNull()?.let { RunStatus.Exited(it) }
                ?: RunStatus.Idle
            else -> RunStatus.Idle
        }

        private val ANSI = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\)|[@-Z\\-_])""")

        /**
         * A log line as text: colour and cursor codes removed (tools that
         * force colour still emit them into a pipe), and of a carriage-return
         * progress line only what was drawn last.
         */
        fun cleanLogLine(line: String): String {
            val noAnsi = ANSI.replace(line, "")
            val lastCr = noAnsi.trimEnd('\r').lastIndexOf('\r')
            return (if (lastCr >= 0) noAnsi.substring(lastCr + 1) else noAnsi).trimEnd('\r')
        }
    }
}
