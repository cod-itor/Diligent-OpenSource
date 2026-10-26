package com.vscodroid.diligent.process

import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.PortFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** How a command ended, with the end of what it printed. */
data class ShellResult(val exitCode: Int, val lastLines: List<String>) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * Runs a command the way the editor's terminal would: the bundled bash, with
 * the same environment (PATH to the bundled git, node, python and tmux, HOME,
 * the toolchains). Diligent's screens use it for the few things they do
 * without the terminal: cloning a repository, starting and stopping a run.
 *
 * The environment is VSCodroid's own ([Environment.buildProcessEnvironment]),
 * not a copy, so a tool the terminal can run is a tool this can run.
 */
class BundledShell(private val context: Context) {

    /**
     * Runs `bash -c [script]` in [cwd], off the main thread, and suspends until
     * it ends. Every line it prints (stdout and stderr together) goes to
     * [onLine] as it arrives; the last [keepLines] come back in the result.
     * Cancelling the coroutine kills the process.
     */
    suspend fun run(
        script: String,
        cwd: File,
        keepLines: Int = DEFAULT_KEEP_LINES,
        onLine: (String) -> Unit = {},
    ): ShellResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(Environment.getTerminalShellPath(context), "-c", script)
            .directory(cwd)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(Environment.buildProcessEnvironment(context, PortFinder.rememberedPort(context)))
            }
            .start()
        val kept = ArrayDeque<String>()
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    onLine(line)
                    kept.addLast(line)
                    while (kept.size > keepLines) kept.removeFirst()
                }
            }
            ShellResult(process.waitFor(), kept.toList())
        } finally {
            // Reached with the process alive only on cancellation or an error
            // reading it; either way nobody is waiting for it any more.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    companion object {
        const val DEFAULT_KEEP_LINES = 200

        /**
         * [arg] as one bash word, whatever it contains: wrapped in single
         * quotes, with each single quote inside closed, escaped and reopened.
         * Every user-supplied value that goes into a script goes through this.
         */
        fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
    }
}
