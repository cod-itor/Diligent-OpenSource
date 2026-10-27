package com.vscodroid.diligent.run

import android.content.res.AssetManager
import com.vscodroid.diligent.kit.KitRecipes
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A button under an explanation: [action] is one of the catalog's action ids. */
data class ErrorFix(val action: String, val label: String, val recipe: String? = null, val file: String? = null, val line: Int? = null)

/** One catalog entry that a log matched, with its placeholders filled. */
data class ErrorExplanation(
    val id: String,
    val title: String,
    val explanation: String,
    val fixes: List<ErrorFix>,
    val file: String? = null,
    val line: Int? = null,
)

/**
 * The kit extension's plain-language errors, `errors.json`, applied to a
 * run's log. A port of the extension's errors.js with the same rules (see
 * there): any pattern or exit code, plus every `with` pattern; `{{1}}` from
 * the pattern that matched; a location near the match gives `{{file}}`,
 * `{{line}}` and `explanationAt`, and without one `openFileAtLine` is dropped.
 */
class ErrorCatalog private constructor(private val entries: List<Entry>) {

    private class Entry(
        val id: String,
        val patterns: List<Regex>,
        val with: List<Regex>,
        val exitCodes: Set<Int>,
        val location: List<Regex>,
        val title: String,
        val explanation: String,
        val explanationAt: String?,
        val fixes: List<ErrorFix>,
    )

    val ids: List<String> get() = entries.map { it.id }

    /** Every entry [text] (and [exitCode]) matches, in catalog order. */
    fun explain(text: String, exitCode: Int? = null): List<ErrorExplanation> {
        val body = text.takeLast(MAX_TEXT).replace(CRLF, "\n")
        return entries.mapNotNull { e ->
            val hit = e.patterns.firstNotNullOfOrNull { it.find(body) }
            if (hit == null && (exitCode == null || exitCode !in e.exitCodes)) return@mapNotNull null
            if (!e.with.all { it.containsMatchIn(body) }) return@mapNotNull null
            val where = if (hit != null && e.location.isNotEmpty()) locate(body, hit.range.first, e.location) else null
            val values = buildMap {
                put("1", hit?.groupValues?.getOrNull(1).orEmpty())
                if (where != null) {
                    put("file", where.first)
                    put("line", where.second.toString())
                }
            }
            ErrorExplanation(
                id = e.id,
                title = fill(e.title, values),
                explanation = fill(if (where != null && e.explanationAt != null) e.explanationAt else e.explanation, values),
                fixes = e.fixes.mapNotNull { f ->
                    when {
                        f.action != OPEN_FILE_AT_LINE -> f
                        where == null -> null
                        else -> f.copy(file = where.first, line = where.second)
                    }
                },
                file = where?.first,
                line = where?.second,
            )
        }
    }

    private fun locate(text: String, index: Int, patterns: List<Regex>): Pair<String, Int>? {
        val lines = text.split('\n')
        val at = text.substring(0, index).count { it == '\n' }
        val near = lines.subList(maxOf(0, at - LOCATION_WINDOW), minOf(lines.size, at + LOCATION_WINDOW + 1)).joinToString("\n")
        for (p in patterns) {
            val m = p.find(near) ?: continue
            val file = m.groupValues.getOrNull(1).orEmpty()
            val line = m.groupValues.getOrNull(2)?.toIntOrNull()
            if (file.isNotEmpty() && line != null) return file to line
        }
        return null
    }

    companion object {
        const val FILE = "errors.json"
        const val OPEN_FILE_AT_LINE = "openFileAtLine"
        private const val MAX_TEXT = 256 * 1024
        private const val LOCATION_WINDOW = 6
        private val CRLF = Regex("\r\n?")
        private val PLACEHOLDER = Regex("""\{\{(\w+)\}\}""")

        private fun fill(text: String, values: Map<String, String>): String =
            PLACEHOLDER.replace(text) { values[it.groupValues[1]].orEmpty() }

        /** The catalog in [json], or null when it is not one. An entry with a broken pattern is skipped. */
        fun parse(json: String?): ErrorCatalog? {
            if (json.isNullOrBlank()) return null
            val list = try {
                JSONObject(json).optJSONArray("errors")
            } catch (_: JSONException) {
                null
            } ?: return null
            val entries = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(::entry) }
            return ErrorCatalog(entries)
        }

        private fun entry(o: JSONObject): Entry? {
            val id = o.optString("id").ifEmpty { return null }
            val options = buildSet {
                add(RegexOption.MULTILINE)
                add(RegexOption.UNIX_LINES)
                if (o.optBoolean("ignoreCase")) add(RegexOption.IGNORE_CASE)
            }
            return try {
                Entry(
                    id = id,
                    patterns = o.optJSONArray("patterns").strings().map { Regex(it, options) },
                    with = o.optJSONArray("with").strings().map { Regex(it, options) },
                    exitCodes = o.optJSONArray("exitCodes").let { a ->
                        if (a == null) emptySet() else (0 until a.length()).map { a.optInt(it, -1) }.toSet()
                    },
                    location = o.optJSONArray("location").strings().map { Regex(it, options) },
                    title = o.optString("title"),
                    explanation = o.optString("explanation"),
                    explanationAt = o.optString("explanationAt").ifEmpty { null },
                    fixes = o.optJSONArray("fixes").let { a ->
                        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fix) }
                    },
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun fix(o: JSONObject): ErrorFix? {
            val action = o.optString("action").ifEmpty { return null }
            return ErrorFix(action, o.optString("label"), o.optString("recipe").ifEmpty { null })
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

        /** The bundled catalog, from the newest kit extension. Touches the assets: call it off the main thread. */
        fun load(assets: AssetManager): ErrorCatalog? = try {
            KitRecipes.newestExtension(assets.list("extensions")?.toList().orEmpty())?.let { dir ->
                parse(assets.open("extensions/$dir/$FILE").bufferedReader().use { it.readText() })
            }
        } catch (_: IOException) {
            null
        }
    }
}
