package com.vscodroid.diligent.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class FileIndexTest {

    @TempDir
    lateinit var temp: File

    private fun project(vararg files: String): File {
        val root = File(temp, "project")
        files.forEach { File(root, it).apply { parentFile.mkdirs() }.writeText("x") }
        root.mkdirs()
        return root
    }

    private fun index(root: File, max: Int = FileIndex.MAX_FILES) =
        runBlocking { FileIndex.build(root.path, max, Dispatchers.Unconfined) }!!

    // -- Listing --

    @Test
    fun `every file is listed, grouped by folder, root first`() {
        val root = project("src/b.ts", "README.md", "src/app/page.tsx", "src/a10.ts", "src/a2.ts", ".env")
        assertEquals(
            listOf(".env", "README.md", "src/a2.ts", "src/a10.ts", "src/b.ts", "src/app/page.tsx"),
            index(root).files.map { it.relative },
        )
    }

    @Test
    fun `paths are under the root as given, with name and folder split`() {
        val root = project("src/app/page.tsx")
        val file = index(root).files.single()
        assertEquals(File(root, "src/app/page.tsx").path, file.path)
        assertEquals("page.tsx", file.name)
        assertEquals("src/app", file.folder)
    }

    @Test
    fun `heavy and build folders are skipped`() {
        val root = project(
            "index.js", ".git/config", "node_modules/x/index.js", ".venv/bin/python", "build/out.js",
            "dist/a.js", "__pycache__/m.pyc", "src/node_modules/y.js", ".github/workflows/ci.yml",
        )
        assertEquals(listOf("index.js", ".github/workflows/ci.yml"), index(root).files.map { it.relative })
    }

    @Test
    fun `the cap stops the listing and says so, keeping the shallow files`() {
        val root = project("a.txt", "b.txt", "deep/c.txt", "deep/d.txt")
        val capped = index(root, max = 3)
        assertTrue(capped.capped)
        assertEquals(listOf("a.txt", "b.txt", "deep/c.txt"), capped.files.map { it.relative })

        val exact = index(root, max = 4)
        assertFalse(exact.capped)
        assertEquals(4, exact.files.size)
    }

    @Test
    fun `links are not followed into folders or out of the project`() {
        val root = project("src/a.ts")
        val outside = File(temp, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("no")
        Files.createSymbolicLink(File(root, "out").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        Files.createSymbolicLink(File(root, "secret.txt").toPath(), File(outside, "secret.txt").toPath())
        Files.createSymbolicLink(File(root, "alias.ts").toPath(), File(root, "src/a.ts").toPath())
        assertEquals(listOf("alias.ts", "src/a.ts"), index(root).files.map { it.relative })
    }

    @Test
    fun `a root reached through a link keeps the link in its paths`() {
        val real = project("main.py")
        val link = File(temp, "by-name").toPath()
        Files.createSymbolicLink(link, real.toPath())
        val file = runBlocking { FileIndex.build(link.toString(), io = Dispatchers.Unconfined) }!!.files.single()
        assertEquals("$link/main.py", file.path)
    }

    @Test
    fun `a root that is not a folder has no index`() {
        val file = File(temp, "team.code-workspace").apply { writeText("{}") }
        assertNull(runBlocking { FileIndex.build(file.path, io = Dispatchers.Unconfined) })
        assertNull(runBlocking { FileIndex.build(File(temp, "missing").path, io = Dispatchers.Unconfined) })
    }

    // -- Search --

    private fun files(vararg relative: String) =
        FileIndex(relative.map { IndexedFile("/p/$it", it) }, capped = false)

    private fun search(index: FileIndex, query: String, recent: List<String> = emptyList()) =
        FileSearch.matches(index, query, recent).others.map { it.relative }

    @Test
    fun `name prefix beats name contains beats path contains beats letters in order`() {
        val index = files("main/notes.md", "src/domain.ts", "src/main.ts", "lib/m_a_i_n.txt")
        assertEquals(listOf("src/main.ts", "src/domain.ts", "main/notes.md", "lib/m_a_i_n.txt"), search(index, "main"))
    }

    @Test
    fun `case and surrounding spaces do not matter`() {
        val index = files("README.md", "src/app.ts")
        assertEquals(listOf("README.md"), search(index, "  readme "))
    }

    @Test
    fun `letters in order find a file through its path`() {
        val index = files("src/app/page.tsx", "src/lib/util.ts")
        assertEquals(listOf("src/app/page.tsx"), search(index, "apts"))
    }

    @Test
    fun `nothing matching gives an empty result`() {
        assertTrue(FileSearch.matches(files("a.txt"), "zzz", emptyList()).isEmpty)
    }

    @Test
    fun `within a tier, recent files then shorter names come first`() {
        val index = files("a/index.ts", "b/index_old.ts", "c/index.ts")
        assertEquals(listOf("a/index.ts", "c/index.ts", "b/index_old.ts"), search(index, "index"))
        assertEquals(
            listOf("c/index.ts", "a/index.ts", "b/index_old.ts"),
            search(index, "index", recent = listOf("/p/c/index.ts")),
        )
    }

    @Test
    fun `an empty search shows recent files first, newest first, only those still there`() {
        val index = files("a.ts", "b.ts", "c.ts")
        val result = FileSearch.matches(index, "", listOf("/p/c.ts", "/p/gone.ts", "/p/a.ts", "/p/c.ts"))
        assertEquals(listOf("c.ts", "a.ts"), result.recent.map { it.relative })
        assertEquals(listOf("a.ts", "b.ts", "c.ts"), result.others.map { it.relative })
    }

    @Test
    fun `at most ten recent files are shown`() {
        val names = (1..15).map { "f$it.ts" }
        val index = files(*names.toTypedArray())
        val result = FileSearch.matches(index, "", names.map { "/p/$it" })
        assertEquals(FileSearch.RECENT_SHOWN, result.recent.size)
    }
}
