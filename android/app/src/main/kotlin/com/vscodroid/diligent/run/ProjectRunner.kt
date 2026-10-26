package com.vscodroid.diligent.run

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vscodroid.R
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.TaskKeeper
import com.vscodroid.diligent.TaskUpdate
import com.vscodroid.diligent.laptop.LaptopMessages
import com.vscodroid.diligent.laptop.LaptopRun
import com.vscodroid.diligent.laptop.Machine
import com.vscodroid.diligent.laptop.Machines
import com.vscodroid.diligent.process.BundledShell
import com.vscodroid.diligent.process.ShellResult
import com.vscodroid.diligent.ui.components.StatusTone
import com.vscodroid.diligent.ui.components.TopBarStatus
import com.vscodroid.util.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Starts, stops and watches the open project's run profiles, and keeps the
 * shell's run state (`running`, `runStatus`, `previewPort`) in step for the
 * top bar and the action bar.
 *
 * Everything runs through [BundledShell], so a run has the terminal's own
 * tools and environment; the scripts themselves are [RunScripts]'. The state
 * lives in tmux and in two files beside the log, never only in memory: a
 * session started yesterday, or from another visit to the screen, is found by
 * the next poll. That is also why there is no "started by us" flag anywhere.
 *
 * Polling costs a bash process a second, so it runs only while something
 * [watch]es: the Run screen (status and log) or the action bar (status
 * alone). Main-thread confined: every public call and every state write
 * happens on [scope]'s main dispatcher; only the shell work hops to IO.
 */
class ProjectRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val shell: MobileShell,
    private val notes: RunNotes,
    private val openFile: (String) -> Unit,
    private val openPreviewOn: (port: Int, title: String?) -> Unit,
) : RunControl {

    override var state by mutableStateOf(RunUiState())
        private set

    private val bundled by lazy { BundledShell(context) }
    private val home by lazy { File(Environment.getHomeDir(context)) }
    private val scripts by lazy {
        RunScripts(
            homeDir = Environment.getHomeDir(context),
            shell = Environment.getTerminalShellPath(context),
            execPreload = Environment.getExecPreloadPath(context),
        )
    }

    /** The profile the user picked on the screen, for this project; null lets [RunSelection] choose. */
    private var chosen: String? = null

    private var watchers = 0
    private var logWatchers = 0
    private var pollLoop: Job? = null
    private var loadJob: Job? = null

    /** The laptops in machines.json, read with the profiles; the offload hint offers the first. */
    private var machines: List<Machine> = emptyList()

    // What the offload hint counts, per session, while the screen or action bar watches.
    private val lowMemoryKills = HashMap<String, Int>()
    private val runningSince = HashMap<String, Long>()
    private val offloadDismissed = HashSet<String>()
    private var lastTasks: TaskUpdate? = null

    /**
     * The editor opened [folder] (null: none). A `.code-workspace` file runs
     * from its own folder. Reloads profiles and forgets the previous project's
     * pick; a session of the previous project keeps running in tmux.
     */
    fun setProject(folder: String?) {
        val dir = folder?.let { if (it.endsWith(".code-workspace")) File(it).parent else it }
        if (dir == state.projectDir && state.config != RunConfigState.Loading) return
        chosen = null
        loadJob?.cancel()
        state = RunUiState(projectDir = dir, projectName = shell.projectName, config = if (dir == null) RunConfigState.Missing(null) else RunConfigState.Loading)
        publish()
        if (dir != null) reload()
    }

    private fun reload() {
        val dir = state.projectDir ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            val config = withContext(Dispatchers.IO) { RunFiles.load(File(dir)) }
            machines = withContext(Dispatchers.IO) { Machines.load(home) }
            if (state.projectDir != dir) return@launch
            val profiles = (config as? RunConfigState.Loaded)?.config?.profiles.orEmpty()
            val active = RunSelection.pick(profiles, state.running, chosen, state.active)
            state = state.copy(config = config, active = active, saveFailed = false)
            refreshNote()
            poll(withLog = logWatchers > 0)
        }
    }

    override suspend fun watch(withLog: Boolean) {
        watchers++
        if (withLog) logWatchers++
        // A laptop added on the Laptop screen counts from the next look at Run.
        scope.launch { machines = withContext(Dispatchers.IO) { Machines.load(home) } }
        ensurePolling()
        try {
            awaitCancellation()
        } finally {
            watchers--
            if (withLog) logWatchers--
        }
    }

    private fun ensurePolling() {
        if (pollLoop?.isActive == true) return
        pollLoop = scope.launch {
            while (isActive && watchers > 0) {
                poll(withLog = logWatchers > 0)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** One look at tmux and the log. Results for a project or profile no longer shown are dropped. */
    private suspend fun poll(withLog: Boolean) {
        val dir = state.projectDir ?: return
        val profiles = state.profiles
        val active = state.activeProfile ?: return
        val session = sessionOf(active)
        val lines = try {
            bundled.run(scripts.poll(session, withLog), home, keepLines = POLL_KEEP_LINES).lastLines
        } catch (e: IOException) {
            Log.w(TAG, "Run status poll failed", e)
            return
        }
        if (state.projectDir != dir || state.active != active.name) return
        val result = RunScripts.parsePoll(lines)
        val running = profiles.filter { sessionOf(it) in result.sessions }.map { it.name }.toSet()
        val next = RunSelection.pick(profiles, running, chosen, state.active)
        state = state.copy(
            running = running,
            status = result.status,
            log = if (withLog) result.log else state.log,
        )
        if (result.status == RunStatus.Running) recoverLocalPort(active)
        state = state.copy(offerLaptop = offloadOffer(active, session, result.status))
        publish()
        // A running session of another profile was found: show that one instead.
        if (next != state.active) {
            state = state.copy(active = next, status = RunStatus.Idle, log = emptyList(), localPort = null)
            refreshNote()
            poll(withLog)
        }
    }

    /** The laptop to offer running [profile] on instead, or null; see [OffloadHint]. */
    private fun offloadOffer(profile: RunProfile, session: String, status: RunStatus): String? {
        val now = SystemClock.elapsedRealtime()
        if (status == RunStatus.Running) runningSince.getOrPut(session) { now } else runningSince.remove(session)
        val tasks = TaskKeeper.latest
        if (tasks !== lastTasks) {
            val previous = lastTasks?.tasks?.firstOrNull { it.id == session }
            val current = tasks?.tasks?.firstOrNull { it.id == session }
            lowMemoryKills[session] = OffloadHint.countKills(lowMemoryKills[session] ?: 0, previous, current)
            lastTasks = tasks
        }
        val laptop = machines.firstOrNull() ?: return null
        if (session in offloadDismissed) return null
        val offer = OffloadHint.offer(
            onPhone = RunWhere.parse(profile.where) == RunWhere.Phone,
            hasLaptop = true,
            lowMemoryKills = lowMemoryKills[session] ?: 0,
            runningForMs = runningSince[session]?.let { now - it },
            hasPort = profile.port != null,
        )
        return if (offer) laptop.name else null
    }

    override fun dismissOffload() {
        val profile = state.activeProfile ?: return
        offloadDismissed += sessionOf(profile)
        state = state.copy(offerLaptop = null)
    }

    /**
     * The offload hint's action: stops the profile on the phone, sets its
     * `where` to the laptop in run.json (the other profiles as they are),
     * and starts it there.
     */
    override fun runOnLaptop(name: String) {
        val dir = state.projectDir ?: return
        val profile = state.activeProfile ?: return
        val config = (state.config as? RunConfigState.Loaded)?.config ?: return
        if (state.pending != null) return
        val updated = RunConfig(config.profiles.map { if (it.name == profile.name) it.copy(where = RunWhere.laptop(name)) else it })
        state = state.copy(pending = RunPending.Stopping, offerLaptop = null)
        publish()
        scope.launch {
            try {
                bundled.run(scripts.stop(sessionOf(profile)), home)
            } catch (e: IOException) {
                Log.w(TAG, "Stop failed", e)
            }
            val ok = withContext(Dispatchers.IO) {
                try {
                    RunFiles.write(File(dir), updated)
                    true
                } catch (e: IOException) {
                    Log.w(TAG, "Writing run.json failed", e)
                    false
                }
            }
            state = state.copy(pending = null)
            if (state.projectDir != dir) return@launch
            if (!ok) {
                state = state.copy(saveFailed = true)
                publish()
                return@launch
            }
            chosen = profile.name
            state = state.copy(config = RunConfigState.Loaded(updated), active = profile.name, status = RunStatus.Idle)
            start()
        }
    }

    override fun select(profile: String) {
        if (profile == state.active || state.profiles.none { it.name == profile }) return
        chosen = profile
        state = state.copy(active = profile, status = RunStatus.Idle, log = emptyList(), startOutput = emptyList(), localPort = null)
        refreshNote()
        publish()
        scope.launch { poll(withLog = logWatchers > 0) }
    }

    override fun start() {
        val dir = state.projectDir ?: return
        val profile = state.activeProfile ?: return
        if (state.pending != null) return
        state = state.copy(pending = RunPending.Starting, startOutput = emptyList())
        publish()
        scope.launch {
            val result = try {
                when (val where = RunWhere.parse(profile.where)) {
                    RunWhere.Phone -> bundled.run(scripts.start(sessionOf(profile), dir, profile), home)
                    is RunWhere.Laptop -> startOnLaptop(where.name, dir, profile)
                    is RunWhere.Unknown -> ShellResult(-1, listOf(context.getString(R.string.diligent_run_where_unknown, where.raw)))
                }
            } catch (e: IOException) {
                ShellResult(-1, listOfNotNull(e.message))
            } catch (e: IllegalArgumentException) {
                ShellResult(-1, listOfNotNull(e.message))
            }
            state = state.copy(pending = null, startOutput = if (result.succeeded) emptyList() else result.lastLines)
            if (result.succeeded) state = state.copy(status = RunStatus.Running)
            publish()
            poll(withLog = logWatchers > 0)
        }
    }

    /**
     * Syncs the project to the laptop called [name] ([LaptopRun.sync]), then
     * starts the ssh that runs it there in the profile's tmux session, so the
     * supervisor keeps it alive like a run on the phone. The profile's port
     * is forwarded to the same port on the phone when that is free, or to
     * any free one.
     */
    private suspend fun startOnLaptop(name: String, dir: String, profile: RunProfile): ShellResult {
        val machine = withContext(Dispatchers.IO) { Machines.load(home) }.firstOrNull { it.name == name }
            ?: return ShellResult(-1, listOf(context.getString(R.string.diligent_run_no_laptop, name)))
        val laptop = LaptopRun(machine, state.projectName ?: File(dir).name, messagesFor(machine))
        val sync = bundled.run(laptop.sync(dir, profile), File(dir))
        if (!sync.succeeded) return sync
        val localPort = profile.port?.let { withContext(Dispatchers.IO) { freeLocalPort(it) } }
        val local = profile.copy(command = laptop.command(profile, localPort), env = emptyMap(), port = localPort)
        val result = bundled.run(scripts.start(sessionOf(profile), dir, local), home)
        if (result.succeeded && state.active == profile.name) state = state.copy(localPort = localPort)
        return result
    }

    private fun messagesFor(machine: Machine) = LaptopMessages(
        unreachable = context.getString(R.string.diligent_laptop_unreachable, machine.name),
        keyRefused = context.getString(R.string.diligent_laptop_key_refused, machine.name),
        hostKeyChanged = context.getString(R.string.diligent_laptop_host_key_changed, machine.name),
        noGit = context.getString(R.string.diligent_laptop_needs_git),
    )

    /**
     * The forwarded port of a laptop run started before this runner existed
     * (another visit, yesterday): the `port` its saved launch script names.
     */
    private suspend fun recoverLocalPort(profile: RunProfile) {
        if (state.localPort != null || RunWhere.parse(profile.where) !is RunWhere.Laptop) return
        val port = withContext(Dispatchers.IO) {
            try {
                File(scripts.runScriptPath(sessionOf(profile))).useLines { it.firstOrNull() }
                    ?.takeIf { it.startsWith(RunScripts.RUN_META) }
                    ?.let { JSONObject(it.removePrefix(RunScripts.RUN_META)).optInt("port", 0) }
                    ?.takeIf { it > 0 }
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }
        if (port != null && state.active == profile.name) state = state.copy(localPort = port)
    }

    override fun stop() {
        val profile = state.activeProfile ?: return
        if (state.pending != null) return
        state = state.copy(pending = RunPending.Stopping)
        publish()
        scope.launch {
            try {
                bundled.run(scripts.stop(sessionOf(profile)), home)
            } catch (e: IOException) {
                Log.w(TAG, "Stop failed", e)
            }
            state = state.copy(pending = null)
            publish()
            poll(withLog = logWatchers > 0)
        }
    }

    override fun save(profile: RunProfile) {
        val dir = state.projectDir ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    RunFiles.write(File(dir), RunConfig(listOf(profile)))
                    true
                } catch (e: IOException) {
                    Log.w(TAG, "Writing run.json failed", e)
                    false
                }
            }
            if (state.projectDir != dir) return@launch
            if (ok) {
                chosen = profile.name
                reload()
            } else {
                state = state.copy(saveFailed = true)
            }
        }
    }

    override fun dismissWebpackNote() {
        val dir = state.projectDir ?: return
        notes.dismissWebpackNote(dir)
        state = state.copy(webpackNote = false)
    }

    override fun openPreview() {
        val port = state.previewPort ?: return
        openPreviewOn(port, state.projectName)
    }

    override fun openConfig() {
        val dir = state.projectDir ?: return
        openFile(RunConfig.fileIn(File(dir)).path)
    }

    private fun refreshNote() {
        val dir = state.projectDir
        state = state.copy(webpackNote = dir != null && notes.showWebpackNote(dir, state.activeProfile))
    }

    private fun sessionOf(profile: RunProfile): String =
        RunSession.name(state.projectName ?: File(state.projectDir.orEmpty()).name, profile.name)

    /** Brings the shell (top bar, action bar) in step with [state]. */
    private fun publish() {
        val s = state
        shell.running = s.isRunning
        shell.previewPort = s.previewPort
        shell.runStatus = when {
            s.pending == RunPending.Starting -> TopBarStatus(context.getString(R.string.diligent_run_status_starting), StatusTone.Warning)
            s.pending == RunPending.Stopping -> TopBarStatus(context.getString(R.string.diligent_run_status_stopping), StatusTone.Warning)
            else -> statusLabel(s.status, s.shownPort)?.let { (res, tone) ->
                val text = when (val st = s.status) {
                    is RunStatus.Exited -> context.getString(res, st.code)
                    RunStatus.Running -> s.shownPort?.let { context.getString(res, it) } ?: context.getString(res)
                    else -> context.getString(res)
                }
                TopBarStatus(text, tone)
            }
        }
    }

    companion object {
        private const val TAG = "DiligentRun"

        /** [preferred] when nothing on the phone listens there, otherwise any free port. Blocking. */
        internal fun freeLocalPort(preferred: Int): Int = try {
            ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", preferred)) }
            preferred
        } catch (_: IOException) {
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }
        const val POLL_INTERVAL_MS = 1000L

        /** Room for the poll's own lines on top of the log tail. */
        private const val POLL_KEEP_LINES = RunScripts.LOG_LINES + 64

        /**
         * The top bar's words for [status], as a string resource and a tone,
         * or null when there is nothing worth saying (idle, stopped, finished
         * cleanly). Shared with the Run screen so both say the same thing.
         */
        fun statusLabel(status: RunStatus, port: Int?): Pair<Int, StatusTone>? = when (status) {
            RunStatus.Running ->
                (if (port != null) R.string.diligent_run_status_running_port else R.string.diligent_run_status_running) to StatusTone.Running
            is RunStatus.Exited -> if (status.code == 0) null else R.string.diligent_run_status_failed to StatusTone.Failed
            RunStatus.NoTmux -> R.string.diligent_run_status_no_tmux to StatusTone.Failed
            RunStatus.Idle, RunStatus.Stopped -> null
        }
    }
}
