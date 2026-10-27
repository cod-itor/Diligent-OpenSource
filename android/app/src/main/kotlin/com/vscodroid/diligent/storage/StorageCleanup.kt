package com.vscodroid.diligent.storage

import java.io.File

/** What the Storage screen can free. Each kind can always be fetched or made again; none is the user's source. */
enum class CleanupKind {
    /** One project's `node_modules`, which `npm install` brings back. */
    NodeModules,

    /** npm's download cache (`NPM_CONFIG_CACHE`). */
    NpmCache,

    /** pip's download and wheel cache (`PIP_CACHE_DIR`), and where pip kept it before. */
    PipCache,

    /** Every cache and the editor's logs: VSCodroid's own clear (`StorageManager.clearCaches`, the `clearCaches` bridge method). */
    AllCaches,

    /** One project's snapshots beyond the newest 100, on `refs/diligent/snapshots`. */
    OldSnapshots,

    /** Run logs in `~/.diligent/logs` older than seven days. */
    OldRunLogs,
}

/**
 * One thing the Storage screen offers to delete, with the size Delete shows
 * before it asks. [project] is the project's folder name for the per-project
 * kinds; [count] is how many snapshots or log files it is.
 */
data class CleanupItem(
    val kind: CleanupKind,
    val bytes: Long,
    val path: String,
    val project: String? = null,
    val count: Int = 0,
)

/** One row of what uses space, by the key `StorageManager.getStorageBreakdown` reports it under. */
data class UsageRow(val key: String, val bytes: Long)

/** The Storage screen's whole picture: what uses space, what is free, and what can be freed. */
data class StorageReport(val usage: List<UsageRow>, val total: Long, val free: Long, val items: List<CleanupItem>)

/** A file as the log cleanup sees it: where, how big, when last written. */
data class LogFile(val path: String, val bytes: Long, val modifiedMs: Long)

/**
 * The Storage screen's rules, free of Android so they are tested without a device.
 *
 * The one rule above the others: never a source file. Every delete passes
 * [mayDelete], which accepts only the exact places the kinds name, never a
 * project folder itself or anything else in it.
 */
object StorageCleanup {
    const val LOG_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** The breakdown rows the screen lists, in order. `total` is shown above them. */
    val USAGE_KEYS = listOf("projects", "tools", "vscode_server", "extensions", "user_data", "saf_mirrors", "cache", "logs")

    /** Log files last written more than seven days before [nowMs]. */
    fun oldLogs(logs: List<LogFile>, nowMs: Long): List<LogFile> =
        logs.filter { nowMs - it.modifiedMs > LOG_MAX_AGE_MS }

    /** The `node_modules` each project may have: `<projectsDir>/<project>/node_modules`. */
    fun nodeModulesOf(projectsDir: File, projects: List<String>): List<File> =
        projects.map { File(File(projectsDir, it), NODE_MODULES) }

    /**
     * What the screen offers: only items that hold something, largest first.
     * A snapshot item with nothing beyond the newest 100 is left out, as is
     * any item of 0 bytes.
     */
    fun offered(items: List<CleanupItem>): List<CleanupItem> =
        items.filter { it.bytes > 0 && (it.kind != CleanupKind.OldSnapshots || it.count > 0) }
            .sortedByDescending { it.bytes }

    /**
     * Whether [path] is a place [kind] may delete, by its path alone (no
     * disk access): a project's own `node_modules` directly under
     * [projectsDir]; the npm or pip cache folder itself; a file directly in
     * [logsDir], or that folder as the item; for snapshots, a project folder directly under
     * [projectsDir] (the trim touches only its Git objects). Anything with a
     * `..` segment is refused.
     */
    fun mayDelete(kind: CleanupKind, path: String, projectsDir: String, cacheDir: String, logsDir: String, homeDir: String): Boolean {
        val target = File(path)
        if (target.path.split(File.separatorChar).any { it == ".." || it == "." }) return false
        val parent = target.parentFile ?: return false
        return when (kind) {
            CleanupKind.NodeModules ->
                target.name == NODE_MODULES && parent.parentFile?.path == File(projectsDir).path && parent.name.isNotEmpty()
            CleanupKind.NpmCache -> target.path == File(cacheDir, NPM_CACHE).path
            CleanupKind.PipCache -> target.path == File(cacheDir, PIP_CACHE).path || target.path == File(homeDir, LEGACY_PIP_CACHE).path
            // The folder (the item) or one file directly in it (each delete).
            CleanupKind.OldRunLogs -> target.path == File(logsDir).path || parent.path == File(logsDir).path
            CleanupKind.OldSnapshots -> parent.path == File(projectsDir).path
            // VSCodroid's own clear decides what it reaches; the item names the cache folder.
            CleanupKind.AllCaches -> target.path == File(cacheDir).path
        }
    }

    const val NODE_MODULES = "node_modules"
    const val NPM_CACHE = "npm-cache"
    const val PIP_CACHE = "pip"
    const val LEGACY_PIP_CACHE = ".cache/pip"
}
