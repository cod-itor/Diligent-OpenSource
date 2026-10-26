package com.vscodroid.diligent.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BundledShellQuoteTest {

    @Test
    fun `plain text is wrapped`() {
        assertEquals("'hello world'", BundledShell.quote("hello world"))
    }

    @Test
    fun `a single quote cannot end the word`() {
        assertEquals("'it'\\''s'", BundledShell.quote("it's"))
    }

    @Test
    fun `shell syntax stays literal`() {
        val nasty = "\$(rm -rf ~); `id` && echo \"x\" | cat > /dev/null; \\n"
        assertEquals("'$nasty'", BundledShell.quote(nasty))
    }

    @Test
    fun `the empty string is still one word`() {
        assertEquals("''", BundledShell.quote(""))
    }
}

/**
 * The process-group wrapper, run through a real bash (present on the Mac and
 * on the Linux runners), since what it promises is about processes.
 */
class BundledShellWrapTest {

    private fun start(script: String): Process =
        ProcessBuilder("/bin/bash", "-c", BundledShell.wrap(script)).redirectErrorStream(true).start()

    @Test
    fun `the script's output and exit status come through`() {
        val p = start("echo one; echo two; exit 3")
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(3, p.waitFor())
        assertEquals("one\ntwo\n", out)
    }

    @Test
    fun `a script ending in a comment still runs`() {
        val p = start("echo ok # trailing comment")
        assertEquals("ok\n", p.inputStream.bufferedReader().readText())
        assertEquals(0, p.waitFor())
    }

    @Test
    fun `ending bash ends what the script started`() {
        val marker = java.io.File.createTempFile("bundled-shell", ".marker").apply { delete() }
        val p = start("sleep 3; touch ${BundledShell.quote(marker.path)}")
        Thread.sleep(500)
        p.destroy()
        assertEquals(true, p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS), "bash did not exit on SIGTERM")
        Thread.sleep(3_500)
        assertEquals(false, marker.exists(), "the script's child outlived the kill")
    }
}
