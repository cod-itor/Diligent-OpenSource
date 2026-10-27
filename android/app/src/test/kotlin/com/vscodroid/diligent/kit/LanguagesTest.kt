package com.vscodroid.diligent.kit

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.R
import com.vscodroid.setup.SPACE_BUFFER
import com.vscodroid.setup.ToolchainFailure
import com.vscodroid.setup.ToolchainRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanguagesTest {

    private fun pack(name: String, install: Long, download: Long) =
        LanguagePack("toolchain_$name", name, 0, install, download)

    private val packs = listOf(
        pack("ruby", 41, 11),
        pack("kotlin", 120, 60),
        pack("java", 186, 64),
        pack("cpp", 260, 74),
    )
    private val known = packs.map { it.pack }

    // -- grouping --

    private fun recipe(id: String, language: String?) =
        KitRecipe(id, id, "", null, emptyList(), null, KitInternet.No, emptyList(), language = language)

    @Test
    fun `recipes group by language in the set order, unknown languages by name, none last`() {
        val groups = KitRecipes.groups(
            listOf(
                recipe("rust", "Rust"), recipe("x", null), recipe("flask", "Python"), recipe("coffee", "CoffeeScript"),
                recipe("web", "HTML and CSS"), recipe("asc", "AssemblyScript"), recipe("py", "Python"), recipe("vue", "JavaScript"),
            ),
        )
        assertEquals(
            listOf("HTML and CSS", "JavaScript", "Python", "Rust", "AssemblyScript", "CoffeeScript", null),
            groups.map { it.language },
        )
        assertEquals(listOf("flask", "py"), groups.first { it.language == "Python" }.recipes.map { it.id }, "order kept inside a group")
    }

    @Test
    fun `the language is read from the recipe`() {
        assertEquals("Python", KitRecipes.parse("""{"id": "a", "title": "A", "language": " Python "}""")?.language)
        assertNull(KitRecipes.parse("""{"id": "a", "title": "A"}""")?.language)
        assertNull(KitRecipes.parse("""{"id": "a", "title": "A", "language": 3}""")?.language)
    }

    @Test
    fun `every shipped recipe names its language, so none falls under Other`() {
        val dir = java.io.File("src/main/assets/extensions")
            .listFiles { f -> f.name.startsWith(KitRecipes.EXTENSION_PREFIX) }
            ?.map { it.name }?.let(KitRecipes::newestExtension)
        val files = java.io.File("src/main/assets/extensions/$dir/recipes").listFiles().orEmpty()
        val recipes = KitRecipes.parseAll(files.map { it.readText() })
        assertTrue(recipes.size >= 30, "found ${recipes.size} recipes")
        assertEquals(emptyList<String>(), recipes.filter { it.language == null }.map { it.id })
        assertEquals(listOf("toolchain_java", "toolchain_kotlin"), recipes.first { it.id == "kotlin" }.packs)
    }

    // -- ordering, missing, totals --

    @Test
    fun `java installs before kotlin, and kotlin brings java along`() {
        assertEquals(listOf("toolchain_java", "toolchain_kotlin"), Languages.toInstall(listOf("toolchain_kotlin"), emptySet(), known))
        assertEquals(listOf("toolchain_kotlin"), Languages.toInstall(listOf("toolchain_kotlin"), setOf("toolchain_java"), known))
        assertEquals(
            listOf("toolchain_ruby", "toolchain_java", "toolchain_kotlin", "toolchain_cpp"),
            Languages.missing(packs, emptySet()),
            "the registry's order, except Java comes before Kotlin",
        )
    }

    @Test
    fun `missing leaves out what is installed and what the registry does not offer`() {
        assertEquals(listOf("toolchain_kotlin", "toolchain_cpp"), Languages.missing(packs, setOf("toolchain_ruby", "toolchain_java")))
        assertEquals(emptyList<String>(), Languages.missing(packs, known.toSet()))
        assertEquals(emptyList<String>(), Languages.toInstall(listOf("toolchain_go"), emptySet(), known))
        // Kotlin without a Java pack in the registry: Kotlin alone, rather than nothing.
        assertEquals(listOf("toolchain_kotlin"), Languages.toInstall(listOf("toolchain_kotlin"), emptySet(), listOf("toolchain_kotlin")))
    }

    @Test
    fun `totals add the downloads, and the space for every tree plus the largest again`() {
        val missing = Languages.missing(packs, setOf("toolchain_cpp"))
        val totals = Languages.totals(packs, missing)
        assertEquals(11L + 60 + 64, totals.downloadBytes)
        assertEquals(41L + 120 + 186 + 186 + SPACE_BUFFER, totals.spaceBytes)
        assertEquals(PackTotals(0, 0), Languages.totals(packs, emptyList()))
    }

    @Test
    fun `the registry's packs all appear, and the record's short names become pack names`() {
        assertEquals(ToolchainRegistry.available.map { it.packName }, Languages.packs().map { it.pack })
        assertEquals(setOf("toolchain_java", "toolchain_ruby"), Languages.installedPacks(listOf("java", "toolchain_ruby", "")))
    }

    // -- the queue --

    @Test
    fun `a failure is recorded and the next pack starts`() {
        var q = PackQueue(listOf("toolchain_ruby", "toolchain_cpp", "toolchain_java"))
        q = q.on("toolchain_ruby", AssetPackStatus.DOWNLOADING, 30, null, false)
        assertEquals(30, q.percent)
        q = q.on("toolchain_ruby", AssetPackStatus.FAILED, 0, ToolchainFailure.NOT_PUBLISHED, false)
        assertEquals("toolchain_cpp", q.current)
        assertEquals(listOf(PackFailure("toolchain_ruby", ToolchainFailure.NOT_PUBLISHED.message)), q.failed)
        q = q.on("toolchain_cpp", AssetPackStatus.COMPLETED, 100, null, false)
        q = q.on("toolchain_java", AssetPackStatus.CANCELED, 0, null, false)
        assertTrue(q.finished)
        assertEquals(listOf("toolchain_cpp"), q.installed)
        assertEquals(listOf("toolchain_ruby", "toolchain_java"), q.failed.map { it.pack })
    }

    @Test
    fun `reports for another pack are ignored, and waiting does not advance`() {
        val q = PackQueue(listOf("toolchain_ruby", "toolchain_cpp"))
        assertEquals(q, q.on("toolchain_cpp", AssetPackStatus.COMPLETED, 100, null, false))
        assertEquals(q, q.on("toolchain_ruby", AssetPackStatus.WAITING_FOR_WIFI, 0, null, false))
        assertTrue(q.on("toolchain_ruby", AssetPackStatus.REQUIRES_USER_CONFIRMATION, 0, null, false).needsConfirmation)
    }

    @Test
    fun `kotlin is skipped when java fails`() {
        var q = PackQueue(listOf("toolchain_java", "toolchain_kotlin", "toolchain_cpp"))
        q = q.on("toolchain_java", AssetPackStatus.FAILED, 0, ToolchainFailure.NETWORK, false)
        assertEquals("toolchain_cpp", q.current)
        assertEquals(PackFailure("toolchain_kotlin", R.string.diligent_languages_needs_other), q.failed[1])
        assertEquals(PackRowState.Installing, q.stateOf("toolchain_cpp"))
        assertEquals(PackRowState.Failed, q.stateOf("toolchain_kotlin"))
    }

    @Test
    fun `a pack another install holds is not a failure`() {
        val q = PackQueue(listOf("toolchain_ruby")).on("toolchain_ruby", AssetPackStatus.UNKNOWN, 0, null, heldElsewhere = true)
        assertTrue(q.finished)
        assertEquals(listOf("toolchain_ruby"), q.installed)
        assertTrue(q.failed.isEmpty())
    }

    private class FakeInstaller : PackInstaller {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var onState: ((String, Int, Int, ToolchainFailure?) -> Unit)? = null
        var confirms = 0
        override fun install(pack: String) { started += pack }
        override fun cancel(pack: String) { cancelled += pack }
        override fun listen(onState: ((String, Int, Int, ToolchainFailure?) -> Unit)?) { this.onState = onState }
        override fun heldElsewhere(pack: String) = false
        override fun confirm() { confirms++ }
        override fun installed() = setOf("toolchain_ruby")
    }

    @Test
    fun `set up all installs one pack after another, keeps going past a failure, and lets go at the end`() {
        val installer = FakeInstaller()
        val queue = PackInstallQueue(installer, post = { it() })
        queue.start(listOf("toolchain_java", "toolchain_kotlin", "toolchain_cpp"))
        assertEquals(listOf("toolchain_java"), installer.started)
        queue.start(listOf("toolchain_ruby"))
        assertEquals(listOf("toolchain_java"), installer.started, "a second queue does not start over the first")

        installer.onState!!("toolchain_java", AssetPackStatus.REQUIRES_USER_CONFIRMATION, 0, null)
        installer.onState!!("toolchain_java", AssetPackStatus.REQUIRES_USER_CONFIRMATION, 0, null)
        assertEquals(1, installer.confirms, "asked once")
        installer.onState!!("toolchain_java", AssetPackStatus.COMPLETED, 100, null)
        assertEquals(listOf("toolchain_java", "toolchain_kotlin"), installer.started)
        assertTrue("toolchain_java" in queue.installed)
        installer.onState!!("toolchain_kotlin", AssetPackStatus.FAILED, 0, ToolchainFailure.NOT_PUBLISHED)
        assertEquals(listOf("toolchain_java", "toolchain_kotlin", "toolchain_cpp"), installer.started)
        installer.onState!!("toolchain_cpp", AssetPackStatus.COMPLETED, 100, null)

        val done = queue.queue!!
        assertTrue(done.finished)
        assertEquals(listOf("toolchain_java", "toolchain_cpp"), done.installed)
        assertEquals(listOf("toolchain_kotlin"), done.failed.map { it.pack })
        assertNull(installer.onState, "the listener is released at the end")
        assertFalse(queue.busy)
        queue.dismiss()
        assertNull(queue.queue)
        runBlocking { queue.refresh() }
        assertEquals(setOf("toolchain_ruby"), queue.installed)
    }

    @Test
    fun `stop cancels the pack downloading and nothing after it starts`() {
        val installer = FakeInstaller()
        val queue = PackInstallQueue(installer, post = { it() })
        queue.start(listOf("toolchain_ruby", "toolchain_cpp"))
        val report = installer.onState!!
        queue.cancel()
        assertEquals(listOf("toolchain_ruby"), installer.cancelled)
        report("toolchain_ruby", AssetPackStatus.CANCELED, 0, null)
        assertEquals(listOf("toolchain_ruby"), installer.started)
        assertTrue(queue.queue!!.cancelled)
        assertNull(installer.onState)
    }
}
