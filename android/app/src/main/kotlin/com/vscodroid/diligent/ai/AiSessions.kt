package com.vscodroid.diligent.ai

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vscodroid.diligent.process.BundledShell
import com.vscodroid.diligent.snapshot.SnapshotResult
import com.vscodroid.diligent.snapshot.SnapshotRunner
import com.vscodroid.util.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** One agent's row on the AI screen. */
data class AiAgentState(
    val status: AiToolStatus = AiToolStatus.Unknown,
    val running: Boolean = false,
    /** The snapshot its latest session in this project started after, if any. */
    val record: AiSessionRecord? = null,
)

/** Something the AI screen says once, after an action. */
sealed interface AiNotice {
    data object NoTmux : AiNotice
    data class StartFailed(val lines: List<String>) : AiNotice
    data object SnapshotFailed : AiNotice
    data object Undone : AiNotice
    data object UndoFailed : AiNotice
}

data class AiUiState(
    val projectDir: String? = null,
    val projectName: String? = null,
    val agents: Map<AiAgent, AiAgentState> = AiAgent.entries.associateWith { AiAgentState() },
    /** The agent an action is in progress for. */
    val busy: AiAgent? = null,
    val notice: AiNotice? = null,
) {
    fun of(agent: AiAgent): AiAgentState = agents[agent] ?: AiAgentState()
}

/**
 * The AI screen's work: each agent's `--version`, its tmux session in the open
 * project, starting one after a snapshot, stopping it, and putting the files
 * back to that snapshot. State lives in tmux and in `~/.diligent/ai`, so a
 * session started before the screen (or the app) was last open is found again.
 *
 * Main-thread confined like ProjectRunner: state is written on [scope]'s
 * dispatcher, the shell work runs on IO through [BundledShell].
 */
class AiSessions(private val context: Context, private val scope: CoroutineScope) {

    var state by mutableStateOf(AiUiState())
        private set

    private val bundled by lazy { BundledShell(context) }
    private val snapshots by lazy { SnapshotRunner(bundled) }
    private val home by lazy { File(Environment.getHomeDir(context)) }
    private val scripts by lazy {
        AiScripts(
            homeDir = Environment.getHomeDir(context),
            shell = Environment.getTerminalShellPath(context),
            execPreload = Environment.getExecPreloadPath(context),
        )
    }

    /** The editor's open folder (a `.code-workspace` file means its folder). */
    fun setProject(folder: String?, name: String?) {
        val dir = folder?.let { if (it.endsWith(".code-workspace")) File(it).parent else it }
        if (dir == state.projectDir && name == state.projectName) return
        state = AiUiState(projectDir = dir, projectName = name ?: dir?.let { File(it).name })
        refresh()
    }

    fun session(agent: AiAgent): String? = state.projectName?.let { AiSession.name(agent, it) }

    fun dismissNotice() {
        state = state.copy(notice = null)
    }

    /** Reads every agent's version and which sessions are alive. */
    fun refresh() {
        scope.launch {
            val statuses = AiAgent.entries.associateWith { agent ->
                AiScripts.parseStatus(runOrEmpty(scripts.status(agent)))
            }
            val alive = AiScripts.parseList(runOrEmpty(scripts.list()))
            val dir = state.projectDir
            val records = AiAgent.entries.associateWith { agent -> readRecord(agent, dir) }
            state = state.copy(
                agents = AiAgent.entries.associateWith { agent ->
                    AiAgentState(
                        status = statuses.getValue(agent),
                        running = session(agent)?.let { alive?.contains(it) } == true,
                        record = records.getValue(agent),
                    )
                },
                notice = if (alive == null) AiNotice.NoTmux else state.notice,
            )
        }
    }

    /**
     * Snapshot first (making the folder a Git repository if it is not one,
     * which the user agreed to on the confirm sheet), then the session.
     */
    fun start(agent: AiAgent) {
        val dir = state.projectDir ?: return
        val session = session(agent) ?: return
        if (state.busy != null) return
        state = state.copy(busy = agent, notice = null)
        scope.launch {
            val snap = snapshots.take(File(dir), "before ${agent.id} session", initIfNeeded = true)
            if (snap !is SnapshotResult.Taken) {
                if (snap is SnapshotResult.Failed) Log.w(TAG, "Snapshot failed: ${snap.lines.takeLast(3)}")
                state = state.copy(busy = null, notice = AiNotice.SnapshotFailed)
                return@launch
            }
            val result = try {
                bundled.run(scripts.start(session, dir, agent, snap.commit, System.currentTimeMillis()), home)
            } catch (e: IOException) {
                Log.w(TAG, "Start failed", e)
                null
            }
            state = state.copy(
                busy = null,
                notice = when {
                    result == null -> AiNotice.StartFailed(emptyList())
                    result.exitCode == 127 -> AiNotice.NoTmux
                    !result.succeeded -> AiNotice.StartFailed(result.lastLines.takeLast(5))
                    else -> null
                },
            )
            refresh()
        }
    }

    fun stop(agent: AiAgent) {
        val session = session(agent) ?: return
        if (state.busy != null) return
        state = state.copy(busy = agent)
        scope.launch {
            runOrEmpty(scripts.stop(session))
            state = state.copy(busy = null)
            refresh()
        }
    }

    /** Puts the files back to the snapshot [agent]'s session started after. */
    fun undo(agent: AiAgent) {
        val dir = state.projectDir ?: return
        val record = state.of(agent).record ?: return
        if (state.busy != null) return
        state = state.copy(busy = agent, notice = null)
        scope.launch {
            val ok = snapshots.restore(File(dir), record.commit)
            state = state.copy(busy = null, notice = if (ok) AiNotice.Undone else AiNotice.UndoFailed)
        }
    }

    private suspend fun runOrEmpty(script: String): List<String> = try {
        bundled.run(script, home).lastLines
    } catch (e: IOException) {
        Log.w(TAG, "AI script failed", e)
        emptyList()
    }

    private suspend fun readRecord(agent: AiAgent, dir: String?): AiSessionRecord? {
        if (dir == null) return null
        val session = session(agent) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                File(scripts.recordPath(session)).takeIf { it.isFile }?.readText()?.let { AiScripts.parseRecord(it, dir) }
            } catch (e: IOException) {
                Log.w(TAG, "Reading the session record failed", e)
                null
            }
        }
    }

    private companion object {
        const val TAG = "DiligentAi"
    }
}
