package com.vscodroid.diligent.run

import com.vscodroid.diligent.TaskCause
import com.vscodroid.diligent.TaskInfo
import com.vscodroid.diligent.TaskState

/**
 * When the Run screen offers "Run on <name> instead": a profile on the phone,
 * a laptop set up, and the phone visibly struggling with it. That is two
 * low-memory kills of its session, or a step (a profile without a port: a
 * build, a test run) still going after three minutes. A server with a port
 * is meant to run for hours, so its running time says nothing.
 */
object OffloadHint {
    const val LOW_MEMORY_KILLS = 2
    const val LONG_STEP_MS = 3 * 60 * 1000L

    /**
     * [count] updated with what the supervisor says of a session now
     * ([current]) and said last time ([previous]): one more when it newly
     * names low memory, restarted it again, or gave up on it.
     */
    fun countKills(count: Int, previous: TaskInfo?, current: TaskInfo?): Int {
        if (current?.cause != TaskCause.LowMemory) return count
        val again = previous == null ||
            previous.cause != TaskCause.LowMemory ||
            current.restarts > previous.restarts ||
            (current.state == TaskState.Failed && previous.state != TaskState.Failed)
        return if (again) count + 1 else count
    }

    fun offer(onPhone: Boolean, hasLaptop: Boolean, lowMemoryKills: Int, runningForMs: Long?, hasPort: Boolean): Boolean =
        onPhone && hasLaptop &&
            (lowMemoryKills >= LOW_MEMORY_KILLS || (!hasPort && runningForMs != null && runningForMs > LONG_STEP_MS))
}
