package com.vscodroid.diligent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where a supervised task stands, as the diligent-core supervisor reports it. */
enum class TaskState(val key: String) {
    Running("running"),
    Restarting("restarting"),
    Failed("failed"),
    Stopped("stopped"),
    ;

    companion object {
        fun fromKey(key: String?): TaskState? = entries.firstOrNull { it.key == key }
    }
}

/** Why a task last ended, as the supervisor names it. */
enum class TaskCause(val key: String) {
    LowMemory("low-memory"),
    ProcessLimit("process-limit"),
    PortBusy("port-busy"),
    Killed("killed"),
    Exit("exit"),
    ;

    companion object {
        fun fromKey(key: String?): TaskCause? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One supervised task: a run profile in its tmux session.
 *
 * [id] is the tmux session's name, which is what `diligent.core.stopTask`
 * takes. [message] is a plain sentence for the user, or null.
 */
data class TaskInfo(
    val id: String,
    val name: String,
    val project: String,
    val state: TaskState,
    val cause: TaskCause?,
    val message: String?,
    val restarts: Int,
) {
    /** Running, or about to run again: what keeps the phone awake. */
    val active: Boolean get() = state == TaskState.Running || state == TaskState.Restarting

    /**
     * Ended by something the Health screen can explain: Android killed it, or
     * it failed for a named reason.
     */
    val needsHealth: Boolean
        get() = cause == TaskCause.Killed || (state == TaskState.Failed && cause != null)
}

/**
 * The supervisor's whole picture, sent through `diligentUpdateTasks` each time
 * it changes. [processes] is the app's process count when the supervisor could
 * read it, and [processLimit] the limit Android applies to it.
 */
data class TaskUpdate(
    val tasks: List<TaskInfo>,
    val processes: Int?,
    val processLimit: Int = DEFAULT_PROCESS_LIMIT,
) {
    val active: List<TaskInfo> get() = tasks.filter { it.active }

    /**
     * The tasks that need the Health screen now and did not in [previous]
     * (by id, state and cause), so one kill raises one notification however
     * many updates repeat it.
     */
    fun newProblems(previous: TaskUpdate?): List<TaskInfo> = tasks.filter { task ->
        task.needsHealth && previous?.tasks?.none {
            it.id == task.id && it.needsHealth && it.state == task.state && it.cause == task.cause
        } ?: true
    }

    companion object {
        /** Android's limit on an app's child processes since Android 12. */
        const val DEFAULT_PROCESS_LIMIT = 32

        /** Longest text kept from the page, so a runaway message cannot fill a notification. */
        private const val MAX_TEXT = 300

        /**
         * Parses the JSON the supervisor sends, or answers null when it is not
         * an object with a `tasks` array. A task without an id or with an
         * unknown state is skipped rather than failing the whole update; every
         * other field falls back to a safe value.
         */
        fun parse(json: String?): TaskUpdate? {
            if (json.isNullOrBlank()) return null
            val root = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            val array = root.optJSONArray("tasks") ?: return null
            return TaskUpdate(
                tasks = parseTasks(array),
                processes = root.optCount("processes"),
                processLimit = root.optCount("processLimit")?.takeIf { it > 0 } ?: DEFAULT_PROCESS_LIMIT,
            )
        }

        private fun parseTasks(array: JSONArray): List<TaskInfo> {
            val tasks = mutableListOf<TaskInfo>()
            val seen = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optText("id") ?: continue
                val state = TaskState.fromKey(item.optText("state")) ?: continue
                if (!seen.add(id)) continue
                tasks += TaskInfo(
                    id = id,
                    name = item.optText("name") ?: id,
                    project = item.optText("project") ?: "",
                    state = state,
                    cause = TaskCause.fromKey(item.optText("cause")),
                    message = item.optText("message"),
                    restarts = item.optCount("restarts") ?: 0,
                )
            }
            return tasks
        }

        /** A non-blank string field, trimmed and capped, or null. */
        private fun JSONObject.optText(key: String): String? {
            val value = opt(key) as? String ?: return null
            return value.trim().take(MAX_TEXT).takeIf { it.isNotEmpty() }
        }

        /** A whole, non-negative number field, or null. */
        private fun JSONObject.optCount(key: String): Int? {
            val value = opt(key) as? Number ?: return null
            val d = value.toDouble()
            if (d.isNaN() || d < 0 || d > Int.MAX_VALUE || d != Math.floor(d)) return null
            return d.toInt()
        }
    }
}
