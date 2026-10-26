package com.vscodroid.diligent.projects

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecentProjectsTest {

    private fun project(path: String, at: Long = 0L) = RecentProject(path, RecentProjectList.nameOf(path), at)

    // -- The list's rules --

    @Test
    fun `a new entry goes first`() {
        val list = listOf(project("/p/a"), project("/p/b"))
        assertEquals(listOf("/p/c", "/p/a", "/p/b"), RecentProjectList.add(list, project("/p/c")).map { it.path })
    }

    @Test
    fun `reopening moves the entry up instead of repeating it`() {
        val list = listOf(project("/p/a", 1), project("/p/b", 2))
        val next = RecentProjectList.add(list, project("/p/b", 9))
        assertEquals(listOf("/p/b", "/p/a"), next.map { it.path })
        assertEquals(9L, next.first().lastOpened)
    }

    @Test
    fun `the list keeps the newest twenty`() {
        var list = emptyList<RecentProject>()
        repeat(25) { list = RecentProjectList.add(list, project("/p/$it")) }
        assertEquals(RecentProjectList.MAX, list.size)
        assertEquals("/p/24", list.first().path)
        assertEquals("/p/5", list.last().path)
    }

    @Test
    fun `remove takes only that path`() {
        val list = listOf(project("/p/a"), project("/p/b"))
        assertEquals(listOf("/p/b"), RecentProjectList.remove(list, "/p/a").map { it.path })
    }

    @Test
    fun `names drop the workspace suffix and a trailing slash`() {
        assertEquals("shop", RecentProjectList.nameOf("/home/projects/shop"))
        assertEquals("shop", RecentProjectList.nameOf("/home/projects/shop/"))
        assertEquals("team", RecentProjectList.nameOf("/home/projects/team.code-workspace"))
        assertEquals("/", RecentProjectList.nameOf("/"))
    }

    @Test
    fun `a workspace file is detected from its folder`() {
        assertEquals("/p/team", RecentProjectList.folderOf("/p/team/team.code-workspace").path)
        assertEquals("/p/shop", RecentProjectList.folderOf("/p/shop").path)
    }

    @Test
    fun `encode and decode round trip`() {
        val list = listOf(
            RecentProject("/p/a", "a", 5, ProjectKind(ProjectLanguage.TypeScript, ProjectFramework.NextJs)),
            RecentProject("/p/it's \"quoted\"", "it's \"quoted\"", 6, ProjectKind.Unknown),
        )
        assertEquals(list, RecentProjectList.decode(RecentProjectList.encode(list)))
    }

    @Test
    fun `a damaged value reads as empty, and bad entries are skipped`() {
        assertEquals(emptyList<RecentProject>(), RecentProjectList.decode("{oops"))
        assertEquals(emptyList<RecentProject>(), RecentProjectList.decode(null))
        val mixed = """[{"path":"/p/a","lastOpened":3,"language":"rust"},{"name":"no path"},7,{"path":"/p/a"}]"""
        assertEquals(
            listOf(RecentProject("/p/a", "a", 3, ProjectKind(ProjectLanguage.Rust))),
            RecentProjectList.decode(mixed),
        )
    }

    // -- The store --

    private val stored = mutableMapOf<String, String>()
    private val prefs: SharedPreferences = mockk {
        every { getString(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
        every { edit() } returns mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } answers { stored[firstArg()] = secondArg<String>(); self as SharedPreferences.Editor }
        }
    }
    private var now = 1_000L
    private val existing = mutableSetOf<String>()

    private fun store() = RecentProjects(
        prefs = prefs,
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        clock = { now },
        exists = { it in existing },
        detect = { if (it.name == "api") ProjectKind(ProjectLanguage.Go) else ProjectKind.Unknown },
    )

    @Test
    fun `nothing is read until asked`() {
        assertNull(store().projects.value)
    }

    @Test
    fun `an opened folder is recorded with its language and kept for the next start`() = runBlocking {
        store().opened("/p/api")
        val next = store()
        existing += "/p/api"
        next.refresh()
        assertEquals(listOf(RecentProject("/p/api", "api", 1_000L, ProjectKind(ProjectLanguage.Go))), next.projects.value)
    }

    @Test
    fun `no folder records nothing`() = runBlocking {
        val recent = store()
        recent.opened(null)
        recent.opened("")
        recent.refresh()
        assertEquals(emptyList<RecentProject>(), recent.projects.value)
        assertEquals(null, stored[RecentProjects.KEY])
    }

    @Test
    fun `reopening updates the time`() = runBlocking {
        val recent = store()
        recent.record("/p/a")
        recent.record("/p/b")
        now = 2_000L
        recent.record("/p/a")
        assertEquals(listOf("/p/a" to 2_000L, "/p/b" to 1_000L), recent.projects.value!!.map { it.path to it.lastOpened })
    }

    @Test
    fun `refresh drops folders that are gone and stores that`() = runBlocking {
        val recent = store()
        recent.record("/p/a")
        recent.record("/p/b")
        existing += "/p/b"
        recent.refresh()
        assertEquals(listOf("/p/b"), recent.projects.value!!.map { it.path })
        assertEquals(listOf("/p/b"), RecentProjectList.decode(stored[RecentProjects.KEY]).map { it.path })
    }

    @Test
    fun `remove forgets the entry only`() = runBlocking {
        val recent = store()
        recent.record("/p/a")
        recent.record("/p/b")
        recent.remove("/p/a")
        assertEquals(listOf("/p/b"), recent.projects.value!!.map { it.path })
        assertEquals(listOf("/p/b"), RecentProjectList.decode(stored[RecentProjects.KEY]).map { it.path })
    }

    @Test
    fun `a detector that is refused access still records the folder`() = runBlocking {
        val recent = RecentProjects(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, { now }, { true }, {
            throw SecurityException("no")
        })
        recent.record("/p/locked")
        assertEquals(ProjectKind.Unknown, recent.projects.value!!.single().kind)
    }
}
