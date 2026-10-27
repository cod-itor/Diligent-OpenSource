package com.vscodroid.diligent.files

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

/** The files recently opened in one project, newest first, as absolute paths. */
data class ProjectRecentFiles(val project: String, val files: List<String>)

/**
 * The recent files' rules, apart from storage and threads so they are tested
 * as plain functions.
 */
object RecentFileList {
    /** Per project: what the top of the all-files list shows. */
    const val MAX_FILES = 10

    /** Projects remembered; the oldest is forgotten first. */
    const val MAX_PROJECTS = 20

    /**
     * [path] first in [project]'s list, without an older copy of itself, and
     * that project first. A path outside the project is not recorded.
     */
    fun add(list: List<ProjectRecentFiles>, project: String, path: String): List<ProjectRecentFiles> {
        val root = project.trimEnd('/')
        if (root.isEmpty() || !path.startsWith("$root/")) return list
        val before = list.firstOrNull { it.project == root }?.files.orEmpty()
        val files = (listOf(path) + before.filter { it != path }).take(MAX_FILES)
        return (listOf(ProjectRecentFiles(root, files)) + list.filter { it.project != root }).take(MAX_PROJECTS)
    }

    /** [project]'s recent files, newest first. */
    fun of(list: List<ProjectRecentFiles>, project: String): List<String> =
        list.firstOrNull { it.project == project.trimEnd('/') }?.files.orEmpty()

    fun encode(list: List<ProjectRecentFiles>): String = JSONArray().apply {
        list.forEach { entry ->
            put(JSONObject().put(KEY_PROJECT, entry.project).put(KEY_FILES, JSONArray(entry.files)))
        }
    }.toString()

    /** The stored list; a damaged value reads as empty, and a damaged entry is skipped. */
    fun decode(json: String?): List<ProjectRecentFiles> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val project = item.optString(KEY_PROJECT).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val files = item.optJSONArray(KEY_FILES) ?: return@mapNotNull null
            val paths = (0 until files.length()).mapNotNull { files.optString(it).takeIf { p -> p.isNotEmpty() } }
            ProjectRecentFiles(project, paths.distinct().take(MAX_FILES))
        }.distinctBy { it.project }.take(MAX_PROJECTS)
    }

    private const val KEY_PROJECT = "project"
    private const val KEY_FILES = "files"
}

/**
 * The files opened recently in each project, for the top of the all-files
 * list and the file switcher.
 *
 * Recorded by the controller's `openFile`, the one place the all-files list,
 * the file switcher and the Files tree all open a file through. Stored as one
 * JSON value in Diligent's own preferences file, like the recent projects
 * next to it, never inside the project.
 */
class RecentFiles internal constructor(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _files = MutableStateFlow<List<ProjectRecentFiles>?>(null)

    /** Null until the stored list has been read once. */
    val files: StateFlow<List<ProjectRecentFiles>?> = _files.asStateFlow()

    private val lock = Mutex()

    /** [path] was opened in [project] (null: no project). Returns at once; the work happens on [io]. */
    fun opened(project: String?, path: String) {
        if (project.isNullOrEmpty()) return
        scope.launch { record(project, path) }
    }

    suspend fun record(project: String, path: String) = withContext(io) {
        update { RecentFileList.add(it, project, path) }
    }

    /** Reads the stored list if it has not been read yet. */
    suspend fun load() = withContext(io) { update { it } }

    private suspend fun update(change: (List<ProjectRecentFiles>) -> List<ProjectRecentFiles>) = lock.withLock {
        val current = _files.value ?: RecentFileList.decode(prefs.getString(KEY, null))
        val next = change(current)
        if (next != current) prefs.edit { putString(KEY, RecentFileList.encode(next)) }
        _files.value = next
    }

    companion object {
        internal const val KEY = "recent_files"

        @Volatile
        private var instance: RecentFiles? = null

        /** The one list for this process, so the controller's records and the screens' reads share it. */
        fun of(context: Context): RecentFiles = instance ?: synchronized(this) {
            instance ?: RecentFiles(
                prefs = context.applicationContext.getSharedPreferences(ViewSettings.PREFS_NAME, Context.MODE_PRIVATE),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                io = Dispatchers.IO,
            ).also { instance = it }
        }
    }
}
