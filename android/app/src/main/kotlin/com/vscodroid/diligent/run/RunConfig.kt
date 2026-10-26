package com.vscodroid.diligent.run

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * One way to run a project: a shell command, and where its preview answers.
 *
 * [command] is shell text, run as the user typed it (pipes, `&&` and all);
 * it is the one value that is never quoted. [env] values, by contrast, are
 * data and always quoted. [where] is `phone` or `laptop:<name>` (see
 * [RunWhere]); a value this version does not know is kept as it is.
 */
data class RunProfile(
    val name: String,
    val command: String,
    val port: Int? = null,
    val where: String = WHERE_PHONE,
    val env: Map<String, String> = emptyMap(),
    /**
     * Shell text run before [command] when the project's lock file changed,
     * on a laptop only (`npm ci`, `pip install -r requirements.txt`). Like
     * [command], never quoted.
     */
    val prepare: String? = null,
) {
    companion object {
        const val WHERE_PHONE = "phone"
    }
}

/** What went wrong reading `.diligent/run.json`. The screen turns each into a sentence. */
sealed interface RunConfigProblem {
    /** Not JSON at all, or not an object. [detail] is the parser's own words, for the curious. */
    data class NotJson(val detail: String) : RunConfigProblem

    /** No `profiles` array. */
    data object NoProfiles : RunConfigProblem

    /** Profile number [index] (from 0) is not an object. */
    data class ProfileNotObject(val index: Int) : RunConfigProblem
    data class MissingName(val index: Int) : RunConfigProblem
    data class MissingCommand(val index: Int) : RunConfigProblem

    /** A port that is not a whole number from 1 to 65535. */
    data class BadPort(val index: Int) : RunConfigProblem
    data class BadWhere(val index: Int) : RunConfigProblem

    /** An `env` that is not an object of names to plain values, or a name a shell cannot export. */
    data class BadEnv(val index: Int, val key: String?) : RunConfigProblem
    data class DuplicateName(val name: String) : RunConfigProblem
}

/** The answer of [RunConfig.parse]. */
sealed interface RunConfigParse {
    data class Ok(val config: RunConfig) : RunConfigParse
    data class Invalid(val problem: RunConfigProblem) : RunConfigParse
}

/**
 * A project's run profiles, as stored in `.diligent/run.json`:
 *
 * ```json
 * { "profiles": [ { "name": "dev", "command": "npm run dev", "port": 3000, "where": "phone", "env": {} } ] }
 * ```
 *
 * The file belongs to the project, so it travels with the repository and a
 * user may edit it by hand. Hence the reading rules: fields this version does
 * not know are ignored rather than refused (a newer Diligent may have written
 * them), `where` defaults to `phone`, `port` may be left out, and anything
 * that would make a run do something other than what the file says is refused
 * with a problem the screen can name, never guessed at.
 *
 * The first profile is the default: the action bar's Run starts it.
 */
data class RunConfig(val profiles: List<RunProfile>) {

