package com.vscodroid.diligent.storage

import android.content.Context
import com.vscodroid.diligent.process.BundledShell
import com.vscodroid.diligent.snapshot.SnapshotRunner
import com.vscodroid.util.Environment
import com.vscodroid.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Measures and frees space for the Storage screen, on the IO dispatcher.
 *
 * What uses space comes from VSCodroid's own breakdown and free space
 * (`StorageManager`, behind the `getStorageBreakdown` and
 * `getAvailableStorage` bridge methods), and "All caches and editor logs"
 * is its `clearCaches`. Diligent adds the per-project, npm, pip, snapshot and
 * run log items. Every delete checks [StorageCleanup.mayDelete] again on the
 * path it is about to remove, and folders are removed with
 * `StorageManager.deleteRecursive`, which unlinks a link rather than
 * following it out of the folder.
 */
class StorageScanner(private val context: Context) {
    private val projectsDir by lazy { File(Environment.getProjectsDir(context)) }
    private val homeDir by lazy { File(Environment.getHomeDir(context)) }
    private val logsDir by lazy { File(homeDir, ".diligent/logs") }
    private val cacheDir get() = context.cacheDir
    private val snapshots by lazy { SnapshotRunner(BundledShell(context)) }

    suspend fun scan(nowMs: Long = System.currentTimeMillis()): StorageReport = withContext(Dispatchers.IO) {
        val breakdown = StorageManager.getStorageBreakdown(context)
        val usage = StorageCleanup.USAGE_KEYS.map { UsageRow(it, breakdown.optLong(it)) }
        val projects = projectsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }.orEmpty()
        val items = buildList {
            for (folder in StorageCleanup.nodeModulesOf(projectsDir, projects)) {
                if (folder.isDirectory) {
                    add(CleanupItem(CleanupKind.NodeModules, StorageManager.dirSize(folder), folder.path, folder.parentFile?.name))
                }
            }
            val npm = File(cacheDir, StorageCleanup.NPM_CACHE)
            add(CleanupItem(CleanupKind.NpmCache, StorageManager.dirSize(npm), npm.path))
            val pip = File(cacheDir, StorageCleanup.PIP_CACHE)
            val legacyPip = File(homeDir, StorageCleanup.LEGACY_PIP_CACHE)
            add(CleanupItem(CleanupKind.PipCache, StorageManager.dirSize(pip) + StorageManager.dirSize(legacyPip), pip.path))
            add(CleanupItem(CleanupKind.AllCaches, breakdown.optLong("cache") + breakdown.optLong("logs"), cacheDir.path))
            for (name in projects) {
                val project = File(projectsDir, name)
                if (!File(project, ".git").exists()) continue
                val (count, bytes) = snapshots.excess(project) ?: continue
                add(CleanupItem(CleanupKind.OldSnapshots, bytes, project.path, name, count))
            }
            val old = StorageCleanup.oldLogs(logFiles(), nowMs)
            add(CleanupItem(CleanupKind.OldRunLogs, old.sumOf { it.bytes }, logsDir.path, count = old.size))
        }
        StorageReport(
            usage = usage,
            total = breakdown.optLong("total"),
            free = StorageManager.getAvailableStorage(context),
            items = StorageCleanup.offered(items),
        )
    }

    /** Frees [item], and answers the bytes freed (for snapshots, the measured estimate), or null when it was refused or failed. */
    suspend fun delete(item: CleanupItem, nowMs: Long = System.currentTimeMillis()): Long? = withContext(Dispatchers.IO) {
        val allowed = StorageCleanup.mayDelete(
            item.kind, item.path,
            projectsDir = projectsDir.path, cacheDir = cacheDir.path, logsDir = logsDir.path, homeDir = homeDir.path,
        )
        if (!allowed) return@withContext null
        when (item.kind) {
            CleanupKind.NodeModules, CleanupKind.NpmCache -> StorageManager.deleteRecursive(File(item.path))
            CleanupKind.PipCache ->
                StorageManager.deleteRecursive(File(item.path)) + StorageManager.removeLegacyPipCache(context)
            CleanupKind.AllCaches -> StorageManager.clearCaches(context)
            CleanupKind.OldSnapshots -> if (snapshots.trim(File(item.path))) item.bytes else null
            CleanupKind.OldRunLogs -> StorageCleanup.oldLogs(logFiles(), nowMs).sumOf { log ->
                val file = File(log.path)
                if (StorageCleanup.mayDelete(item.kind, file.path, projectsDir.path, cacheDir.path, logsDir.path, homeDir.path) &&
                    file.delete()
                ) log.bytes else 0L
            }
        }
    }

    private fun logFiles(): List<LogFile> =
        logsDir.listFiles()?.filter { it.isFile }?.map { LogFile(it.path, it.length(), it.lastModified()) }.orEmpty()
}
