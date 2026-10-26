package com.vscodroid.diligent.health

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One check as `diligentGetHealth` reports it: words already resolved. */
data class HealthLine(val id: String, val title: String, val state: String, val detail: String)

/**
 * The Health screen's checks as JSON, for `diligentGetHealth` (what
 * `kit doctor` prints):
 * `{"checks":[{"id","title","state":"pass|fail|unknown","detail"}]}`.
 *
 * [json] is pure and tested on the JVM; [read] adds the phone's facts and
 * the words.
 */
object HealthReport {
    /** A stable id per check, for scripts: it does not change with the language. */
    fun id(topic: HealthTopic): String = when (topic) {
        HealthTopic.Battery -> "battery"
        HealthTopic.ChildProcesses -> "child-processes"
        HealthTopic.ChildProcessesAdb -> "child-processes-adb"
        HealthTopic.Notifications -> "notifications"
        HealthTopic.Storage -> "storage"
        HealthTopic.Processes -> "processes"
    }

    fun state(status: HealthStatus): String = when (status) {
        HealthStatus.Pass -> "pass"
        HealthStatus.Fail -> "fail"
        HealthStatus.CannotCheck -> "unknown"
    }

    /** [checks] in order, each with its words from [title] and [detail]. */
    fun lines(checks: List<HealthCheck>, title: (HealthCheck) -> String, detail: (HealthCheck) -> String): List<HealthLine> =
        checks.map { HealthLine(id(it.topic), title(it), state(it.status), detail(it)) }

    fun json(lines: List<HealthLine>): String {
        val array = JSONArray()
        lines.forEach { line ->
            array.put(
                JSONObject()
                    .put("id", line.id)
                    .put("title", line.title)
                    .put("state", line.state)
                    .put("detail", line.detail),
            )
        }
        return JSONObject().put("checks", array).toString()
    }

    /**
     * Reads the phone and answers the JSON. Safe on any thread, including the
     * WebView's JavaBridge thread: [HealthProbe.read] makes guarded system
     * service and settings reads, none of which needs the UI thread, and the
     * words are string resources.
     */
    fun read(context: Context): String {
        val facts = HealthProbe.read(context)
        return json(
            lines(
                HealthRules.evaluate(facts),
                title = { HealthText.title(context, it) },
                detail = { HealthText.detail(context, it, facts) },
            ),
        )
    }
}
