package com.vscodroid.diligent.kit

import org.json.JSONException
import org.json.JSONObject

/** Where one recipe step stands. [Pending] is Diligent's own: a step not reported yet. */
enum class KitStepState(val key: String?) {
    Pending(null),
    Running("running"),
    Ok("ok"),
    Fail("fail"),
    ;

    companion object {
        fun fromKey(key: String?): KitStepState? = entries.firstOrNull { it.key != null && it.key == key }
    }
}

/** The step a progress message is about: [index] of [total], 0-based. */
data class KitStepReport(val index: Int, val total: Int, val title: String, val state: KitStepState)

/** How a run ended. [message] is a plain sentence for the user. */
data class KitDone(val ok: Boolean, val projectPath: String?, val message: String?)

/**
 * One message from the kit engine through `diligentKitProgress`:
 * `{"runId","recipe","project","step":{"index","total","title","state"},"line","done"}`.
 */
data class KitProgress(
    val runId: String,
    val recipe: String?,
    val project: String?,
    val step: KitStepReport?,
    val line: String?,
    val done: KitDone?,
) {
    companion object {
        /** Longest text kept from the engine, so a runaway line cannot fill the screen. */
        private const val MAX_TEXT = 500

        /** Steps beyond this are not drawn; no recipe comes near it. */
        const val MAX_STEPS = 50

        /**
         * Parses the engine's JSON, or answers null when it is not an object
         * with a `runId`. A `step` that is incomplete is dropped and the rest
         * of the message kept; every other field falls back to null.
         */
        fun parse(json: String?): KitProgress? {
            if (json.isNullOrBlank()) return null
            val root = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            val runId = root.optText("runId") ?: return null
            return KitProgress(
                runId = runId,
                recipe = root.optText("recipe"),
                project = root.optText("project"),
                step = root.optJSONObject("step")?.let(::parseStep),
                line = root.optText("line"),
                done = root.optJSONObject("done")?.let(::parseDone),
            )
        }

        private fun parseStep(step: JSONObject): KitStepReport? {
            val index = step.optCount("index") ?: return null
            val total = step.optCount("total")?.takeIf { it in 1..MAX_STEPS } ?: return null
            if (index >= total) return null
            val state = KitStepState.fromKey(step.optText("state")) ?: return null
            return KitStepReport(index, total, step.optText("title").orEmpty(), state)
        }

        private fun parseDone(done: JSONObject): KitDone? {
            val ok = done.opt("ok") as? Boolean ?: return null
            return KitDone(ok, done.optText("projectPath"), done.optText("message"))
        }

        private fun JSONObject.optText(key: String): String? {
            val value = opt(key) as? String ?: return null
            return value.trim().take(MAX_TEXT).takeIf { it.isNotEmpty() }
        }

        private fun JSONObject.optCount(key: String): Int? {
            val value = opt(key) as? Number ?: return null
            val d = value.toDouble()
            if (d.isNaN() || d < 0 || d > Int.MAX_VALUE || d != Math.floor(d)) return null
            return d.toInt()
        }
    }
}

/** A step as the Setup screen lists it. [title] is empty until the engine names it. */
data class KitStepLine(val title: String, val state: KitStepState)

/**
 * A run the Setup screen started, as far as the engine has told: its steps,
 * the latest output line, and how it ended.
 *
 * [runId] is null until the first message, since `diligent.kit.add` does not
 * answer with one; that first message is matched by recipe and project name.
 * [project] is the project folder the engine named. [timedOut] is set when
 * the engine never answered at all.
 */
data class KitRun(
    val recipe: String,
    val projectName: String,
    val runId: String? = null,
    val project: String? = null,
    val steps: List<KitStepLine> = emptyList(),
    val line: String? = null,
    val done: KitDone? = null,
    val timedOut: Boolean = false,
    /** Prepare for offline rather than a new project: nothing to open at the end. */
    val prepare: Boolean = false,
) {
    val finished: Boolean get() = done != null || timedOut
    val succeeded: Boolean get() = done?.ok == true

    /** The finished project's folder: the end's path, else the one the engine named. */
    val projectPath: String? get() = done?.projectPath ?: project

    /** Whether [progress] is about this run rather than another (a `kit add` in the terminal). */
    fun accepts(progress: KitProgress): Boolean {
        if (runId != null) return progress.runId == runId
        if (progress.recipe != null && progress.recipe != recipe) return false
        val project = progress.project ?: return progress.recipe == recipe
        return project.trimEnd('/').substringAfterLast('/') == projectName
    }

    /**
     * This run after [progress]. Messages for another run, or after the run
     * ended, change nothing. A step reported running or done marks every
     * earlier step still running as done, since the engine runs them in order.
     */
    fun reduce(progress: KitProgress): KitRun {
        if (finished || !accepts(progress)) return this
        var steps = steps
        progress.step?.let { report ->
            val list = MutableList(report.total) { i -> steps.getOrNull(i) ?: KitStepLine("", KitStepState.Pending) }
            for (i in 0 until report.index) {
                if (list[i].state == KitStepState.Running || list[i].state == KitStepState.Pending) {
                    list[i] = list[i].copy(state = KitStepState.Ok)
                }
            }
            val title = report.title.ifEmpty { list[report.index].title }
            list[report.index] = KitStepLine(title, report.state)
            steps = list
        }
        val done = progress.done
        if (done != null && !done.ok) {
            // A failure stops the run where it was: a step left running is the one that failed.
            steps = steps.map { if (it.state == KitStepState.Running) it.copy(state = KitStepState.Fail) else it }
        }
        return copy(
            runId = progress.runId,
            project = progress.project ?: project,
            steps = steps,
            line = progress.line ?: line,
            done = done,
        )
    }
}
