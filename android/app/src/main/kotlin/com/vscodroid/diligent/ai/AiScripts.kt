package com.vscodroid.diligent.ai

import com.vscodroid.diligent.process.BundledShell.Companion.quote
import com.vscodroid.diligent.run.RunConfig
import com.vscodroid.diligent.run.RunSession
import com.vscodroid.diligent.snapshot.GitSnapshots

/**
 * The terminal AI agents the AI screen runs in tmux. [id] names the session,
 * [command] is what runs (both are words the terminal knows: `claude` is the
 * bash function VSCodroid writes for the Claude Code extension's CLI, `agy` is
 * Google's Antigravity CLI, which the user installs).
 */
enum class AiAgent(val id: String, val command: String) {
    Claude(id = "claude", command = "claude"),
    Antigravity(id = "agy", command = "agy"),
}

/** The tmux session of [agent] in a project: `diligent-ai-<agent>-<project>`. */
object AiSession {
    const val PREFIX = "diligent-ai-"

    /** Cleaned as [RunSession] cleans run sessions: lowercase letters, digits, `_` and `-`. */
    fun name(agent: AiAgent, project: String): String = PREFIX + agent.id + "-" + RunSession.part(project, "project")
}

/** What `<tool> --version` said. */
sealed interface AiToolStatus {
    data object Unknown : AiToolStatus
    data object NotInstalled : AiToolStatus
    data class Installed(val version: String) : AiToolStatus

    /** It is there but did not answer `--version` cleanly: [detail] is its first line. */
    data class Broken(val code: Int, val detail: String) : AiToolStatus
}

/** What the AI screen knows about a session from tmux and its record. */
data class AiSessionRecord(val project: String, val commit: String, val startedMs: Long)

/**
 * The shell scripts behind the AI screen, each a `bash -c` script for
 * [com.vscodroid.diligent.process.BundledShell]. Every value goes through
 * [quote]; the agent's command is one of [AiAgent]'s fixed words.
 *
 * A session's record, `~/.diligent/ai/<session>.json`, holds the snapshot
 * taken before it started (`{"project":..., "commit":..., "started":...}`), so
 * "Undo changes since this session started" still works after the session
 * ends or the app restarts. Credentials are never read: the scripts only run
 * the tools' own `--version`.
 */
class AiScripts(
    private val homeDir: String,
    private val shell: String,
    private val execPreload: String?,
) {
    val recordDir: String get() = "$homeDir/.diligent/ai"

    fun recordPath(session: String) = "$recordDir/$session.json"

    /** `<tool> --version`: prints `code <n>` and then up to three lines of what it said. */
    fun status(agent: AiAgent): String = listOf(
        "if ! type ${agent.command} >/dev/null 2>&1; then echo 'code 127'; exit 0; fi",
        "out=\$(${agent.command} --version 2>&1 </dev/null); code=\$?",
        "printf 'code %s\\n' \"\$code\"",
        "printf '%s\\n' \"\$out\" | head -n 3",
    ).joinToString("\n")

    /**
     * Starts [agent] in [session] in [projectDir], recording [commit] as the
     * snapshot it started after. Does nothing when the session is already
     * running (the record of the earlier start stays). Exits 127 when tmux is
     * missing and 2 when the folder is gone. When the agent exits, the session
     * waits for Enter, so its last words can still be read on Attach.
     */
    fun start(session: String, projectDir: String, agent: AiAgent, commit: String, nowMs: Long): String {
        require(GitSnapshots.isCommit(commit)) { "not a snapshot: $commit" }
        val target = quote("=$session")
        val record = "{\"project\":" + RunConfig.jsonString(projectDir) + ",\"commit\":\"" + commit + "\",\"started\":" + nowMs + "}"
        return listOf(
            "command -v tmux >/dev/null 2>&1 || { echo 'tmux was not found'; exit 127; }",
            "[ -d ${quote(projectDir)} ] || { echo 'The project folder is missing'; exit 2; }",
            "if tmux has-session -t $target 2>/dev/null; then exit 0; fi",
            "mkdir -p ${quote(recordDir)} || exit 1",
            "printf '%s\\n' ${quote(record)} > ${quote(recordPath(session))} || exit 1",
            "exec tmux new-session -d -s ${quote(session)} -c ${quote(projectDir)} ${quote(shell)} -lc ${quote(inner(projectDir, agent))}",
        ).joinToString("\n")
    }

    internal fun inner(projectDir: String, agent: AiAgent): String = buildList {
        add("cd ${quote(projectDir)} || exit 2")
        if (execPreload != null) add("[ -f ${quote(execPreload)} ] && export LD_PRELOAD=${quote(execPreload)}")
        add(agent.command)
        add("printf '\\n[diligent] %s ended (exit %s). Press Enter to close.\\n' ${quote(agent.command)} \"\$?\"")
        add("read -r _")
    }.joinToString("\n")

    /** Ends [session]. Its record stays, for Undo. */
    fun stop(session: String): String = "tmux kill-session -t ${quote("=$session")} 2>/dev/null; true"

    /** The `diligent-ai-` sessions alive now, one `session <name>` line each. */
    fun list(): String = listOf(
        "command -v tmux >/dev/null 2>&1 || { echo 'notmux'; exit 0; }",
        "tmux list-sessions -F 'session #{session_name}' 2>/dev/null",
        "true",
    ).joinToString("\n")

    /** The tmux command that attaches the terminal to [session]. */
    fun attachCommand(session: String): String = "tmux attach -t ${quote("=$session")}"

    companion object {
        private val VERSION = Regex("""\d+\.\d+(?:\.\d+)?(?:[-+.][0-9A-Za-z.]+)?""")

        /** Reads what [status] printed. */
        fun parseStatus(lines: List<String>): AiToolStatus {
            val codeLine = lines.indexOfFirst { it.startsWith("code ") }
            if (codeLine < 0) return AiToolStatus.Unknown
            val code = lines[codeLine].removePrefix("code ").trim().toIntOrNull() ?: return AiToolStatus.Unknown
            val said = lines.drop(codeLine + 1).map { it.trim() }.filter { it.isNotEmpty() }
            val version = said.firstNotNullOfOrNull { VERSION.find(it)?.value }
            return when {
                code == 127 || code == 126 -> AiToolStatus.NotInstalled
                code == 0 && version != null -> AiToolStatus.Installed(version)
                else -> AiToolStatus.Broken(code, said.firstOrNull().orEmpty())
            }
        }

        /** Reads what [list] printed: the AI sessions alive, or null when tmux is missing. */
        fun parseList(lines: List<String>): Set<String>? {
            if (lines.any { it == "notmux" }) return null
            return lines.filter { it.startsWith("session $PREFIX_LINE") }.map { it.removePrefix("session ") }.toSet()
        }

        private const val PREFIX_LINE = AiSession.PREFIX

        private val RECORD_COMMIT = Regex("\"commit\"\\s*:\\s*\"([0-9a-f]{40,64})\"")
        private val RECORD_STARTED = Regex("\"started\"\\s*:\\s*(\\d+)")

        /** A session record, or null when it is missing or not one of ours. */
        fun parseRecord(text: String, project: String): AiSessionRecord? {
            val commit = RECORD_COMMIT.find(text)?.groupValues?.get(1) ?: return null
            // The project is checked so a record of another folder with the same name is not used.
            if (!text.contains("\"project\":" + RunConfig.jsonString(project))) return null
            val started = RECORD_STARTED.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return AiSessionRecord(project, commit, started)
        }
    }
}
