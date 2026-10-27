package com.vscodroid.diligent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskUpdateTest {

    private val full = """
        {"tasks":[
          {"id":"diligent-web-dev","name":"dev","project":"web","state":"running","cause":null,"message":null,"restarts":0},
          {"id":"diligent-api-serve","name":"serve","project":"api","state":"restarting","cause":"killed",
           "message":"Android closed it in the background.","restarts":2}
        ],"processes":17,"processLimit":32}
    """.trimIndent()

    @Test
    fun `the supervisor's update is read in full`() {
        val update = TaskUpdate.parse(full)!!
        assertEquals(17, update.processes)
        assertEquals(32, update.processLimit)
        assertEquals(2, update.tasks.size)
        val dev = update.tasks[0]
        assertEquals(TaskInfo("diligent-web-dev", "dev", "web", TaskState.Running, null, null, 0), dev)
        val api = update.tasks[1]
        assertEquals(TaskState.Restarting, api.state)
        assertEquals(TaskCause.Killed, api.cause)
        assertEquals("Android closed it in the background.", api.message)
        assertEquals(2, api.restarts)
        assertEquals(listOf("diligent-web-dev", "diligent-api-serve"), update.active.map { it.id })
    }

    @Test
    fun `text that is not an update is ignored`() {
        for (bad in listOf(null, "", "  ", "not json", "[]", "42", "null", "{}", """{"tasks":{}}""", """{"tasks":"x"}""", "{\"tasks\":[")) {
            assertNull(TaskUpdate.parse(bad), "$bad")
        }
    }

    @Test
    fun `an empty task list is an update, which ends every task`() {
        val update = TaskUpdate.parse("""{"tasks":[]}""")!!
        assertTrue(update.tasks.isEmpty())
        assertNull(update.processes)
        assertEquals(TaskUpdate.DEFAULT_PROCESS_LIMIT, update.processLimit)
    }

    @Test
    fun `a broken task is skipped and the rest kept`() {
        val update = TaskUpdate.parse(
            """{"tasks":[
                 42, null, "x",
                 {"name":"no id","state":"running"},
                 {"id":"a","state":"sleeping"},
                 {"id":"b"},
                 {"id":7,"state":"running"},
                 {"id":"ok","state":"stopped"},
                 {"id":"ok","state":"running"}
               ]}""",
        )!!
        assertEquals(listOf("ok"), update.tasks.map { it.id })
        assertEquals(TaskState.Stopped, update.tasks.single().state)
    }

    @Test
    fun `missing and wrong fields fall back to safe values`() {
        val task = TaskUpdate.parse(
            """{"tasks":[{"id":" s1 ","state":"failed","name":"  ","project":5,"cause":"gremlins",
                "message":"","restarts":-3}],"processes":"many","processLimit":0}""",
        )!!.tasks.single()
        assertEquals("s1", task.id)
        assertEquals("s1", task.name)
        assertEquals("", task.project)
        assertNull(task.cause)
        assertNull(task.message)
        assertEquals(0, task.restarts)
        val update = TaskUpdate.parse("""{"tasks":[],"processes":2.5,"processLimit":-1}""")!!
        assertNull(update.processes)
        assertEquals(32, update.processLimit)
    }

    @Test
    fun `a runaway message is cut short`() {
        val long = "x".repeat(5000)
        val task = TaskUpdate.parse("""{"tasks":[{"id":"a","state":"failed","message":"$long"}]}""")!!.tasks.single()
        assertEquals(300, task.message!!.length)
    }

    @Test
    fun `only running and restarting tasks are active`() {
        val states = TaskState.entries.associateWith { TaskInfo("i", "n", "p", it, null, null, 0).active }
        assertEquals(
            mapOf(TaskState.Running to true, TaskState.Restarting to true, TaskState.Failed to false, TaskState.Stopped to false),
            states,
        )
    }

    @Test
    fun `a kill, or a failure with a named cause, sends the user to Health`() {
        fun task(state: TaskState, cause: TaskCause?) = TaskInfo("i", "n", "p", state, cause, null, 0)
        assertTrue(task(TaskState.Restarting, TaskCause.Killed).needsHealth)
        assertTrue(task(TaskState.Failed, TaskCause.Killed).needsHealth)
        assertTrue(task(TaskState.Failed, TaskCause.LowMemory).needsHealth)
        assertTrue(task(TaskState.Failed, TaskCause.Exit).needsHealth)
        assertFalse(task(TaskState.Failed, null).needsHealth)
        assertFalse(task(TaskState.Restarting, TaskCause.PortBusy).needsHealth)
        assertFalse(task(TaskState.Running, null).needsHealth)
    }

    @Test
    fun `a problem is reported once, and again when it changes`() {
        val killed = TaskUpdate.parse("""{"tasks":[{"id":"a","state":"restarting","cause":"killed"}]}""")!!
        assertEquals(listOf("a"), killed.newProblems(null).map { it.id })
        assertTrue(killed.newProblems(killed).isEmpty(), "the same kill repeated is not new")
        val failed = TaskUpdate.parse("""{"tasks":[{"id":"a","state":"failed","cause":"killed"}]}""")!!
        assertEquals(listOf("a"), failed.newProblems(killed).map { it.id })
        val running = TaskUpdate.parse("""{"tasks":[{"id":"a","state":"running"}]}""")!!
        assertTrue(running.newProblems(killed).isEmpty())
        assertEquals(listOf("a"), killed.newProblems(running).map { it.id }, "killed again after recovering")
    }
}
