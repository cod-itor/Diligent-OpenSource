package com.vscodroid.diligent.kit

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** What a recipe needs from the internet, for the badge on its card. */
enum class KitInternet { No, Once, PackDownload }

/**
 * One recipe of the kit engine, as the Setup screen shows it: a card, then
 * the confirm sheet. [packs] are the toolchain packs it installs, [steps]
 * the titles of its steps that have one, and [minFreeMB] the free space it
 * asks for before it starts. [tested] is the versions it was last tested
 * with, which a prepared recipe must still match to be ready offline.
 */
data class KitRecipe(
    val id: String,
    val title: String,
    val summary: String,
    val sizeMB: Int?,
    val packs: List<String>,
    val minFreeMB: Int?,
    val internet: KitInternet,
    val steps: List<String>,
    val tested: Map<String, String> = emptyMap(),
)

/**
 * Reads the recipes the kit engine ships, from
 * `assets/extensions/diligent.diligent-kit-<version>/recipes/<id>.json`.
 * Parsing is pure and tested on the JVM; [load] adds the assets.
 */
object KitRecipes {
    /** The bundled kit extension's folder name, before its version. */
    const val EXTENSION_PREFIX = "diligent.diligent-kit-"

    private const val MAX_TEXT = 300

    /** The guide's order: no-internet basics first, then the bigger stacks, then the packs. */
    private val ORDER = listOf(
        "web-basic", "python-script", "python-data", "react-vite", "nextjs", "node-api", "java", "c-cpp", "ruby",
    )

    /**
     * One recipe, or null when it has no `id` or `title`. Every other field
     * falls back to a safe value.
     */
    fun parse(json: String?): KitRecipe? {
        if (json.isNullOrBlank()) return null
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return null
        }
        val id = root.optText("id") ?: return null
        val title = root.optText("title") ?: return null
        val needs = root.optJSONObject("needs")
        val packs = needs?.optJSONArray("toolchains")?.texts().orEmpty()
        return KitRecipe(
            id = id,
            title = title,
            summary = root.optText("summary").orEmpty(),
            sizeMB = root.optCount("sizeMB"),
            packs = packs,
            minFreeMB = needs?.optCount("minFreeMB"),
            internet = internet(needs?.opt("internet")),
            steps = root.optJSONArray("steps")?.let(::stepTitles).orEmpty(),
            tested = root.optJSONObject("tested")?.let(::textMap).orEmpty(),
        )
    }

    /**
     * The badge. Accepts the words a recipe might use: `false`, "no", "none"
     * or "offline"; `true`, "once" or "yes"; anything naming a pack. Missing
     * or unknown says Once, which never promises more than is true.
     */
    fun internet(value: Any?): KitInternet {
        if (value is Boolean) return if (value) KitInternet.Once else KitInternet.No
        val text = (value as? String)?.trim()?.lowercase() ?: return KitInternet.Once
        return when {
            text in setOf("no", "none", "never", "false", "offline") -> KitInternet.No
            "pack" in text -> KitInternet.PackDownload
            else -> KitInternet.Once
        }
    }

    /** [texts] parsed, without the broken ones or a repeated id, in the guide's order. */
    fun parseAll(texts: List<String>): List<KitRecipe> {
        val seen = mutableSetOf<String>()
        val recipes = texts.mapNotNull(::parse).filter { seen.add(it.id) }
        return recipes.sortedWith(
            compareBy<KitRecipe> { ORDER.indexOf(it.id).let { i -> if (i < 0) ORDER.size else i } }.thenBy { it.title },
        )
    }

    /** The newest kit extension among the asset folder [names], or null when there is none. */
    fun newestExtension(names: List<String>): String? = names
        .filter { it.startsWith(EXTENSION_PREFIX) }
        .maxWithOrNull(compareBy<String, List<Int>>(VersionOrder) { version(it) })

    /**
     * The bundled recipes. Touches the assets: call it off the main thread.
     * A missing extension or folder is no recipes, not an error.
     */
    fun load(assets: AssetManager): List<KitRecipe> = try {
        val dir = newestExtension(assets.list("extensions")?.toList().orEmpty())
        if (dir == null) {
            emptyList()
        } else {
            val folder = "extensions/$dir/recipes"
            val files = assets.list(folder)?.filter { it.endsWith(".json") }.orEmpty()
            parseAll(files.map { name -> assets.open("$folder/$name").bufferedReader().use { it.readText() } })
        }
    } catch (_: IOException) {
        emptyList()
    }

    private fun version(name: String): List<Int> =
        name.removePrefix(EXTENSION_PREFIX).split('.').map { it.toIntOrNull() ?: -1 }

    private object VersionOrder : Comparator<List<Int>> {
        override fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return 0
        }
    }

    private fun textMap(obj: JSONObject): Map<String, String> =
        obj.keys().asSequence().mapNotNull { key -> (obj.opt(key) as? String)?.let { key to it } }.toMap()

    private fun stepTitles(array: JSONArray): List<String> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optText("title") }

    private fun JSONArray.texts(): List<String> =
        (0 until length()).mapNotNull { (opt(it) as? String)?.trim()?.take(MAX_TEXT)?.takeIf(String::isNotEmpty) }

    private fun JSONObject.optText(key: String): String? {
        val value = opt(key) as? String ?: return null
        return value.trim().take(MAX_TEXT).takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optCount(key: String): Int? {
        val value = opt(key) as? Number ?: return null
        val d = value.toDouble()
        if (d.isNaN() || d < 0 || d > Int.MAX_VALUE) return null
        return Math.ceil(d).toInt()
    }
}