    /** The file's text: two-space indented, `/` left alone so a command reads as typed. */
    fun toJson(): String = buildString {
        append("{\n  \"profiles\": [")
        profiles.forEachIndexed { i, p ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\n")
            append("      \"name\": ").append(jsonString(p.name)).append(",\n")
            append("      \"command\": ").append(jsonString(p.command)).append(",\n")
            if (p.port != null) append("      \"port\": ").append(p.port).append(",\n")
            append("      \"where\": ").append(jsonString(p.where)).append(",\n")
            if (p.prepare != null) append("      \"prepare\": ").append(jsonString(p.prepare)).append(",\n")
            append("      \"env\": {")
            p.env.entries.forEachIndexed { j, (k, v) ->
                append(if (j == 0) " " else ", ").append(jsonString(k)).append(": ").append(jsonString(v))
            }
            append(if (p.env.isEmpty()) "}" else " }")
            append("\n    }")
        }
        append(if (profiles.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
    }

    companion object {
        const val DIR = ".diligent"
        const val FILE = "run.json"

        /** Where a project keeps its profiles. */
        fun fileIn(projectDir: File): File = File(File(projectDir, DIR), FILE)

        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Whether [name] can be exported by a shell, which is what the run script does with it. */
        fun isEnvName(name: String): Boolean = ENV_NAME.matches(name)

        fun isPort(n: Long): Boolean = n in 1..65535

        /**
         * A port typed into a form: blank means none, anything else must be a
         * port. Answers [Result.failure] for text that is neither.
         */
        fun parsePortField(text: String): Result<Int?> {
            val t = text.trim()
            if (t.isEmpty()) return Result.success(null)
            val n = t.toLongOrNull()?.takeIf { t.all(Char::isDigit) && isPort(it) }
                ?: return Result.failure(IllegalArgumentException("not a port: $text"))
            return Result.success(n.toInt())
        }

        fun parse(text: String): RunConfigParse {
            val root = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return RunConfigParse.Invalid(RunConfigProblem.NotJson(e.message.orEmpty()))
            }
            val array = root.opt("profiles") as? JSONArray
                ?: return RunConfigParse.Invalid(RunConfigProblem.NoProfiles)
            val profiles = ArrayList<RunProfile>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.opt(i) as? JSONObject
                    ?: return RunConfigParse.Invalid(RunConfigProblem.ProfileNotObject(i))
                profiles += try {
                    profile(obj, i)
                } catch (refused: Refused) {
                    return RunConfigParse.Invalid(refused.problem)
                }
            }
            profiles.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.let {
                return RunConfigParse.Invalid(RunConfigProblem.DuplicateName(it.key))
            }
            return RunConfigParse.Ok(RunConfig(profiles))
        }

        /** Carries the first problem out of [profile]; never escapes [parse]. */
        private class Refused(val problem: RunConfigProblem) : Exception(null, null, false, false)

        private fun refuse(problem: RunConfigProblem): Nothing = throw Refused(problem)

        private fun profile(obj: JSONObject, i: Int): RunProfile {
            val name = (obj.opt("name") as? String)?.trim()
            if (name.isNullOrEmpty()) refuse(RunConfigProblem.MissingName(i))
            val command = (obj.opt("command") as? String)?.trim()
            if (command.isNullOrEmpty()) refuse(RunConfigProblem.MissingCommand(i))

            val port: Int? = when (val raw = obj.opt("port")) {
                null, JSONObject.NULL -> null
                is Int, is Long -> (raw as Number).toLong().takeIf(::isPort)?.toInt()
                    ?: refuse(RunConfigProblem.BadPort(i))
                is Number -> raw.toDouble().takeIf { it % 1.0 == 0.0 && isPort(it.toLong()) }?.toInt()
                    ?: refuse(RunConfigProblem.BadPort(i))
                is String -> parsePortField(raw).getOrNull() ?: refuse(RunConfigProblem.BadPort(i))
                else -> refuse(RunConfigProblem.BadPort(i))
            }

            val where = when (val raw = obj.opt("where")) {
                null, JSONObject.NULL -> RunProfile.WHERE_PHONE
                is String -> raw.trim().ifEmpty { refuse(RunConfigProblem.BadWhere(i)) }
                else -> refuse(RunConfigProblem.BadWhere(i))
            }

            val env = LinkedHashMap<String, String>()
            when (val raw = obj.opt("env")) {
                null, JSONObject.NULL -> Unit
                is JSONObject -> for (key in raw.keys()) {
                    if (!isEnvName(key)) refuse(RunConfigProblem.BadEnv(i, key))
                    env[key] = when (val v = raw.opt(key)) {
                        is String -> v
                        is Boolean, is Int, is Long -> v.toString()
                        is Number -> v.toString()
                        else -> refuse(RunConfigProblem.BadEnv(i, key))
                    }
                }
                else -> refuse(RunConfigProblem.BadEnv(i, null))
            }
            // Optional, and only text: anything else is left out rather than refused.
            val prepare = (obj.opt("prepare") as? String)?.trim()?.ifEmpty { null }
            return RunProfile(name, command, port, where, env, prepare)
        }

        /**
         * [s] as a JSON string. Written out rather than taken from org.json,
         * whose Android build escapes every `/`, which turns a command such as
         * `python3 src/app.py` into `src\/app.py` in a file people read and edit.
         */
        internal fun jsonString(s: String): String = buildString(s.length + 2) {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}
