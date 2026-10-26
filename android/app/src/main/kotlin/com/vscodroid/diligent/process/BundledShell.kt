package com.vscodroid.diligent.process

import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.PortFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How a command ended, with the end of what it printed. */
data class ShellResult(val exitCode: Int, val lastLines: List<String>) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * Runs a command the way the editor's terminal would: the bundled bash, with
 * the terminal's environment (PATH to the bundled git, node, python and tmux,
 * HOME, the toolchains) and the terminal's exec interceptor preloaded, so
 * scripts and binaries under the app's files directory (git hooks,
 * `node_modules/.bin`, a venv's console scripts) run as they do there.
 * Diligent's screens use it for what they do without the terminal: cloning a
 * repository, starting and stopping a run.
 */
class BundledShell(private val context: Context) {

    /**
     * Runs `bash -c [script]` in [cwd], off the main thread, and suspends until
     * it ends. Every line it prints (stdout and stderr together) goes to
     * [onLine] as it arrives, on a background thread; the last [keepLines] come
     * back in the result. Nothing is read from stdin.
     *
     * Cancelling the coroutine ends the command and everything it started: the
     * script runs as its own process group (see [wrap]), the whole group gets
     * SIGTERM, and bash gets SIGKILL if it is still there a moment later.
     */
    suspend fun run(
        script: String,
        cwd: File,
        keepLines: Int = DEFAULT_KEEP_LINES,
        onLine: (String) -> Unit = {},
    ): ShellResult = withContext(Dispatchers.IO) {
        val env = processEnvironment()
        val process = ProcessBuilder(shellPath(), "-c", wrap(script))
            .directory(cwd)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(env)
            }
            .start()
        // A command that reads stdin gets end of input, not a hang.
        process.outputStream.close()
        val stop = coroutineContext.job.invokeOnCompletion { cause -> if (cause != null) end(process) }
        val kept = ArrayDeque<String>()
        try {
            coroutineScope {
                val reader = launch {
                    try {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            onLine(line)
                            synchronized(kept) {
                                kept.addLast(line)
                                while (kept.size > keepLines) kept.removeFirst()
                            }
                        }
                    } catch (_: IOException) {
                        // The stream was closed under it: the command was ended,
                        // or something it left behind held the output open.
                    }
                }
                val code = runInterruptible { process.waitFor() }
                // Something the script left running in the background can hold
                // the output open after bash exits; do not wait for it forever.
                if (withTimeoutOrNull(OUTPUT_GRACE_MS) { reader.join() } == null) {
                    process.inputStream.close()
                    reader.cancelAndJoin()
                }
                ShellResult(code, synchronized(kept) { kept.toList() })
            }
        } finally {
            stop.dispose()
            if (process.isAlive) withContext(NonCancellable) { end(process) }
        }
    }

    /** SIGTERM to bash, whose trap passes it to the script's group, then SIGKILL. */
    private fun end(process: Process) {
        process.destroy()
        if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }

    private fun shellPath(): String {
        val bundled = Environment.getTerminalShellPath(context)
        return if (File(bundled).canExecute()) bundled else "/system/bin/sh"
    }

    private fun processEnvironment(): Map<String, String> {
        val env = Environment.buildProcessEnvironment(context, PortFinder.rememberedPort(context)).toMutableMap()
        // As terminal.integrated.env.linux does, and only when the file is
        // there: a preload that cannot be found aborts every exec, bash too.
        val preload = Environment.getExecPreloadPath(context)
        if (File(preload).isFile) env["LD_PRELOAD"] = preload
        return env
    }

    companion object {
        const val DEFAULT_KEEP_LINES = 200
        private const val OUTPUT_GRACE_MS = 1_000L
        private const val KILL_GRACE_MS = 2_000L

        /**
         * [script] run as its own process group, so it can be ended whole.
         *
         * A process Java starts shares the app's process group, so `kill 0`
         * from it would signal the app itself. With job control on (`set -m`),
         * bash puts a background job in a group of its own; bash waits for it,
         * passes SIGTERM to that group, and exits with the job's status. Job
         * control is switched off again once the job has its group, or bash
         * would print "[1]+ Done" lines into the output. The
         * newline before `)` keeps a script ending in a comment from
         * swallowing it.
         */
        internal fun wrap(script: String): String =
            "set -m; ( $script\n) & pg=\$!; set +m; " +
                "trap 'kill -TERM -\$pg 2>/dev/null; exit 143' TERM; " +
                "wait \$pg"

        /**
         * [arg] as one bash word, whatever it contains: wrapped in single
         * quotes, with each single quote inside closed, escaped and reopened.
         * Every user-supplied value that goes into a script goes through this.
         */
        fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
    }
}
