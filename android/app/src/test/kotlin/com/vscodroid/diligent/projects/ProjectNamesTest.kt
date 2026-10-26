package com.vscodroid.diligent.projects

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectNamesTest {

    private val nothingTaken: (String) -> Boolean = { false }

    @Test
    fun `ordinary names are fine, spaces and capitals included`() {
        listOf("shop", "My site", "hello-world", "v2.0", ".dotfiles", "привет").forEach {
            assertNull(ProjectNames.problem(it, nothingTaken), it)
        }
    }

    @Test
    fun `blank is empty`() {
        assertEquals(NameProblem.Empty, ProjectNames.problem("", nothingTaken))
        assertEquals(NameProblem.Empty, ProjectNames.problem("   ", nothingTaken))
    }

    @Test
    fun `a slash would make a folder somewhere else`() {
        assertEquals(NameProblem.Slash, ProjectNames.problem("a/b", nothingTaken))
        assertEquals(NameProblem.Slash, ProjectNames.problem("/abs", nothingTaken))
        assertEquals(NameProblem.Slash, ProjectNames.problem("nul\u0000l", nothingTaken))
    }

    @Test
    fun `dot names are reserved`() {
        assertEquals(NameProblem.Reserved, ProjectNames.problem(".", nothingTaken))
        assertEquals(NameProblem.Reserved, ProjectNames.problem(" .. ", nothingTaken))
    }

    @Test
    fun `the limit is in bytes, not characters`() {
        assertNull(ProjectNames.problem("a".repeat(255), nothingTaken))
        assertEquals(NameProblem.TooLong, ProjectNames.problem("a".repeat(256), nothingTaken))
        assertEquals(NameProblem.TooLong, ProjectNames.problem("é".repeat(128), nothingTaken))
    }

    @Test
    fun `a name in use is refused, checked after trimming`() {
        assertEquals(NameProblem.Exists, ProjectNames.problem(" shop ") { it == "shop" })
        assertEquals("shop", ProjectNames.clean("  shop\n"))
    }

    @Test
    fun `checks a real folder`(@TempDir dir: File) {
        File(dir, "taken").mkdir()
        assertEquals(NameProblem.Exists, ProjectNames.problemIn(dir, "taken"))
        assertNull(ProjectNames.problemIn(dir, "free"))
    }
}