/**
 * Which recipes `kit add` can run without the internet, as the kit engine
 * decides it (engine.js `readyOffline`): one that needs no internet, one
 * whose language packs are installed and that downloads nothing else, or
 * one that `kit prepare` filled the caches for, with the versions it is
 * still tested with.
 *
 * Prepared recipes are in `~/.diligent/kit-offline.json`
 * (`{"recipes": {"<id>": {"preparedAt", "tested"}}}`), installed packs in
 * `~/.vscodroid/toolchains.json` (an array of `{"name": "java"}`).
 */
object KitOffline {
    const val FILE = ".diligent/kit-offline.json"
    private const val PACKS_FILE = ".vscodroid/toolchains.json"

    /** The ids of [recipes] that are ready offline, from the two files' text (null when missing). */
    fun readyIds(recipes: List<KitRecipe>, preparedJson: String?, packsJson: String?): Set<String> {
        val prepared = prepared(preparedJson)
        val packs = packs(packsJson)
        return recipes.filter { r ->
            val packsThere = r.packs.all { it in packs }
            when (r.internet) {
                KitInternet.No -> true
                KitInternet.PackDownload -> packsThere
                KitInternet.Once -> packsThere && prepared[r.id] == r.tested
            }
        }.map { it.id }.toSet()
    }

    /** [readyIds] from the files under [home]. Touches the disk: call it off the main thread. */
    fun load(recipes: List<KitRecipe>, home: File): Set<String> =
        readyIds(recipes, read(File(home, FILE)), read(File(home, PACKS_FILE)))

    /** Prepared recipe id to the versions it was prepared with. */
    private fun prepared(json: String?): Map<String, Map<String, String>> {
        val recipes = parseObject(json)?.optJSONObject("recipes") ?: return emptyMap()
        return recipes.keys().asSequence().mapNotNull { id ->
            val tested = recipes.optJSONObject(id)?.optJSONObject("tested") ?: return@mapNotNull null
            id to tested.keys().asSequence().mapNotNull { k -> (tested.opt(k) as? String)?.let { k to it } }.toMap()
        }.toMap()
    }

    private fun packs(json: String?): Set<String> {
        val array = try {
            if (json.isNullOrBlank()) return emptySet() else JSONArray(json)
        } catch (_: JSONException) {
            return emptySet()
        }
        return (0 until array.length()).mapNotNull { i ->
            (array.optJSONObject(i)?.opt("name") as? String)?.let { "toolchain_$it" }
        }.toSet()
    }

    private fun parseObject(json: String?): JSONObject? = try {
        if (json.isNullOrBlank()) null else JSONObject(json)
    } catch (_: JSONException) {
        null
    }

    private fun read(file: File): String? = try {
        if (file.isFile && file.length() < MAX_FILE) file.readText() else null
    } catch (_: IOException) {
        null
    }

    private const val MAX_FILE = 256 * 1024L
}
