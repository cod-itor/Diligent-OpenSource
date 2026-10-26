package com.vscodroid.diligent.run

import com.vscodroid.diligent.TaskCause
import com.vscodroid.diligent.TaskInfo
import com.vscodroid.diligent.TaskState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OffloadHintTest {

    private fun task(state: TaskState, cause: TaskCause?, restarts: Int) =
        TaskInfo("diligent-app-dev", "dev", "app", state, cause, null, restarts)

    @Test
    fun `counts each low-memory kill once`() {
        var n = 0
        val killed = task(TaskState.Restarting, TaskCause.LowMemory, 1)
        n = OffloadHint.countKills(n, null, killed)
        assertEquals(1, n)
        n = OffloadHint.countKills(n, killed, killed)
        assertEquals(1, n, "the same report again is not a new kill")
        val running = task(TaskState.Running, null, 1)
        n = OffloadHint.countKills(n, killed, running)
        assertEquals(1, n)
        n = OffloadHint.countKills(n, running, task(TaskState.Restarting, TaskCause.LowMemory, 2))
        assertEquals(2, n)
        n = OffloadHint.countKills(n, task(TaskState.Restarting, TaskCause.LowMemory, 2), task(TaskState.Failed, TaskCause.LowMemory, 2))
        assertEquals(3, n)
        assertEquals(3, OffloadHint.countKills(3, null, task(TaskState.Failed, TaskCause.PortBusy, 0)))
    }

    @Test
    fun `offered after two kills or a long step, with a laptop, on the phone`() {
        assertTrue(OffloadHint.offer(onPhone = true, hasLaptop = true, lowMemoryKills = 2, runningForMs = null, hasPort = true))
        assertFalse(OffloadHint.offer(onPhone = true, hasLaptop = true, lowMemoryKills = 1, runningForMs = null, hasPort = true))
        assertTrue(OffloadHint.offer(onPhone = true, hasLaptop = true, lowMemoryKills = 0, runningForMs = 3 * 60_000L + 1, hasPort = false))
        assertFalse(OffloadHint.offer(onPhone = true, hasLaptop = true, lowMemoryKills = 0, runningForMs = 3 * 60_000L, hasPort = false))
        assertFalse(OffloadHint.offer(onPhone = true, hasLaptop = true, lowMemoryKills = 0, runningForMs = 60 * 60_000L, hasPort = true), "a server runs long on purpose")
        assertFalse(OffloadHint.offer(onPhone = true, hasLaptop = false, lowMemoryKills = 5, runningForMs = null, hasPort = true))
        assertFalse(OffloadHint.offer(onPhone = false, hasLaptop = true, lowMemoryKills = 5, runningForMs = null, hasPort = true))
    }
}
