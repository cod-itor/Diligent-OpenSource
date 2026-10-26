package com.vscodroid.diligent.projects

import com.vscodroid.diligent.process.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class GitCloneTest {

    private val scripts = mutableListOf<Pair<String, File>>()

    /** A git that writes a file into the folder it was given, says [lines], and exits with [exit]. */
    private fun fakeGit(exit: Int, vararg lines: String) = GitClone(
        runShell = { script, cwd, onLine ->
            scripts += script to cwd
            val folder = File(cwd, Regex("""'([^']*)' < /dev/null$""").find(script)!!.groupValues[1])
            File(folder, "README.md").writeText("hello")
            lines.forEach(onLine)
            ShellResult(exit, lines.toList())
        },
        io = Dispatchers.Unconfined,
    )

    @Test
    fun `the script quotes both values and ends the options`() {
        assertEquals(
            "GIT_TERMINAL_PROMPT=0 git clone --progress -- 'https://h/u/r.git' 'my site' < /dev/null",
            GitClone.script("https://h/u/r.git", "my site"),
        )
        assertEquals(
            "GIT_TERMINAL_PROMPT=0 git clone --progress -- 'x'\\''; rm -rf ~' 'a' < /dev/null",
            GitClone.script("x'; rm -rf ~", "a"),
        )
    }

    @Test
    fun `a clone that works keeps its folder and streams its lines`(@TempDir dir: File) = runBlocking {
        val seen = mutableListOf<String>()
        val outcome = fakeGit(0, "Cloning into 'shop'...", "Receiving objects: 100%").clone(" https://h/u/shop.git ", dir, "shop", seen::add)
        assertEquals(CloneOutcome.Done(File(dir, "shop")), outcome)
        assertEquals("hello", File(dir, "shop/README.md").readText())
        assertEquals(listOf("Cloning into 'shop'...", "Receiving objects: 100%"), seen)
        assertEquals(dir, scripts.single().second)
        assertTrue(scripts.single().first.contains("-- 'https://h/u/shop.git' 'shop'"))
    }

    @Test
    fun `a failed clone removes its folder and reports the last lines`(@TempDir dir: File) = runBlocking {
        val outcome = fakeGit(128, "fatal: repository not found").clone("https://h/u/x.git", dir, "x")
        assertEquals(CloneOutcome.Failed(listOf("fatal: repository not found")), outcome)
        assertFalse(File(dir, "x").exists())
    }

    @Test
    fun `an existing folder is never cloned over, or removed`(@TempDir dir: File) = runBlocking {
        File(dir, "shop").mkdir()
        File(dir, "shop/mine.txt").writeText("keep")
        val outcome = fakeGit(0).clone("https://h/u/shop.git", dir, "shop")
        assertEquals(CloneOutcome.Refused(NameProblem.Exists), outcome)
        assertTrue(scripts.isEmpty())
        assertEquals("keep", File(dir, "shop/mine.txt").readText())
    }

    @Test
    fun `a bad name runs nothing`(@TempDir dir: File) = runBlocking {
        assertEquals(CloneOutcome.Refused(NameProblem.Slash), fakeGit(0).clone("https://h/u/a.git", dir, "a/b"))
        assertTrue(scripts.isEmpty())
    }

    @Test
    fun `cancelling removes the partial folder`(@TempDir dir: File) {
        val started = CompletableDeferred<Unit>()
        val clone = GitClone(
            runShell = { _, cwd, _ ->
                File(cwd, "big/partial.pack").writeText("half")
                started.complete(Unit)
                awaitCancellation()
            },
            io = Dispatchers.IO,
        )
        runBlocking {
            val job = async(Dispatchers.IO) { clone.clone("https://h/u/big.git", dir, "big") }
            started.await()
            job.cancel()
            assertThrows<CancellationException> { runBlocking { job.await() } }
        }
        assertFalse(File(dir, "big").exists())
    }

    @Test
    fun `cleanup removes links without following them`(@TempDir dir: File) {
        val outside = File(dir, "other-project").apply { mkdir() }
        File(outside, "precious.txt").writeText("keep")
        val partial = File(dir, "partial").apply { mkdir() }
        Files.createSymbolicLink(File(partial, "link").toPath(), outside.toPath())
        File(partial, "sub").mkdir()
        File(partial, "sub/file").writeText("x")

        GitClone.removeTree(partial.toPath())

        assertFalse(partial.exists())
        assertEquals("keep", File(outside, "precious.txt").readText())
    }

    @Test
    fun `removing nothing is fine`(@TempDir dir: File) {
        GitClone.removeTree(File(dir, "absent").toPath())
        assertNull(dir.list()?.firstOrNull())
    }
}
