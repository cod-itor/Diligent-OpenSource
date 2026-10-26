package com.vscodroid.diligent.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class ProjectFilesTest {

    @TempDir
    lateinit var temp: File

    private lateinit var project: File
    private lateinit var outside: File
    private lateinit var files: ProjectFiles

    @BeforeEach
    fun setUp() {
        project = File(temp, "project").apply { mkdirs() }
        outside = File(temp, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("keep me")
        files = ProjectFiles(project.path, Dispatchers.Unconfined)
    }

    private fun <T> done(result: FileResult<T>): T {
        assertTrue(result is FileResult.Done, "expected Done, got $result")
        return (result as FileResult.Done).value
    }

    private fun refused(problem: FileProblem, result: FileResult<*>) =
        assertEquals(FileResult.Refused(problem), result)

    private fun names(folder: File = project) = runBlocking { done(files.list(folder.path)).map { it.name } }

    private fun link(at: File, to: File) {
        Files.createSymbolicLink(at.toPath(), to.toPath())
    }

    @Nested
    inner class Listing {
        @Test
        fun `folders come first, each group in case-insensitive natural order`() {
            listOf("b.txt", "File10.kt", "file2.kt", "A.md").forEach { File(project, it).writeText("") }
            listOf("zeta", "Alpha", "src2", "src10").forEach { File(project, it).mkdir() }
            assertEquals(
                listOf("Alpha", "src2", "src10", "zeta", "A.md", "b.txt", "file2.kt", "File10.kt"),
                names(),
            )
        }

        @Test
        fun `dot files are listed and marked hidden, git and node_modules marked heavy`() {
            File(project, ".git").mkdir()
            File(project, "node_modules").mkdir()
            File(project, ".env").writeText("")
            File(project, "src").mkdir()
            val entries = runBlocking { done(files.list(project.path)) }.associateBy { it.name }
            assertTrue(entries.getValue(".git").isHidden)
            assertTrue(entries.getValue(".git").isHeavy)
            assertTrue(entries.getValue("node_modules").isHeavy)
            assertFalse(entries.getValue("node_modules").isHidden)
            assertTrue(entries.getValue(".env").isHidden)
            assertFalse(entries.getValue(".env").isHeavy)
            assertFalse(entries.getValue("src").isHidden)
        }

        @Test
        fun `entries carry paths under the root as given, even when the root is a link`() {
            File(project, "src").mkdir()
            val alias = File(temp, "by-name").apply { mkdirs() }.let { File(it, "My Project") }
            link(alias, project)
            val viaLink = ProjectFiles(alias.path, Dispatchers.Unconfined)
            val entries = runBlocking { done(viaLink.list(alias.path)) }
            assertEquals(listOf(File(alias, "src").path), entries.map { it.path })
            assertEquals(listOf("a.txt"), runBlocking {
                done(viaLink.createFile(File(alias, "src").path, "a.txt"))
                done(viaLink.list(File(alias, "src").path)).map { it.name }
            })
        }

        @Test
        fun `a link leaving the project is listed as a file that leaves it`() {
            link(File(project, "escape"), outside)
            File(project, "real").mkdir()
            link(File(project, "inner"), File(project, "real"))
            val entries = runBlocking { done(files.list(project.path)) }.associateBy { it.name }
            with(entries.getValue("escape")) {
                assertTrue(isLink)
                assertTrue(leavesProject)
                assertFalse(isDirectory)
            }
            with(entries.getValue("inner")) {
                assertTrue(isLink)
                assertFalse(leavesProject)
                assertTrue(isDirectory)
            }
        }

        @Test
        fun `listing outside the project is refused, by dot-dot or through a link`() = runBlocking {
            refused(FileProblem.OutsideProject, files.list(File(project, "../outside").path))
            refused(FileProblem.OutsideProject, files.list(outside.path))
            link(File(project, "escape"), outside)
            refused(FileProblem.OutsideProject, files.list(File(project, "escape").path))
        }

        @Test
        fun `a missing folder and a file are not listed`() = runBlocking {
            refused(FileProblem.Missing, files.list(File(project, "nope").path))
            File(project, "a.txt").writeText("")
            refused(FileProblem.NotAFolder, files.list(File(project, "a.txt").path))
        }

        @Test
        fun `an empty folder lists as empty`() {
            assertEquals(emptyList<String>(), names())
        }

        @Test
        fun `isFolder answers only for folders inside`() = runBlocking {
            File(project, "src").mkdir()
            File(project, "a.txt").writeText("")
            assertTrue(files.isFolder(project.path))
            assertTrue(files.isFolder(File(project, "src").path))
            assertFalse(files.isFolder(File(project, "a.txt").path))
            assertFalse(files.isFolder(outside.path))
        }
    }

    @Nested
    inner class Creating {
        @Test
        fun `creates an empty file and a folder, trimming the name`() = runBlocking {
            val file = done(files.createFile(project.path, "  index.ts "))
            assertEquals(File(project, "index.ts").path, file)
            assertTrue(File(file).isFile)
            assertEquals(0L, File(file).length())
            val folder = done(files.createFolder(project.path, "lib"))
            assertTrue(File(folder).isDirectory)
        }

        @Test
        fun `creates inside a nested folder`() = runBlocking {
            File(project, "src/app").mkdirs()
            done(files.createFile(File(project, "src/app").path, "page.tsx"))
            assertTrue(File(project, "src/app/page.tsx").isFile)
        }

        @Test
        fun `refuses a name that is taken, by a file or a folder`() = runBlocking {
            File(project, "a.txt").writeText("old")
            File(project, "lib").mkdir()
            refused(FileProblem.NameTaken, files.createFile(project.path, "a.txt"))
            refused(FileProblem.NameTaken, files.createFolder(project.path, "lib"))
            refused(FileProblem.NameTaken, files.createFolder(project.path, "a.txt"))
            assertEquals("old", File(project, "a.txt").readText())
        }

        @Test
        fun `refuses a dangling link's name too`() = runBlocking {
            link(File(project, "dangling"), File(project, "gone"))
            refused(FileProblem.NameTaken, files.createFile(project.path, "dangling"))
        }

        @Test
        fun `refuses bad names`() = runBlocking {
            refused(FileProblem.EmptyName, files.createFile(project.path, ""))
            refused(FileProblem.EmptyName, files.createFile(project.path, "   "))
            refused(FileProblem.BadName, files.createFile(project.path, "a/b"))
            refused(FileProblem.BadName, files.createFile(project.path, ".."))
            refused(FileProblem.BadName, files.createFolder(project.path, "."))
            refused(FileProblem.BadName, files.createFile(project.path, "../escape.txt"))
            assertFalse(File(temp, "escape.txt").exists())
        }

        @Test
        fun `refuses to create outside the project, directly or through a link`() = runBlocking {
            refused(FileProblem.OutsideProject, files.createFile(outside.path, "x.txt"))
            refused(FileProblem.OutsideProject, files.createFile(File(project, "..").path, "x.txt"))
            link(File(project, "escape"), outside)
            refused(FileProblem.OutsideProject, files.createFile(File(project, "escape").path, "x.txt"))
            assertFalse(File(outside, "x.txt").exists())
            assertFalse(File(temp, "x.txt").exists())
        }

        @Test
        fun `refuses to create inside a file`() = runBlocking {
            File(project, "a.txt").writeText("")
            refused(FileProblem.NotAFolder, files.createFile(File(project, "a.txt").path, "b"))
        }
    }

    @Nested
    inner class Renaming {
        @Test
        fun `renames a file and a folder with its contents`() = runBlocking {
            File(project, "a.txt").writeText("hello")
            File(project, "src/deep").mkdirs()
            File(project, "src/deep/x.kt").writeText("x")
            assertEquals(File(project, "b.txt").path, done(files.rename(File(project, "a.txt").path, "b.txt")))
            assertEquals("hello", File(project, "b.txt").readText())
            done(files.rename(File(project, "src").path, "lib"))
            assertEquals("x", File(project, "lib/deep/x.kt").readText())
            assertFalse(File(project, "src").exists())
        }

        @Test
        fun `renaming to the same name changes nothing`() = runBlocking {
            File(project, "a.txt").writeText("hello")
            assertEquals(File(project, "a.txt").path, done(files.rename(File(project, "a.txt").path, "a.txt")))
            assertEquals("hello", File(project, "a.txt").readText())
        }

        @Test
        fun `a case-only rename works whatever the file system's case rules`() = runBlocking {
            File(project, "readme.md").writeText("hi")
            done(files.rename(File(project, "readme.md").path, "README.md"))
            assertEquals(listOf("README.md"), names())
        }

        @Test
        fun `refuses a taken name and leaves both files alone`() = runBlocking {
            File(project, "a.txt").writeText("a")
            File(project, "b.txt").writeText("b")
            refused(FileProblem.NameTaken, files.rename(File(project, "a.txt").path, "b.txt"))
            assertEquals("a", File(project, "a.txt").readText())
            assertEquals("b", File(project, "b.txt").readText())
        }

        @Test
        fun `renaming a link onto the name of its target is a clash`() = runBlocking {
            File(project, "b").mkdir()
            link(File(project, "a"), File(project, "b"))
            refused(FileProblem.NameTaken, files.rename(File(project, "a").path, "b"))
            assertTrue(Files.isSymbolicLink(File(project, "a").toPath()))
        }

        @Test
        fun `renames a link, not what it points at`() = runBlocking {
            link(File(project, "escape"), outside)
            done(files.rename(File(project, "escape").path, "renamed"))
            assertTrue(Files.isSymbolicLink(File(project, "renamed").toPath()))
            assertTrue(File(outside, "secret.txt").exists())
            assertTrue(outside.isDirectory)
        }

        @Test
        fun `refuses bad names, the root, and anything outside`() = runBlocking {
            File(project, "a.txt").writeText("")
            refused(FileProblem.BadName, files.rename(File(project, "a.txt").path, "../a.txt"))
            refused(FileProblem.EmptyName, files.rename(File(project, "a.txt").path, " "))
            refused(FileProblem.ProjectRoot, files.rename(project.path, "other"))
            refused(FileProblem.ProjectRoot, files.rename("${project.path}/src/..", "other"))
            refused(FileProblem.OutsideProject, files.rename(File(outside, "secret.txt").path, "x"))
            refused(FileProblem.OutsideProject, files.rename(File(project, "../outside/secret.txt").path, "x"))
            link(File(project, "escape"), outside)
            refused(FileProblem.OutsideProject, files.rename(File(project, "escape/secret.txt").path, "x"))
            assertTrue(File(outside, "secret.txt").exists())
        }

        @Test
        fun `refuses a missing entry`() = runBlocking {
            refused(FileProblem.Missing, files.rename(File(project, "nope").path, "x"))
        }
    }

    @Nested
    inner class Moving {
        @Test
        fun `moves a file and a folder into another folder`() = runBlocking {
            File(project, "a.txt").writeText("a")
            File(project, "util/inner").mkdirs()
            File(project, "lib").mkdir()
            assertEquals(File(project, "lib/a.txt").path, done(files.move(File(project, "a.txt").path, File(project, "lib").path)))
            done(files.move(File(project, "util").path, File(project, "lib").path))
            assertTrue(File(project, "lib/util/inner").isDirectory)
            assertEquals("a", File(project, "lib/a.txt").readText())
            done(files.move(File(project, "lib/a.txt").path, project.path))
            assertTrue(File(project, "a.txt").isFile)
        }

        @Test
        fun `refuses a folder into itself or its own folder`() = runBlocking {
            File(project, "src/app").mkdirs()
            refused(FileProblem.IntoItself, files.move(File(project, "src").path, File(project, "src").path))
            refused(FileProblem.IntoItself, files.move(File(project, "src").path, File(project, "src/app").path))
            assertTrue(File(project, "src/app").isDirectory)
        }

        @Test
        fun `refuses a move into the folder it is already in`() = runBlocking {
            File(project, "a.txt").writeText("")
            refused(FileProblem.AlreadyThere, files.move(File(project, "a.txt").path, project.path))
        }

        @Test
        fun `refuses when the destination holds the name`() = runBlocking {
            File(project, "a.txt").writeText("mine")
            File(project, "lib").mkdir()
            File(project, "lib/a.txt").writeText("theirs")
            refused(FileProblem.NameTaken, files.move(File(project, "a.txt").path, File(project, "lib").path))
            assertEquals("theirs", File(project, "lib/a.txt").readText())
            assertEquals("mine", File(project, "a.txt").readText())
        }

        @Test
        fun `refuses a destination outside, directly or through a link, and a file as destination`() = runBlocking {
            File(project, "a.txt").writeText("")
            File(project, "b.txt").writeText("")
            refused(FileProblem.OutsideProject, files.move(File(project, "a.txt").path, outside.path))
            link(File(project, "escape"), outside)
            refused(FileProblem.OutsideProject, files.move(File(project, "a.txt").path, File(project, "escape").path))
            refused(FileProblem.NotAFolder, files.move(File(project, "a.txt").path, File(project, "b.txt").path))
            assertTrue(File(project, "a.txt").exists())
            assertFalse(File(outside, "a.txt").exists())
        }

        @Test
        fun `refuses moving the root or something outside`() = runBlocking {
            File(project, "lib").mkdir()
            refused(FileProblem.ProjectRoot, files.move(project.path, File(project, "lib").path))
            refused(FileProblem.OutsideProject, files.move(File(outside, "secret.txt").path, File(project, "lib").path))
            assertTrue(File(outside, "secret.txt").exists())
        }

        @Test
        fun `moves into a folder reached through a link that stays inside`() = runBlocking {
            File(project, "real").mkdir()
            link(File(project, "alias"), File(project, "real"))
            File(project, "a.txt").writeText("")
            done(files.move(File(project, "a.txt").path, File(project, "alias").path))
            assertTrue(File(project, "real/a.txt").isFile)
        }
    }

    @Nested
    inner class Deleting {
        @Test
        fun `deletes a file and a folder with everything in it`() = runBlocking {
            File(project, "a.txt").writeText("")
            File(project, "src/deep/deeper").mkdirs()
            File(project, "src/deep/deeper/x.kt").writeText("")
            File(project, "src/.hidden").writeText("")
            done(files.delete(File(project, "a.txt").path))
            done(files.delete(File(project, "src").path))
            assertEquals(emptyList<String>(), names())
            assertTrue(project.isDirectory)
        }

        @Test
        fun `deleting a link removes the link and never what it points at`() = runBlocking {
            link(File(project, "escape"), outside)
            File(project, "src").mkdir()
            link(File(project, "src/escape"), outside)
            done(files.delete(File(project, "escape").path))
            done(files.delete(File(project, "src").path))
            assertEquals("keep me", File(outside, "secret.txt").readText())
            assertEquals(emptyList<String>(), names())
        }

        @Test
        fun `refuses the root, anything outside, and what is not there`() = runBlocking {
            File(project, "a.txt").writeText("")
            refused(FileProblem.ProjectRoot, files.delete(project.path))
            refused(FileProblem.ProjectRoot, files.delete("${project.path}/"))
            refused(FileProblem.OutsideProject, files.delete(outside.path))
            refused(FileProblem.OutsideProject, files.delete(File(project, "../outside").path))
            link(File(project, "escape"), outside)
            refused(FileProblem.OutsideProject, files.delete(File(project, "escape/secret.txt").path))
            refused(FileProblem.Missing, files.delete(File(project, "nope").path))
            assertTrue(File(outside, "secret.txt").exists())
            assertTrue(File(project, "a.txt").exists())
        }
    }

    @Test
    fun `relative names a path from the root`() {
        assertEquals("", files.relative(project.path))
        assertEquals("src/app", files.relative(File(project, "src/app").path))
        assertEquals("src", files.relative(File(project, "src/app/..").path))
    }

    @Test
    fun `checkName accepts ordinary names and refuses the rest`() {
        listOf("a", "notes..md", ".env", "a b", "ñame.kt", "x".repeat(255)).forEach {
            assertNull(ProjectFiles.checkName(it), it)
        }
        assertEquals(FileProblem.EmptyName, ProjectFiles.checkName(""))
        listOf(".", "..", "a/b", "/", "a\u0000b", "x".repeat(256), "é".repeat(128)).forEach {
            assertEquals(FileProblem.BadName, ProjectFiles.checkName(it), it)
        }
    }

    @Test
    fun `natural order compares digit runs by value and ignores case`() {
        val sorted = listOf("a10", "a2", "A1", "a01", "b", "B", "a", "a2b", "a2a", "10", "9", "").sortedWith(NaturalOrder)
        assertEquals(listOf("", "9", "10", "a", "A1", "a01", "a2", "a2a", "a2b", "a10", "B", "b"), sorted)
    }

    @Test
    fun `natural order is consistent in both directions`() {
        val names = listOf("a", "A", "a1", "a01", "a001", "b2", "B10", "x", "x9999999999999999999999")
        for (x in names) for (y in names) {
            assertEquals(Integer.signum(NaturalOrder.compare(x, y)), -Integer.signum(NaturalOrder.compare(y, x)), "$x $y")
        }
    }
}
