package com.vscodroid.diligent.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RunStateTest {

    private val dev = RunProfile("dev", "npm run dev", 3000)
    private val api = RunProfile("api", "python3 main.py", 8000)
    private val profiles = listOf(dev, api)

    @Test
    fun `the first profile is the default`() {
        assertEquals("dev", RunSelection.pick(profiles, emptySet(), null, null))
        assertNull(RunSelection.pick(emptyList(), emptySet(), null, null))
    }

    @Test
    fun `a running profile is found when the screen opens`() {
        assertEquals("api", RunSelection.pick(profiles, setOf("api"), null, null))
        assertEquals("api", RunSelection.pick(profiles, setOf("api"), null, "dev"))
    }

    @Test
    fun `the user's pick wins, while it exists`() {
        assertEquals("dev", RunSelection.pick(profiles, setOf("api"), "dev", "dev"))
        assertEquals("api", RunSelection.pick(profiles, setOf("api"), "gone", "dev"))
    }

    @Test
    fun `the current profile stays when nothing runs`() {
        assertEquals("api", RunSelection.pick(profiles, emptySet(), null, "api"))
        assertEquals("dev", RunSelection.pick(profiles, emptySet(), null, "gone"))
    }

    @Test
    fun `preview port only while running`() {
        val loaded = RunUiState(config = RunConfigState.Loaded(RunConfig(profiles)), active = "dev")
        assertNull(loaded.previewPort)
        assertEquals(3000, loaded.copy(status = RunStatus.Running).previewPort)
        assertNull(loaded.copy(status = RunStatus.Running, active = "nope").previewPort)
        assertNull(loaded.copy(status = RunStatus.Exited(1)).previewPort)
    }

    @Test
    fun `no run json means a detected proposal`(@TempDir dir: File) {
        File(dir, "package.json").writeText("""{ "scripts": { "dev": "vite" } }""")
        val state = RunFiles.load(dir)
        assertEquals(RunConfigState.Missing(DetectedRun(RunProfile("dev", "npm run dev", 5173), ProjectKind.Vite)), state)
        assertEquals(RunConfigState.Missing(null), RunFiles.load(File(dir, "empty").apply { mkdirs() }))
    }

    @Test
    fun `written profiles load back, and nothing is left beside them`(@TempDir dir: File) {
        val config = RunConfig(listOf(dev))
        RunFiles.write(dir, config)
        assertEquals(RunConfigState.Loaded(config), RunFiles.load(dir))
        assertEquals(listOf("run.json"), File(dir, ".diligent").list()!!.toList())
        RunFiles.write(dir, RunConfig(listOf(api)))
        assertEquals(RunConfigState.Loaded(RunConfig(listOf(api))), RunFiles.load(dir))
    }

    @Test
    fun `a broken run json is reported, not replaced by a guess`(@TempDir dir: File) {
        File(dir, "package.json").writeText("""{ "scripts": { "dev": "vite" } }""")
        File(dir, ".diligent").mkdirs()
        File(dir, ".diligent/run.json").writeText("""{ "profiles": [ { "name": "dev" } ] }""")
        assertEquals(RunConfigState.Invalid(RunConfigProblem.MissingCommand(0)), RunFiles.load(dir))
    }

    @Test
    fun `an empty profiles list falls back to detection`(@TempDir dir: File) {
        File(dir, "main.py").writeText("print(1)")
        File(dir, ".diligent").mkdirs()
        File(dir, ".diligent/run.json").writeText("""{ "profiles": [] }""")
        val state = RunFiles.load(dir)
        assertTrue(state is RunConfigState.Missing && state.proposal?.kind == ProjectKind.Python)
    }

    @Test
    fun `a huge file is not read`(@TempDir dir: File) {
        File(dir, "package.json").writeText("{\"scripts\":{\"dev\":\"vite\"},\"x\":\"" + "a".repeat((RunFiles.MAX_READ_BYTES + 1).toInt()) + "\"}")
        assertNull(RunFiles.detect(dir))
        assertFalse(File(dir, ".diligent").exists())
    }
}
