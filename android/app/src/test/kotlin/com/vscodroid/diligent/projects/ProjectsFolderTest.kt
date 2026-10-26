package com.vscodroid.diligent.projects

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectsFolderTest {

    @Test
    fun `creates an empty folder with the trimmed name`(@TempDir dir: File) {
        val result = ProjectsFolder.create(dir, "  My site ")
        assertEquals(CreateResult.Created(File(dir, "My site")), result)
        assertTrue(File(dir, "My site").isDirectory)
        assertEquals(0, File(dir, "My site").list()!!.size)
    }

    @Test
    fun `makes the projects folder on first use`(@TempDir dir: File) {
        val parent = File(dir, "projects")
        assertEquals(CreateResult.Created(File(parent, "first")), ProjectsFolder.create(parent, "first"))
    }

    @Test
    fun `never adopts an existing folder or file`(@TempDir dir: File) {
        File(dir, "shop").mkdir()
        File(dir, "shop/keep.txt").writeText("mine")
        File(dir, "notes").writeText("a file")
        assertEquals(CreateResult.Refused(NameProblem.Exists), ProjectsFolder.create(dir, "shop"))
        assertEquals(CreateResult.Refused(NameProblem.Exists), ProjectsFolder.create(dir, "notes"))
        assertEquals("mine", File(dir, "shop/keep.txt").readText())
    }

    @Test
    fun `a bad name makes nothing`(@TempDir dir: File) {
        assertEquals(CreateResult.Refused(NameProblem.Slash), ProjectsFolder.create(dir, "a/b"))
        assertEquals(CreateResult.Refused(NameProblem.Empty), ProjectsFolder.create(dir, " "))
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `lists visible folders by name`(@TempDir dir: File) {
        listOf("beta", "Alpha", ".cache", "gamma").forEach { File(dir, it).mkdir() }
        File(dir, "file.txt").writeText("")
        assertEquals(listOf("Alpha", "beta", "gamma"), ProjectsFolder.list(dir).map { File(it).name })
    }

    @Test
    fun `a missing projects folder lists nothing`(@TempDir dir: File) {
        assertEquals(emptyList<String>(), ProjectsFolder.list(File(dir, "nope")))
    }
}
