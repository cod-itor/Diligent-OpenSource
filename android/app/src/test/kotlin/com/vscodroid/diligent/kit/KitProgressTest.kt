package com.vscodroid.diligent.kit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KitProgressTest {

    private fun msg(
        index: Int? = 0,
        total: Int = 3,
        state: String = "running",
        title: String = "Install Node packages",
        line: String? = null,
        done: String = "null",
        runId: String = "r1",
        recipe: String = "react-vite",
        project: String = "/home/projects/my-app",
    ): String {
        val step = if (index == null) "null" else """{"index":$index,"total":$total,"title":"$title","state":"$state"}"""
        val lineJson = if (line == null) "null" else "\"$line\""
        return """{"runId":"$runId","recipe":"$recipe","project":"$project","step":$step,"line":$lineJson,"done":$done}"""
    }

    @Test
    fun `a full message is read`() {
        val p = KitProgress.parse(msg(index = 1, state = "ok", line = "added 120 packages"))!!
        assertEquals("r1", p.runId)
        assertEquals("react-vite", p.recipe)
        assertEquals("/home/projects/my-app", p.project)
        assertEquals(KitStepReport(1, 3, "Install Node packages", KitStepState.Ok), p.step)
        assertEquals("added 120 packages", p.line)
        assertNull(p.done)
    }

    @Test
    fun `the end of a run is read`() {
        val p = KitProgress.parse(msg(done = """{"ok":false,"projectPath":"/p/x","message":"No internet."}"""))!!
        assertEquals(KitDone(false, "/p/x", "No internet."), p.done)
        assertEquals(KitDone(true, null, null), KitProgress.parse(msg(done = """{"ok":true}"""))!!.done)
        assertNull(KitProgress.parse(msg(done = """{"ok":"yes"}"""))!!.done)
    }

    @Test
    fun `text that is not a message is ignored`() {
        for (bad in listOf(null, "", " ", "x", "[]", "42", "{}", """{"runId":3}""", """{"runId":"  "}""", "{\"runId\":")) {
            assertNull(KitProgress.parse(bad), "$bad")
        }
    }

    @Test
    fun `a broken step is dropped and the rest kept`() {
        for (step in listOf(
            """{"index":3,"total":3,"title":"x","state":"ok"}""",
            """{"index":-1,"total":3,"title":"x","state":"ok"}""",
            """{"index":0.5,"total":3,"title":"x","state":"ok"}""",
            """{"index":0,"total":0,"title":"x","state":"ok"}""",
            """{"index":0,"total":3,"title":"x","state":"done"}""",
            """{"total":3,"state":"ok"}""",
            "\"step\"",
        )) {
            val p = KitProgress.parse("""{"runId":"r","step":$step,"line":"hi"}""")!!
            assertNull(p.step, step)
            assertEquals("hi", p.line)
        }
    }

    @Test
    fun `steps fill in as the engine reports them`() {
        var run = KitRun("react-vite", "my-app")
        run = run.reduce(KitProgress.parse(msg(index = 0, title = "Check space"))!!)
        assertEquals("r1", run.runId)
        assertEquals(3, run.steps.size)
        assertEquals(KitStepLine("Check space", KitStepState.Running), run.steps[0])
        assertEquals(KitStepState.Pending, run.steps[2].state)

        // Step 2 starts: step 1, still running, is done.
        run = run.reduce(KitProgress.parse(msg(index = 1, title = "Install", line = "npm install"))!!)
        assertEquals(KitStepState.Ok, run.steps[0].state)
        assertEquals("Check space", run.steps[0].title)
        assertEquals(KitStepState.Running, run.steps[1].state)
        assertEquals("npm install", run.line)

        // A message without a line keeps the last one.
        run = run.reduce(KitProgress.parse(msg(index = 1, title = "Install"))!!)
        assertEquals("npm install", run.line)

        run = run.reduce(KitProgress.parse(msg(index = 2, state = "ok", title = "Test", done = """{"ok":true,"projectPath":"/home/projects/my-app","message":"Ready."}"""))!!)
        assertTrue(run.finished)
        assertTrue(run.succeeded)
        assertEquals(listOf(KitStepState.Ok, KitStepState.Ok, KitStepState.Ok), run.steps.map { it.state })
    }

    @Test
    fun `a failure marks the running step failed`() {
        var run = KitRun("react-vite", "my-app")
        run = run.reduce(KitProgress.parse(msg(index = 1))!!)
        run = run.reduce(KitProgress.parse(msg(index = null, done = """{"ok":false,"message":"npm could not reach the internet."}"""))!!)
        assertTrue(run.finished)
        assertFalse(run.succeeded)
        assertEquals(KitStepState.Fail, run.steps[1].state)
        assertEquals("npm could not reach the internet.", run.done?.message)
        // After the end, nothing changes it.
        assertSame(run, run.reduce(KitProgress.parse(msg(index = 2))!!))
    }

    @Test
    fun `another run is ignored`() {
        val start = KitRun("react-vite", "my-app")
        assertSame(start, start.reduce(KitProgress.parse(msg(recipe = "nextjs"))!!))
        assertSame(start, start.reduce(KitProgress.parse(msg(project = "/home/projects/other"))!!))
        val mine = start.reduce(KitProgress.parse(msg())!!)
        assertSame(mine, mine.reduce(KitProgress.parse(msg(runId = "r2"))!!))
        assertEquals("r1", mine.runId)
    }
}
