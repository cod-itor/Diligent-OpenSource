package com.vscodroid.diligent.projects

import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** The main language of a project, as the Projects tab names it. */
enum class ProjectLanguage(
    /** What is stored in the recent list. Never rename. */
    val key: String,
) {
    TypeScript("typescript"),
    JavaScript("javascript"),
    Python("python"),
    Ruby("ruby"),
    Java("java"),
    Kotlin("kotlin"),
    CFamily("c"),
    Go("go"),
    Rust("rust"),
    Web("web"),
    Unknown("unknown");

    companion object {
        /** The language stored as [key]; anything else, including a key from a newer version, is [Unknown]. */
        fun fromKey(key: String?): ProjectLanguage = entries.firstOrNull { it.key == key } ?: Unknown
    }
}

/** A framework worth naming next to the language, when one is plain from the project's manifest. */
enum class ProjectFramework(
    /** What is stored in the recent list. Never rename. */
    val key: String,
) {
    NextJs("next"),
    Vite("vite"),
    Express("express"),
    Django("django"),
    Flask("flask"),
    FastApi("fastapi"),
    Rails("rails");

    companion object {
        fun fromKey(key: String?): ProjectFramework? = entries.firstOrNull { it.key == key }
    }
}

/** What a project is written in, for the chip on its card. */
data class ProjectKind(val language: ProjectLanguage, val framework: ProjectFramework? = null) {
    companion object {
        val Unknown = ProjectKind(ProjectLanguage.Unknown)
    }
}

/**
 * What [ProjectDetector] may look at: the names at the project's root, and
 * the text of a few small files there. An interface so the rules are tested
 * with a map instead of a folder.
 */
interface ProjectRoot {
    /** The names of the files and folders directly inside the root. */
    val names: Set<String>

    /** The names directly inside [relativeDir] (such as `src`), or empty when it is not a folder. */
    fun namesIn(relativeDir: String): Set<String>

    /** The text of [name] at the root, or null when it cannot be read. */
    fun read(name: String): String?
}

/**
 * Guesses a project's language from the marker files at its root.
 *
 * Only the root, and only a few small files, on purpose: this runs every time
 * a folder opens, and a project with `node_modules` must not cost a tree
 * walk. A guess is enough for a chip on a card, and "unknown" is an honest
 * answer the card shows by leaving the chip out.
 *
 * The order decides projects that carry several markers. A manifest that only
 * one ecosystem uses (Cargo.toml, go.mod, a Gradle or Maven build, a Gemfile,
 * a Python manifest) wins over package.json, because those projects often
 * keep a package.json for front-end tooling while the reverse is rare. Loose
 * hints (a main.py, a Makefile, an index.html) come last.
 */
object ProjectDetector {

    /** Detects from a real folder. Disk reads: call it off the main thread. */
    fun detect(folder: File): ProjectKind = detect(FileProjectRoot(folder))

    fun detect(root: ProjectRoot): ProjectKind {
        val names = root.names
        fun has(name: String) = name in names

        return when {
            has("Cargo.toml") -> ProjectKind(ProjectLanguage.Rust)
            has("go.mod") -> ProjectKind(ProjectLanguage.Go)
            has("build.gradle.kts") || has("settings.gradle.kts") -> ProjectKind(ProjectLanguage.Kotlin)
            has("pom.xml") || has("build.gradle") -> ProjectKind(jvmLanguage(root))
            has("Gemfile") -> ProjectKind(
                ProjectLanguage.Ruby,
                ProjectFramework.Rails.takeIf { has("config.ru") && root.namesIn("config").contains("application.rb") },
            )
            has("pyproject.toml") || has("requirements.txt") || has("setup.py") || has("Pipfile") ->
                ProjectKind(ProjectLanguage.Python, pythonFramework(root))
            has("package.json") -> javaScriptKind(root)
            has("CMakeLists.txt") -> ProjectKind(ProjectLanguage.CFamily)
            has("Makefile") && (hasCSources(names) || hasCSources(root.namesIn("src"))) -> ProjectKind(ProjectLanguage.CFamily)
            has("main.py") || has("app.py") || has("manage.py") -> ProjectKind(ProjectLanguage.Python, pythonFramework(root))
            has("index.html") -> ProjectKind(ProjectLanguage.Web)
            else -> ProjectKind.Unknown
        }
    }

    /** A Groovy Gradle or Maven build is Kotlin when its sources are. */
    private fun jvmLanguage(root: ProjectRoot): ProjectLanguage {
        val main = root.namesIn("src/main")
        val kotlin = "kotlin" in main || root.namesIn("app/src/main").contains("kotlin")
        return if (kotlin) ProjectLanguage.Kotlin else ProjectLanguage.Java
    }

    private fun hasCSources(names: Set<String>) = names.any { name ->
        C_EXTENSIONS.any { name.endsWith(it) }
    }

    private fun pythonFramework(root: ProjectRoot): ProjectFramework? {
        if ("manage.py" in root.names) return ProjectFramework.Django
        val text = listOf("requirements.txt", "pyproject.toml", "Pipfile")
            .filter { it in root.names }
            .mapNotNull { root.read(it) }
            .joinToString("\n")
            .lowercase()
        if (text.isEmpty()) return null
        return PYTHON_FRAMEWORKS.firstOrNull { (name, _) -> Regex("""(^|[^a-z0-9_-])$name([^a-z0-9_-]|$)""", RegexOption.MULTILINE).containsMatchIn(text) }
            ?.second
    }

    private fun javaScriptKind(root: ProjectRoot): ProjectKind {
        val deps = packageDependencies(root.read("package.json"))
        val typescript = "typescript" in deps || "tsconfig.json" in root.names
        val framework = when {
            "next" in deps -> ProjectFramework.NextJs
            "vite" in deps -> ProjectFramework.Vite
            "express" in deps -> ProjectFramework.Express
            else -> null
        }
        return ProjectKind(if (typescript) ProjectLanguage.TypeScript else ProjectLanguage.JavaScript, framework)
    }

    /**
     * Every name in package.json's dependency lists. A file that does not
     * parse gives none, and the project still reads as JavaScript, which is
     * what a package.json says whatever is inside it.
     */
    internal fun packageDependencies(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        val parsed = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return emptySet()
        }
        return DEPENDENCY_LISTS.flatMapTo(mutableSetOf()) { list ->
            parsed.optJSONObject(list)?.keys()?.asSequence()?.toList().orEmpty()
        }
    }

    private val C_EXTENSIONS = listOf(".c", ".cpp", ".cc", ".cxx", ".h", ".hpp")
    private val DEPENDENCY_LISTS = listOf("dependencies", "devDependencies", "peerDependencies")
    private val PYTHON_FRAMEWORKS = listOf(
        "django" to ProjectFramework.Django,
        "fastapi" to ProjectFramework.FastApi,
        "flask" to ProjectFramework.Flask,
    )

    /** Larger than any manifest a person writes; a bigger one is not read. */
    internal const val MAX_READ_BYTES = 256 * 1024
}

/** [ProjectRoot] over a folder on disk. */
private class FileProjectRoot(private val folder: File) : ProjectRoot {
    override val names: Set<String> by lazy { folder.list()?.toSet().orEmpty() }

    override fun namesIn(relativeDir: String): Set<String> = File(folder, relativeDir).list()?.toSet().orEmpty()

    override fun read(name: String): String? {
        val file = File(folder, name)
        if (!file.isFile || file.length() > ProjectDetector.MAX_READ_BYTES) return null
        return try {
            file.readText()
        } catch (_: java.io.IOException) {
            null
        }
    }
}
