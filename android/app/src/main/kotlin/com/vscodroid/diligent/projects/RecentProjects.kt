package com.vscodroid.diligent.projects

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.vscodroid.diligent.ViewSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** A project the editor opened, as the Projects tab lists it. */
data class RecentProject(
    /** The folder (or `.code-workspace` file) as the editor opened it, absolute. */
    val path: String,
    /** The folder's name, without `.code-workspace`. */
    val name: String,
    /** When it was last opened, in milliseconds since the epoch. */
    val lastOpened: Long,
    val kind: ProjectKind = ProjectKind.Unknown,
)

/**
 * The recent list's rules, apart from storage and threads so they are tested
 * as plain functions.
 */
object RecentProjectList {
    /** Enough to find last month's project, few enough to read at a glance. */
    const val MAX = 20

    /** [entry] first, without any older entry for the same path, cut to [max]. */
    fun add(list: List<RecentProject>, entry: RecentProject, max: Int = MAX): List<RecentProject> =
        (listOf(entry) + list.filter { it.path != entry.path }).take(max)

    fun remove(list: List<RecentProject>, path: String): List<RecentProject> = list.filter { it.path != path }

    /** The name the card shows for [path]. */
    fun nameOf(path: String): String {
        val trimmed = path.trimEnd('/')
        return File(trimmed).name.removeSuffix(WORKSPACE_SUFFIX).ifEmpty { path }
    }

    /**
     * The folder whose files say what [path] is written in: the folder
     * itself, or the folder holding a `.code-workspace` file.
     */
    fun folderOf(path: String): File {
        val file = File(path)
        return if (path.endsWith(WORKSPACE_SUFFIX)) file.parentFile ?: file else file
    }

    fun encode(list: List<RecentProject>): String = JSONArray().apply {
        list.forEach { project ->
            put(
                JSONObject()
                    .put(KEY_PATH, project.path)
                    .put(KEY_NAME, project.name)
                    .put(KEY_LAST_OPENED, project.lastOpened)
                    .put(KEY_LANGUAGE, project.kind.language.key)
                    .apply { project.kind.framework?.let { put(KEY_FRAMEWORK, it.key) } },
            )
        }
    }.toString()

    /**
     * The stored list. A value that does not parse reads as empty rather
     * than failing the screen: losing a recent list is a small loss, and an
     * app that cannot start its home tab is not. Entries without a path are
     * dropped one by one, so one bad entry does not take the others with it.
     */
    fun decode(json: String?): List<RecentProject> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val path = item.optString(KEY_PATH).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RecentProject(
                path = path,
                name = item.optString(KEY_NAME).ifEmpty { nameOf(path) },
                lastOpened = item.optLong(KEY_LAST_OPENED, 0L),
                kind = ProjectKind(
                    ProjectLanguage.fromKey(item.optString(KEY_LANGUAGE)),
                    ProjectFramework.fromKey(item.optString(KEY_FRAMEWORK)),
                ),
            )
        }.distinctBy { it.path }.take(MAX)
    }

    const val WORKSPACE_SUFFIX = ".code-workspace"
    private const val KEY_PATH = "path"
    private const val KEY_NAME = "name"
    private const val KEY_LAST_OPENED = "lastOpened"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_FRAMEWORK = "framework"
}

/**
 * The projects the editor opened, newest first: what the Projects tab shows.
 *
 * Recorded by the controller each time a folder opens, whichever way it was
 * opened (this tab, VS Code's own Open Folder, a link), because the editor's
 * page load is the one place every route passes through.
 *
 * Stored as one JSON value in Diligent's own preferences file, never inside
 * the project: a project is the user's, may be a git repository, and should
 * not grow files it did not ask for. That file is left out of backup, like
 * the view choice next to it, which suits a list of paths on this device.
 *
 * Every read and write of the file, and every look at a project's folder,
 * runs on [io]. [projects] is null until the stored list has been read once,
 * so the screen can tell "still reading" from "nothing yet" and does not
 * flash its welcome at someone with twenty projects.
 */
class RecentProjects internal constructor(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exists: (String) -> Boolean = { File(it).exists() },
    private val detect: (File) -> ProjectKind = ProjectDetector::detect,
) {
    private val _projects = MutableStateFlow<List<RecentProject>?>(null)
    val projects: StateFlow<List<RecentProject>?> = _projects.asStateFlow()

    /** One read-modify-write at a time: a folder can open while the screen is pruning. */
    private val lock = Mutex()

    /** The editor is opening [path] (null: no folder). Returns at once; the work happens on [io]. */
    fun opened(path: String?) {
        if (path.isNullOrEmpty()) return
        scope.launch { record(path) }
    }

    /** Puts [path] first, with its language detected now (the project may have changed since). */
    suspend fun record(path: String) = withContext(io) {
        val kind = try {
            detect(RecentProjectList.folderOf(path))
        } catch (_: SecurityException) {
            ProjectKind.Unknown
        }
        val entry = RecentProject(path, RecentProjectList.nameOf(path), clock(), kind)
        update { RecentProjectList.add(it, entry) }
    }

    /** Takes [path] off the list. The folder and its files stay where they are. */
    suspend fun remove(path: String) = withContext(io) {
        update { RecentProjectList.remove(it, path) }
    }

    /**
     * Reads the stored list if it has not been read, and drops every entry
     * whose folder is gone (deleted from the Files tab, or from another app).
     */
    suspend fun refresh() = withContext(io) {
        update { list -> list.filter { exists(it.path) } }
    }

    private suspend fun update(change: (List<RecentProject>) -> List<RecentProject>) = lock.withLock {
        val current = _projects.value ?: RecentProjectList.decode(prefs.getString(KEY, null))
        val next = change(current)
        if (next != current) prefs.edit { putString(KEY, RecentProjectList.encode(next)) }
        _projects.value = next
    }

    companion object {
        internal const val KEY = "recent_projects"

        @Volatile
        private var instance: RecentProjects? = null

        /**
         * The one list for this process. One, so the controller's records and
         * the screen's reads never race over two copies of the stored value;
         * process-wide, so the work it launches outlives a tab switch.
         */
        fun of(context: Context): RecentProjects = instance ?: synchronized(this) {
            instance ?: RecentProjects(
                prefs = context.applicationContext.getSharedPreferences(ViewSettings.PREFS_NAME, Context.MODE_PRIVATE),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                io = Dispatchers.IO,
            ).also { instance = it }
        }
    }
}
