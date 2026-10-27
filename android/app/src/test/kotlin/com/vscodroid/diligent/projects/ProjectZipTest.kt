package com.vscodroid.diligent.projects

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectZipTest {

    @TempDir
    lateinit var dir: File

    private val projects: File get() = File(dir, "projects").apply { mkdirs() }

    private fun zipOf(name: String, vararg entries: Pair<String, String?>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, text) in entries) {
                zip.putNextEntry(ZipEntry(path))
                text?.let { zip.write(it.toByteArray()) }
                zip.closeEntry()
            }
        }
        return file
    }

    // -- What an export leaves out --

    @Test
    fun `packages, caches and build output are skipped at any depth`() {
        assertTrue(ProjectZip.skips("node_modules", isDirectory = true))
        assertTrue(ProjectZip.skips("web/node_modules/react/index.js", isDirectory = false))
        assertTrue(ProjectZip.skips(".venv/bin/python", isDirectory = false))
        assertTrue(ProjectZip.skips("src/__pycache__", isDirectory = true))
        assertTrue(ProjectZip.skips(".next/cache/x", isDirectory = false))
        assertTrue(ProjectZip.skips("dist/index.html", isDirectory = false))
        assertTrue(ProjectZip.skips("app/main.cpython-312.pyc", isDirectory = false))
    }

    @Test
    fun `source files, git and look-alike names are kept`() {
        assertFalse(ProjectZip.skips("index.html", isDirectory = false))
        assertFalse(ProjectZip.skips("src/dist.js", isDirectory = false))
        assertFalse(ProjectZip.skips("docs/node_modules.md", isDirectory = false))
        assertFalse(ProjectZip.skips(".git/objects/ab/cdef", isDirectory = false))
        assertFalse(ProjectZip.skips(".gitignore", isDirectory = false))
        assertFalse(ProjectZip.skips("", isDirectory = true))
    }

    @Test
    fun `an export holds the sources under one top folder`() {
        val root = File(dir, "shop").apply { mkdirs() }
        File(root, "index.html").writeText("<h1>hi</h1>")
        File(root, "src").mkdirs()
        File(root, "src/app.js").writeText("go()")
        File(root, "node_modules/react").mkdirs()
        File(root, "node_modules/react/index.js").writeText("big")
        File(root, ".git").mkdirs()
        File(root, ".git/HEAD").writeText("ref: refs/heads/main")
        Files.createSymbolicLink(File(root, "link").toPath(), File("/etc/hosts").toPath())

        val out = ByteArrayOutputStream()
        assertEquals(3, ProjectZip.export(root, out))
        val names = ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toSet()
        }
        assertEquals(setOf("shop/index.html", "shop/src/app.js", "shop/.git/HEAD"), names)
    }

    // -- Names inside a zip --

    @Test
    fun `entry names escaping the folder are refused`() {
        assertNull(ProjectZip.entryPath("../evil"))
        assertNull(ProjectZip.entryPath("a/../../evil"))
        assertNull(ProjectZip.entryPath("a/../b"))
        assertNull(ProjectZip.entryPath("/etc/passwd"))
        assertNull(ProjectZip.entryPath("C:\\Windows\\x"))
        assertNull(ProjectZip.entryPath("..\\evil"))
        assertNull(ProjectZip.entryPath("a\u0000b"))
    }

    @Test
    fun `ordinary entry names are tidied`() {
        assertEquals("a/b.txt", ProjectZip.entryPath("a/b.txt"))
        assertEquals("a/b.txt", ProjectZip.entryPath("./a//b.txt"))
        assertEquals("a/b", ProjectZip.entryPath("a\\b"))
        assertEquals("site/", ProjectZip.entryPath("site/") + "/")
        assertEquals("", ProjectZip.entryPath("./"))
        assertEquals("..hidden", ProjectZip.entryPath("..hidden"))
    }

    @Test
    fun `one top folder is found only when everything is in it`() {
        assertEquals("site", ProjectZip.commonTop(listOf("site" to true, "site/a" to false)))
        assertEquals("site", ProjectZip.commonTop(listOf("site/a" to false, "site/b/c" to false)))
        assertNull(ProjectZip.commonTop(listOf("site/a" to false, "readme" to false)))
        assertNull(ProjectZip.commonTop(listOf("a" to false)))
        assertNull(ProjectZip.commonTop(listOf("site" to true)))
    }

    // -- Import --

    @Test
    fun `an exported project imports back under its own name`() {
        val root = File(dir, "shop").apply { mkdirs() }
        File(root, "src").mkdirs()
        File(root, "src/app.js").writeText("go()")
        val zip = File(dir, "shop.zip")
        zip.outputStream().use { ProjectZip.export(root, it) }

        val result = ProjectZip.import(zip, projects, "fallback")
        assertInstanceOf(ZipImport.Imported::class.java, result)
        val folder = (result as ZipImport.Imported).folder
        assertEquals(File(projects, "shop"), folder)
        assertEquals("go()", File(folder, "src/app.js").readText())
        assertEquals(listOf("shop"), projects.list()?.toList())
    }

    @Test
    fun `a name that is taken gets a suffix and the old project is untouched`() {
        File(projects, "shop").mkdirs()
        File(projects, "shop/keep.txt").writeText("mine")
        File(projects, "shop-2").mkdirs()
        val zip = zipOf("in.zip", "shop/new.txt" to "theirs")

        val result = ProjectZip.import(zip, projects, "fallback") as ZipImport.Imported
        assertEquals(File(projects, "shop-3"), result.folder)
        assertEquals("theirs", File(projects, "shop-3/new.txt").readText())
        assertEquals("mine", File(projects, "shop/keep.txt").readText())
        assertFalse(File(projects, "shop/new.txt").exists())
    }

    @Test
    fun `a zip without one top folder is named after the file`() {
        val zip = zipOf("notes.zip", "a.txt" to "1", "b/c.txt" to "2")
        val result = ProjectZip.import(zip, projects, "notes") as ZipImport.Imported
        assertEquals(File(projects, "notes"), result.folder)
        assertEquals("2", File(result.folder, "b/c.txt").readText())
    }

    @Test
    fun `zip slip refuses the whole zip and writes nothing`() {
        val zip = zipOf("evil.zip", "site/ok.txt" to "fine", "site/../../escaped.txt" to "bad")
        val result = ProjectZip.import(zip, projects, "evil")
        assertEquals(ZipImport.Unsafe("site/../../escaped.txt"), result)
        assertFalse(File(dir, "escaped.txt").exists())
        assertFalse(File(projects.parentFile, "escaped.txt").exists())
        assertEquals(emptyList<String>(), projects.list()?.toList())
    }

    @Test
    fun `absolute entries are refused`() {
        val zip = zipOf("abs.zip", "/tmp/diligent-zip-test.txt" to "bad")
        assertInstanceOf(ZipImport.Unsafe::class.java, ProjectZip.import(zip, projects, "abs"))
        assertEquals(emptyList<String>(), projects.list()?.toList())
    }

    @Test
    fun `symbolic links in a zip are left out`() {
        val zip = zipOf("links.zip", "site/index.html" to "hi", "site/link" to "/etc/passwd")
        markAsLink(zip, "site/link")

        val result = ProjectZip.import(zip, projects, "links") as ZipImport.Imported
        assertEquals(1, result.skippedLinks)
        assertTrue(File(result.folder, "index.html").isFile)
        assertFalse(File(result.folder, "link").exists())
        assertFalse(Files.isSymbolicLink(File(result.folder, "link").toPath()))
    }

    @Test
    fun `a file that is not a zip, or an empty zip, says so`() {
        val text = File(dir, "x.zip").apply { writeText("not a zip at all, just text") }
        assertEquals(ZipImport.NotAZip, ProjectZip.import(text, projects, "x"))
        val empty = zipOf("e.zip", "folder/" to null)
        assertEquals(ZipImport.Empty, ProjectZip.import(empty, projects, "e"))
        assertEquals(emptyList<String>(), projects.list()?.toList())
    }

    /**
     * Sets the central directory entry for [name] to a Unix symbolic link,
     * which ZipOutputStream cannot write: made-by Unix, mode `S_IFLNK | 0777`.
     */
    private fun markAsLink(zip: File, name: String) {
        val bytes = zip.readBytes()
        val nameBytes = name.toByteArray()
        var i = 0
        while (i < bytes.size - 46) {
            val signature = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
            if (signature == 0x02014b50) {
                val length = (bytes[i + 28].toInt() and 0xFF) or ((bytes[i + 29].toInt() and 0xFF) shl 8)
                if (length == nameBytes.size && bytes.copyOfRange(i + 46, i + 46 + length).contentEquals(nameBytes)) {
                    bytes[i + 5] = 3 // made by Unix
                    val mode = 0xA1FF
                    bytes[i + 40] = (mode and 0xFF).toByte()
                    bytes[i + 41] = (mode shr 8).toByte()
                    zip.writeBytes(bytes)
                    return
                }
            }
            i++
        }
        error("no central directory entry for $name")
    }
}
