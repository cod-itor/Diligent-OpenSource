package com.vscodroid.diligent.run

import java.io.File
import java.io.IOException

/** What a project's `run.json` amounts to, for the Run screen. */
sealed interface RunConfigState {
    data object Loading : RunConfigState

    /** No usable profiles. [proposal] is what [RunDetector] found, or null when it found nothing. */
    data class Missing(val proposal: DetectedRun?) : RunConfigState

    /** The file is there but refused; the screen names [problem] and offers to open it. */
    data class Invalid(val problem: RunConfigProblem) : RunConfigState

    data class Loaded(val config: RunConfig) : RunConfigState
}

/** Start or Stop is under way. */
enum class RunPending { Starting, Stopping }

/** Everything the Run screen draws. Owned by [ProjectRunner]; replaced whole on every change. */
data class RunUiState(
    /** The project folder, or null when no project is open. */
    val projectDir: String? = null,
    val projectName: String? = null,
    val config: RunConfigState = RunConfigState.Loading,
    /** The profile Start and Stop act on. */
    val active: String? = null,
    /** Profiles whose session is alive, by name. */
    val running: Set<String> = emptySet(),
    val status: RunStatus = RunStatus.Idle,
    val pending: RunPending? = null,
    /** What a start that failed before tmux took over printed (no tmux, folder gone). */
    val startOutput: List<String> = emptyList(),
    /** Writing `run.json` failed; the form says so and keeps what was typed. */
    val saveFailed: Boolean = false,
    val log: List<String> = emptyList(),
    val webpackNote: Boolean = false,
) {
    val profiles: List<RunProfile>
        get() = (config as? RunConfigState.Loaded)?.config?.profiles.orEmpty()

    val activeProfile: RunProfile?
        get() = profiles.firstOrNull { it.name == active }

    val isRunning: Boolean get() = status == RunStatus.Running

    /** The port to preview, while the active profile runs and has one. */
    val previewPort: Int? get() = if (isRunning) activeProfile?.port else null
}

/** What the Run screen and the action bar can ask of the runner. */
interface RunControl {
    val state: RunUiState

    /**
     * Keeps the status (and with [withLog], the log tail) fresh about once a
     * second until the calling coroutine is cancelled. Polling stops when
     * nobody watches.
     */
    suspend fun watch(withLog: Boolean)

    fun select(profile: String)
    fun start()
    fun stop()

    /** Writes [profile] as the project's only profile in `run.json` (Accept, or Save from the form). */
    fun save(profile: RunProfile)
    fun dismissWebpackNote()
    fun openPreview()

    /** Opens `run.json` in the editor. */
    fun openConfig()
}

/** Which profile Start and Stop act on. */
object RunSelection {
    /**
     * The user's own pick while it still exists; otherwise a profile that is
     * already running (a session survives the app, so reopening the screen
     * must find it); otherwise the current one if still there; otherwise the
     * first, which is the default.
     */
    fun pick(profiles: List<RunProfile>, running: Set<String>, chosen: String?, current: String?): String? {
        val names = profiles.map { it.name }
        return chosen?.takeIf { it in names }
            ?: current?.takeIf { it in running && it in names }
            ?: names.firstOrNull { it in running }
            ?: current?.takeIf { it in names }
            ?: names.firstOrNull()
    }
}

/**
 * Reading and writing a project's `run.json`, and detection when it has none.
 * Blocking file work: call off the main thread.
 */
object RunFiles {
    /** Larger than any real run.json or package.json; a bigger file is not read whole. */
    const val MAX_READ_BYTES = 512 * 1024L

    fun load(projectDir: File): RunConfigState {
        val file = RunConfig.fileIn(projectDir)
        if (file.isFile) {
            val text = readSmall(file) ?: return RunConfigState.Invalid(RunConfigProblem.NotJson("unreadable"))
            return when (val parsed = RunConfig.parse(text)) {
                is RunConfigParse.Invalid -> RunConfigState.Invalid(parsed.problem)
                is RunConfigParse.Ok ->
                    if (parsed.config.profiles.isEmpty()) RunConfigState.Missing(detect(projectDir))
                    else RunConfigState.Loaded(parsed.config)
            }
        }
        return RunConfigState.Missing(detect(projectDir))
    }

    fun detect(projectDir: File): DetectedRun? = RunDetector.detect { name -> readSmall(File(projectDir, name)) }

    /**
     * Writes [config] to the project's `run.json`, through a temporary file
     * and a rename, so a crash mid-write never leaves half a file that the
     * next visit would refuse.
     */
    @Throws(IOException::class)
    fun write(projectDir: File, config: RunConfig) {
        val file = RunConfig.fileIn(projectDir)
        val dir = file.parentFile ?: throw IOException("no parent for $file")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "${RunConfig.FILE}.tmp")
        tmp.writeText(config.toJson())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("cannot replace $file")
        }
    }

    private fun readSmall(file: File): String? = try {
        if (!file.isFile || file.length() > MAX_READ_BYTES) null else file.readText()
    } catch (_: IOException) {
        null
    }
}
