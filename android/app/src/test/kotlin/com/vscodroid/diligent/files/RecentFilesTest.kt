package com.vscodroid.diligent.files

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecentFilesTest {

    // -- The list's rules --

    @Test
    fun `the newest file goes first, and reopening moves it up instead of repeating it`() {
        var list = RecentFileList.add(emptyList(), "/p/a", "/p/a/x.ts")
        list = RecentFileList.add(list, "/p/a", "/p/a/y.ts")
        list = RecentFileList.add(list, "/p/a", "/p/a/x.ts")
        assertEquals(listOf("/p/a/x.ts", "/p/a/y.ts"), RecentFileList.of(list, "/p/a"))
    }

    @Test
    fun `each project keeps its newest ten`() {
        var list = emptyList<ProjectRecentFiles>()
        repeat(15) { list = RecentFileList.add(list, "/p/a", "/p/a/f$it.ts") }
        val files = RecentFileList.of(list, "/p/a")
        assertEquals(RecentFileList.MAX_FILES, files.size)
        assertEquals("/p/a/f14.ts", files.first())
        assertEquals("/p/a/f5.ts", files.last())
    }

    @Test
    fun `projects are kept apart, and the oldest project is forgotten past twenty`() {
        var list = emptyList<ProjectRecentFiles>()
        repeat(25) { list = RecentFileList.add(list, "/p/$it", "/p/$it/main.py") }
        assertEquals(RecentFileList.MAX_PROJECTS, list.size)
        assertEquals(listOf("/p/24/main.py"), RecentFileList.of(list, "/p/24"))
        assertEquals(emptyList<String>(), RecentFileList.of(list, "/p/4"))
        assertEquals(listOf("/p/5/main.py"), RecentFileList.of(list, "/p/5/"))
    }

    @Test
    fun `a file outside the project is not recorded`() {
        val list = RecentFileList.add(emptyList(), "/p/a", "/p/ab/x.ts")
        assertEquals(emptyList<ProjectRecentFiles>(), RecentFileList.add(list, "", "/x.ts"))
        assertEquals(emptyList<ProjectRecentFiles>(), list)
    }

    @Test
    fun `encode and decode round trip, and damage reads as empty`() {
        val list = listOf(ProjectRecentFiles("/p/a", listOf("/p/a/it's \"q\".ts", "/p/a/b.ts")), ProjectRecentFiles("/p/b", emptyList()))
        assertEquals(list, RecentFileList.decode(RecentFileList.encode(list)))
        assertEquals(emptyList<ProjectRecentFiles>(), RecentFileList.decode("[oops"))
        assertEquals(emptyList<ProjectRecentFiles>(), RecentFileList.decode(null))
        assertEquals(
            listOf(ProjectRecentFiles("/p/a", listOf("/p/a/x"))),
            RecentFileList.decode("""[{"project":"/p/a","files":["/p/a/x","/p/a/x"]},{"files":[]},3,{"project":"/p/a","files":[]}]"""),
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

    private fun store() = RecentFiles(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun `an opened file is kept for the next start`() = runBlocking {
        val first = store()
        assertNull(first.files.value)
        first.opened("/p/a", "/p/a/src/main.ts")
        first.opened("/p/a", "/p/a/README.md")
        val next = store()
        next.load()
        assertEquals(listOf("/p/a/README.md", "/p/a/src/main.ts"), RecentFileList.of(next.files.value!!, "/p/a"))
    }

    @Test
    fun `no project records nothing and writes nothing`() = runBlocking {
        val recent = store()
        recent.opened(null, "/x.ts")
        recent.opened("", "/x.ts")
        recent.load()
        assertEquals(emptyList<ProjectRecentFiles>(), recent.files.value)
        assertNull(stored[RecentFiles.KEY])
    }
}
