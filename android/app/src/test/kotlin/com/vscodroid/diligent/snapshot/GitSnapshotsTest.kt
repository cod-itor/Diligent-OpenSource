package com.vscodroid.diligent.snapshot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GitSnapshotsTest {

    @Test
    fun `reasons become one clean line`() {
        assertEquals("a b c", GitSnapshots.cleanReason("  a\n b\t\tc "))
        assertEquals("snapshot", GitSnapshots.cleanReason(" \n "))
        assertEquals(200, GitSnapshots.cleanReason("x".repeat(500)).length)
    }

    @Test
    fun `a hostile reason stays one quoted word`() {
        val script = GitSnapshots.takeScript("it's \$(touch /tmp/pwned) `id`; rm -rf ~")
        assertTrue(script.endsWith("diligent_take 'it'\\''s \$(touch /tmp/pwned) `id`; rm -rf ~'"), script)
    }

    @Test
    fun `restore refuses anything that is not a commit id`() {
        assertThrows<IllegalArgumentException> { GitSnapshots.restoreScript("HEAD; rm -rf ~") }
        assertThrows<IllegalArgumentException> { GitSnapshots.restoreScript("--orphan") }
    }

    @Test
    fun `the take output is read back`() {
        val c = "a".repeat(40)
        assertEquals(c, GitSnapshots.parseTake(listOf("noise", "snapshot $c")))
        assertNull(GitSnapshots.parseTake(listOf("snapshot nothex")))
        assertNull(GitSnapshots.parseTake(emptyList()))
    }

    // Against a real Git, with the Mac's or the runner's bash.

    @TempDir
    lateinit var dir: File

    private fun hasGit(): Boolean = try {
        ProcessBuilder("git", "--version").start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun bash(script: String): Pair<Int, String> {
        val p = ProcessBuilder("/bin/bash", "-c", script).directory(dir).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun git(vararg args: String): String = bash("git " + args.joinToString(" ") { com.vscodroid.diligent.process.BundledShell.quote(it) }).second.trim()

    @Test
    fun `outside a repository the take says so, and can make one`() {
        assumeTrue(hasGit())
        File(dir, "a.txt").writeText("one")
        val (code, _) = bash(GitSnapshots.takeScript("x"))
        assertEquals(GitSnapshots.NOT_A_REPOSITORY, code)
        val (code2, out) = bash(GitSnapshots.takeScript("x", initIfNeeded = true))
        assertEquals(0, code2, out)
        assertNotNull(GitSnapshots.parseTake(out.lines()))
        assertTrue(File(dir, ".git/info/exclude").readText().contains("node_modules/"))
    }

    @Test
    fun `take, take again, restore, as the core extension does`() {
        assumeTrue(hasGit())
        bash("git init -q")
        File(dir, "a.txt").writeText("one")
        File(dir, "staged.txt").writeText("s")
        bash("git add staged.txt")

        val (c1, o1) = bash(GitSnapshots.takeScript("ai session: claude's \"run\""))
        assertEquals(0, c1, o1)
        val first = GitSnapshots.parseTake(o1.lines())!!
        assertEquals(first, git("rev-parse", GitSnapshots.REF))
        assertEquals("diligent: ai session: claude's \"run\"", git("log", "-1", "--format=%s", GitSnapshots.REF))
        assertEquals("Diligent", git("log", "-1", "--format=%an", GitSnapshots.REF))
        // The user's staging area and HEAD are untouched.
        assertEquals("staged.txt", git("diff", "--cached", "--name-only"))
        assertEquals("", git("rev-parse", "-q", "--verify", "HEAD"))

        // Nothing changed: the same commit comes back, no new one.
        val (_, o2) = bash(GitSnapshots.takeScript("again"))
        assertEquals(first, GitSnapshots.parseTake(o2.lines()))

        File(dir, "a.txt").writeText("two")
        val (c3, o3) = bash(GitSnapshots.restoreScript(first))
        assertEquals(0, c3, o3)
        assertEquals("one", File(dir, "a.txt").readText())
        // A "before restore" snapshot keeps what was there, chained on the first.
        assertEquals("diligent: before restore", git("log", "-1", "--format=%s", GitSnapshots.REF))
        assertEquals(first, git("rev-parse", GitSnapshots.REF + "^"))
        assertNotEquals(first, git("rev-parse", GitSnapshots.REF))
        assertEquals("two", git("show", GitSnapshots.REF + ":a.txt"))
        assertEquals("staged.txt", git("diff", "--cached", "--name-only"))
        assertFalse(File(dir, ".git/index").readText(Charsets.ISO_8859_1).contains("a.txt"))
    }
}
