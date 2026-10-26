package com.vscodroid.diligent.projects

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectDetectorTest {

    /** A project root made of [files] (name to text) and [dirs] (relative folder to the names in it). */
    private fun root(vararg files: Pair<String, String>, dirs: Map<String, Set<String>> = emptyMap()) =
        object : ProjectRoot {
            private val texts = files.toMap()
            override val names: Set<String> = texts.keys + dirs.keys.map { it.substringBefore('/') }
            override fun namesIn(relativeDir: String) = dirs[relativeDir].orEmpty()
            override fun read(name: String) = texts[name]
        }

    private fun detect(vararg files: Pair<String, String>, dirs: Map<String, Set<String>> = emptyMap()) =
        ProjectDetector.detect(root(*files, dirs = dirs))

    @Test
    fun `an empty folder is unknown`() {
        assertEquals(ProjectKind.Unknown, detect())
    }

    @Test
    fun `a package json is JavaScript`() {
        assertEquals(ProjectKind(ProjectLanguage.JavaScript), detect("package.json" to """{"name":"x"}"""))
    }

    @Test
    fun `a typescript dependency makes it TypeScript`() {
        val json = """{"devDependencies":{"typescript":"^5"}}"""
        assertEquals(ProjectLanguage.TypeScript, detect("package.json" to json).language)
    }

    @Test
    fun `a tsconfig makes it TypeScript`() {
        assertEquals(ProjectLanguage.TypeScript, detect("package.json" to "{}", "tsconfig.json" to "{}").language)
    }

    @Test
    fun `next and vite are named as frameworks`() {
        assertEquals(
            ProjectKind(ProjectLanguage.TypeScript, ProjectFramework.NextJs),
            detect("package.json" to """{"dependencies":{"next":"15","react":"19"},"devDependencies":{"typescript":"5"}}"""),
        )
        assertEquals(
            ProjectKind(ProjectLanguage.JavaScript, ProjectFramework.Vite),
            detect("package.json" to """{"devDependencies":{"vite":"6"}}"""),
        )
        assertEquals(ProjectFramework.Express, detect("package.json" to """{"dependencies":{"express":"4"}}""").framework)
    }

    @Test
    fun `a package json that does not parse is still JavaScript`() {
        assertEquals(ProjectKind(ProjectLanguage.JavaScript), detect("package.json" to "{ not json"))
        assertEquals(emptySet<String>(), ProjectDetector.packageDependencies(null))
    }

    @Test
    fun `python manifests and entry points are Python`() {
        listOf("requirements.txt", "pyproject.toml", "setup.py", "Pipfile", "main.py", "app.py").forEach { marker ->
            assertEquals(ProjectLanguage.Python, detect(marker to "").language, marker)
        }
    }

    @Test
    fun `python frameworks come from the requirements`() {
        assertEquals(ProjectFramework.Flask, detect("requirements.txt" to "Flask==3.0\nrequests").framework)
        assertEquals(ProjectFramework.FastApi, detect("pyproject.toml" to "dependencies = [\"fastapi>=0.110\"]").framework)
        assertEquals(ProjectFramework.Django, detect("manage.py" to "").framework)
        assertEquals(null, detect("requirements.txt" to "flask-cors\nrequests").framework)
    }

    @Test
    fun `a Gemfile is Ruby`() {
        assertEquals(ProjectKind(ProjectLanguage.Ruby), detect("Gemfile" to ""))
        assertEquals(
            ProjectFramework.Rails,
            detect("Gemfile" to "", "config.ru" to "", dirs = mapOf("config" to setOf("application.rb"))).framework,
        )
    }

    @Test
    fun `JVM builds are Java unless the sources are Kotlin`() {
        assertEquals(ProjectLanguage.Java, detect("pom.xml" to "").language)
        assertEquals(ProjectLanguage.Java, detect("build.gradle" to "", dirs = mapOf("src/main" to setOf("java"))).language)
        assertEquals(ProjectLanguage.Kotlin, detect("build.gradle" to "", dirs = mapOf("src/main" to setOf("kotlin"))).language)
        assertEquals(ProjectLanguage.Kotlin, detect("build.gradle.kts" to "").language)
    }

    @Test
    fun `CMake is C, and a Makefile only with C sources`() {
        assertEquals(ProjectLanguage.CFamily, detect("CMakeLists.txt" to "").language)
        assertEquals(ProjectLanguage.CFamily, detect("Makefile" to "", "main.c" to "").language)
        assertEquals(ProjectLanguage.CFamily, detect("Makefile" to "", dirs = mapOf("src" to setOf("app.cpp"))).language)
        assertEquals(ProjectLanguage.Unknown, detect("Makefile" to "", "README.md" to "").language)
    }

    @Test
    fun `go, rust and web`() {
        assertEquals(ProjectLanguage.Go, detect("go.mod" to "").language)
        assertEquals(ProjectLanguage.Rust, detect("Cargo.toml" to "").language)
        assertEquals(ProjectLanguage.Web, detect("index.html" to "", "style.css" to "").language)
    }

    @Test
    fun `a single-ecosystem manifest wins over package json`() {
        assertEquals(ProjectLanguage.Ruby, detect("Gemfile" to "", "package.json" to "{}").language)
        assertEquals(ProjectLanguage.Python, detect("requirements.txt" to "", "package.json" to "{}").language)
        assertEquals(ProjectLanguage.Rust, detect("Cargo.toml" to "", "package.json" to "{}").language)
    }

    @Test
    fun `package json wins over loose hints`() {
        assertEquals(ProjectLanguage.JavaScript, detect("package.json" to "{}", "index.html" to "").language)
        assertEquals(ProjectLanguage.JavaScript, detect("package.json" to "{}", "Makefile" to "", "x.c" to "").language)
    }

    @Test
    fun `keys round trip and unknown keys are safe`() {
        ProjectLanguage.entries.forEach { assertEquals(it, ProjectLanguage.fromKey(it.key)) }
        ProjectFramework.entries.forEach { assertEquals(it, ProjectFramework.fromKey(it.key)) }
        assertEquals(ProjectLanguage.Unknown, ProjectLanguage.fromKey("cobol"))
        assertEquals(null, ProjectFramework.fromKey(""))
    }

    @Test
    fun `reads a real folder`(@TempDir dir: File) {
        File(dir, "package.json").writeText("""{"dependencies":{"vite":"6"}}""")
        File(dir, "tsconfig.json").writeText("{}")
        assertEquals(ProjectKind(ProjectLanguage.TypeScript, ProjectFramework.Vite), ProjectDetector.detect(dir))
    }

    @Test
    fun `a missing folder is unknown, not an error`(@TempDir dir: File) {
        assertEquals(ProjectKind.Unknown, ProjectDetector.detect(File(dir, "gone")))
    }

    @Test
    fun `an oversized manifest is not read`(@TempDir dir: File) {
        File(dir, "package.json").writeText("{\"dependencies\":{\"next\":\"1\"}," + " ".repeat(ProjectDetector.MAX_READ_BYTES) + "}")
        val kind = ProjectDetector.detect(dir)
        assertEquals(ProjectLanguage.JavaScript, kind.language)
        assertTrue(kind.framework == null)
    }
}
