package com.vscodroid.diligent.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class StorageCleanupTest {

    private val projects = "/data/files/projects"
    private val cache = "/data/cache"
    private val home = "/data/files/home"
    private val logs = "$home/.diligent/logs"

    private fun may(kind: CleanupKind, path: String) =
        StorageCleanup.mayDelete(kind, path, projectsDir = projects, cacheDir = cache, logsDir = logs, homeDir = home)

    @Test
    fun `only a project's own node_modules may go`() {
        assertTrue(may(CleanupKind.NodeModules, "$projects/site/node_modules"))
        assertFalse(may(CleanupKind.NodeModules, "$projects/site"), "the project folder itself")
        assertFalse(may(CleanupKind.NodeModules, "$projects/site/src"))
        assertFalse(may(CleanupKind.NodeModules, "$projects/site/packages/a/node_modules"), "not directly under a project")
        assertFalse(may(CleanupKind.NodeModules, "$projects/node_modules"), "the projects folder's own")
        assertFalse(may(CleanupKind.NodeModules, "/sdcard/site/node_modules"), "outside the projects folder")
        assertFalse(may(CleanupKind.NodeModules, "$projects/site/../other/node_modules"))
        assertFalse(may(CleanupKind.NodeModules, "$projects/site/./node_modules"))
    }

    @Test
    fun `caches are only the cache folders themselves`() {
        assertTrue(may(CleanupKind.NpmCache, "$cache/npm-cache"))
        assertFalse(may(CleanupKind.NpmCache, cache))
        assertFalse(may(CleanupKind.NpmCache, "$projects/site"))
        assertTrue(may(CleanupKind.PipCache, "$cache/pip"))
        assertTrue(may(CleanupKind.PipCache, "$home/.cache/pip"))
        assertFalse(may(CleanupKind.PipCache, "$home/.cache"))
        assertTrue(may(CleanupKind.AllCaches, cache))
        assertFalse(may(CleanupKind.AllCaches, home))
    }

    @Test
    fun `logs are files directly in the logs folder, snapshots a project folder`() {
        assertTrue(may(CleanupKind.OldRunLogs, logs))
        assertTrue(may(CleanupKind.OldRunLogs, "$logs/diligent-site-dev.log"))
        assertFalse(may(CleanupKind.OldRunLogs, "$home/.diligent/term/x.sh"))
        assertFalse(may(CleanupKind.OldRunLogs, "$projects/site/server.log"))
        assertTrue(may(CleanupKind.OldSnapshots, "$projects/site"))
        assertFalse(may(CleanupKind.OldSnapshots, projects))
        assertFalse(may(CleanupKind.OldSnapshots, "$projects/site/sub"))
    }

    @Test
    fun `logs older than seven days`() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        val logs = listOf(
            LogFile("a.log", 10, now - 8 * day),
            LogFile("b.log", 20, now - 7 * day),
            LogFile("c.status", 30, now - day),
            LogFile("d.log", 40, now - 30 * day),
        )
        assertEquals(listOf("a.log", "d.log"), StorageCleanup.oldLogs(logs, now).map { it.path })
    }

    @Test
    fun `offered items hold something, largest first`() {
        val items = listOf(
            CleanupItem(CleanupKind.NpmCache, 50, "n"),
            CleanupItem(CleanupKind.PipCache, 0, "p"),
            CleanupItem(CleanupKind.NodeModules, 900, "m", "site"),
            CleanupItem(CleanupKind.OldSnapshots, 5, "s", "site", count = 0),
            CleanupItem(CleanupKind.OldSnapshots, 70, "t", "app", count = 3),
        )
        assertEquals(listOf("m", "t", "n"), StorageCleanup.offered(items).map { it.path })
    }

    @Test
    fun `node_modules per project`() {
        assertEquals(
            listOf(File("/p/a/node_modules"), File("/p/b/node_modules")),
            StorageCleanup.nodeModulesOf(File("/p"), listOf("a", "b")),
        )
    }
}
