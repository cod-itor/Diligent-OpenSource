package com.vscodroid.diligent.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Setup screen's run, kept by the controller so it outlives the screen:
 * a user can leave for another tab while packages install and come back to
 * the progress.
 *
 * Starting sends `diligent.kit.add(recipe, name)` to the kit engine through
 * the editor; its answers come back through `diligentKitProgress` into
 * [onProgress]. Main thread only.
 *
 * [send] runs an allowlisted editor command (EditorChannel).
 */
class KitSession(
    private val scope: CoroutineScope,
    private val send: (command: String, args: Array<Any?>) -> Unit,
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
) {
    /** The run on screen, or null when none was started (or it was put away). */
    var run by mutableStateOf<KitRun?>(null)
        private set

    private var waiting: Job? = null

    /** Whether a run is going, so a second one is not started over it. */
    val busy: Boolean get() = run?.finished == false

    fun start(recipe: String, projectName: String) {
        if (busy) return
        run = KitRun(recipe = recipe, projectName = projectName)
        send(COMMAND_KIT_ADD, arrayOf(recipe, projectName))
        waiting?.cancel()
        waiting = scope.launch {
            delay(answerTimeoutMs)
            val current = run
            if (current != null && current.runId == null && !current.finished) run = current.copy(timedOut = true)
        }
    }

    /** Asks the engine to stop the run. It answers with a last message, `done` not ok. */
    fun cancel() {
        val current = run ?: return
        val id = current.runId
        if (id == null) {
            // Nothing heard yet, so nothing to name: put the run away.
            waiting?.cancel()
            run = null
            return
        }
        send(COMMAND_KIT_CANCEL, arrayOf(id))
    }

    fun onProgress(progress: KitProgress) {
        val current = run ?: return
        val next = current.reduce(progress)
        if (next.runId != null) waiting?.cancel()
        run = next
    }

    /** Back to the recipe list: forgets a finished run. A run still going is kept. */
    fun dismiss() {
        if (busy) return
        waiting?.cancel()
        run = null
    }

    companion object {
        /** Commands of the kit engine, allowed by the diligent-bridge extension. */
        const val COMMAND_KIT_ADD = "diligent.kit.add"
        const val COMMAND_KIT_CANCEL = "diligent.kit.cancel"

        /**
         * How long to wait for the engine's first message. The editor may
         * still be starting, and EditorChannel holds the command until it is.
         */
        const val ANSWER_TIMEOUT_MS = 60_000L
    }
}
