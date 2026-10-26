package com.vscodroid.diligent.run

import org.json.JSONException
import org.json.JSONObject

/** What kind of project a detected profile was found in. */
enum class ProjectKind { NextJs, Vite, Node, Python }

/**
 * A profile [RunDetector] proposes for a project that has no `run.json`.
 * Nothing is written until the user accepts it.
 */
data class DetectedRun(
    val profile: RunProfile,
    val kind: ProjectKind,
    /**
     * The command had `-- --webpack` added because Next.js's default bundler,
     * Turbopack, does not run on Android. The screen explains this once per
     * project.
     */
    val webpackRewrite: Boolean = false,
)

/**
 * Guesses how to run a project from the files at its root, so a beginner who
 * opens a cloned Next.js app can press one button instead of writing JSON.
 *
 * Guesses only what the files say plainly. In order:
 * - `package.json` with a `dev` script, else a `start` script, run with npm.
 *   The port comes from a `--port N`, `-p N` or `PORT=N` in that script, else
 *   from the framework: Next.js 3000, Vite 5173 (4173 for `vite preview`).
 *   A Next.js `dev` gets `-- --webpack` (see [DetectedRun.webpackRewrite]).
 * - `main.py`, else `app.py`, run with `python3`. The port is read only from a
 *   literal `port=N` in a `.run(` call, or a framework's default when the file
 *   starts the server itself (`uvicorn.run(` 8000, Flask's `.run(` 5000).
 *   Anything cleverer (settings modules, env lookups) is not guessed at.
 *
 * "Nothing found" is a normal answer: the screen then offers an empty form.
 *
 * [readFile] reads a file at the project root by name and answers null when
 * there is none; the caller caps how much it reads.
 */
object RunDetector {

    /** How much of a Python file is looked at for a port. Enough for any entry point worth guessing about. */
    const val PYTHON_SCAN_BYTES = 64 * 1024

    const val NEXT_PORT = 3000
    const val VITE_PORT = 5173
    const val VITE_PREVIEW_PORT = 4173
    const val UVICORN_PORT = 8000
    const val FLASK_PORT = 5000

    const val WEBPACK_FLAG = "--webpack"

    fun detect(readFile: (String) -> String?): DetectedRun? =
        readFile("package.json")?.let(::fromPackageJson) ?: fromPython(readFile)

    internal fun fromPackageJson(text: String): DetectedRun? {
        val pkg = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return null
        }
        val scripts = pkg.optJSONObject("scripts") ?: return null
        val scriptName = listOf("dev", "start").firstOrNull { (scripts.opt(it) as? String)?.isNotBlank() == true }
            ?: return null
        val script = scripts.getString(scriptName)

        val deps = setOf("dependencies", "devDependencies")
            .flatMap { pkg.optJSONObject(it)?.keys()?.asSequence()?.toList().orEmpty() }
            .toSet()
        val isNext = "next" in deps || NEXT_COMMAND.containsMatchIn(script)
        val isVite = !isNext && ("vite" in deps || VITE_COMMAND.containsMatchIn(script))

        val kind = when {
            isNext -> ProjectKind.NextJs
            isVite -> ProjectKind.Vite
            else -> ProjectKind.Node
        }
        val port = portFlag(script) ?: when (kind) {
            ProjectKind.NextJs -> NEXT_PORT
            ProjectKind.Vite -> if (VITE_PREVIEW.containsMatchIn(script)) VITE_PREVIEW_PORT else VITE_PORT
            else -> null
        }

        val rewrite = kind == ProjectKind.NextJs && scriptName == "dev" && !script.contains(WEBPACK_FLAG)
        val base = if (scriptName == "start") "npm start" else "npm run $scriptName"
        val command = if (rewrite) "$base -- $WEBPACK_FLAG" else base
        return DetectedRun(RunProfile(scriptName, command, port), kind, webpackRewrite = rewrite)
    }

    private fun fromPython(readFile: (String) -> String?): DetectedRun? {
        for (file in listOf("main.py", "app.py")) {
            val source = readFile(file) ?: continue
            val head = source.take(PYTHON_SCAN_BYTES)
            return DetectedRun(
                RunProfile(file.removeSuffix(".py"), "python3 $file", pythonPort(head)),
                ProjectKind.Python,
            )
        }
        return null
    }

    internal fun pythonPort(source: String): Int? {
        RUN_CALL_PORT.find(source)?.let { m -> m.groupValues[1].toLongOrNull()?.takeIf(RunConfig::isPort)?.let { return it.toInt() } }
        return when {
            UVICORN_RUN.containsMatchIn(source) -> UVICORN_PORT
            FLASK_IMPORT.containsMatchIn(source) && DOT_RUN.containsMatchIn(source) -> FLASK_PORT
            else -> null
        }
    }

    /** A port given on the script's command line: `--port 8080`, `--port=8080`, `-p 8080`, `PORT=8080`. */
    internal fun portFlag(script: String): Int? {
        val m = PORT_FLAG.find(script) ?: return null
        val n = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toLongOrNull() ?: return null
        return n.takeIf(RunConfig::isPort)?.toInt()
    }

    private val NEXT_COMMAND = Regex("""(^|[\s;&|/])next\s+(dev|start)\b""")
    private val VITE_COMMAND = Regex("""(^|[\s;&|/])vite(\s|$)""")
    private val VITE_PREVIEW = Regex("""(^|[\s;&|/])vite\s+preview\b""")
    private val PORT_FLAG = Regex("""(?:^|\s)(?:--port[=\s]\s*|-p[=\s]\s*)(\d{1,5})\b|(?:^|[\s;&])PORT=(\d{1,5})\b""")
    private val RUN_CALL_PORT = Regex("""\.run\([^)]*?\bport\s*=\s*(\d{1,5})\b""")
    private val UVICORN_RUN = Regex("""\buvicorn\.run\(""")
    private val FLASK_IMPORT = Regex("""^\s*(from\s+flask\s+import|import\s+flask)\b""", RegexOption.MULTILINE)
    private val DOT_RUN = Regex("""\.run\(""")
}
