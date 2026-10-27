package com.vscodroid.diligent.kit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KitRecipeTest {

    private val react = """
        {"id":"react-vite","title":"React app","summary":"A React app with Vite.","sizeMB":85,
         "needs":{"toolchains":[],"minFreeMB":400,"internet":"once"},
         "tested":{"node":"22","vite":"8"},
         "steps":[{"type":"run","title":"Create the app","cmd":"npm create vite"},
                  {"type":"profile"},
                  {"type":"test","title":"Check it starts"}]}
    """.trimIndent()

    private val java = """
        {"id":"java","title":"Java","summary":"Hello World in Java.","sizeMB":210.4,
         "needs":{"toolchains":["java"],"minFreeMB":600,"internet":"pack"},"steps":[]}
    """.trimIndent()

    @Test
    fun `a recipe is read in full`() {
        val r = KitRecipes.parse(react)!!
        assertEquals(
            KitRecipe(
                id = "react-vite",
                title = "React app",
                summary = "A React app with Vite.",
                sizeMB = 85,
                packs = emptyList(),
                minFreeMB = 400,
                internet = KitInternet.Once,
                steps = listOf("Create the app", "Check it starts"),
                tested = mapOf("node" to "22", "vite" to "8"),
            ),
            r,
        )
        val j = KitRecipes.parse(java)!!
        assertEquals(listOf("java"), j.packs)
        assertEquals(211, j.sizeMB)
        assertEquals(KitInternet.PackDownload, j.internet)
    }

    @Test
    fun `a recipe without an id or title is skipped, and missing fields are safe`() {
        for (bad in listOf(null, "", "x", "[]", "{}", """{"id":"a"}""", """{"title":"A"}""", """{"id":3,"title":"A"}""")) {
            assertNull(KitRecipes.parse(bad), "$bad")
        }
        val bare = KitRecipes.parse("""{"id":"a","title":"A","needs":"x","steps":{},"sizeMB":-3}""")!!
        assertEquals(KitRecipe("a", "A", "", null, emptyList(), null, KitInternet.Once, emptyList()), bare)
    }

    @Test
    fun `the internet badge reads the words a recipe may use`() {
        assertEquals(KitInternet.No, KitRecipes.internet("no"))
        assertEquals(KitInternet.No, KitRecipes.internet(" None "))
        assertEquals(KitInternet.No, KitRecipes.internet(false))
        assertEquals(KitInternet.Once, KitRecipes.internet("once"))
        assertEquals(KitInternet.Once, KitRecipes.internet(true))
        assertEquals(KitInternet.PackDownload, KitRecipes.internet("pack-download"))
        assertEquals(KitInternet.PackDownload, KitRecipes.internet("Pack download"))
        assertEquals(KitInternet.Once, KitRecipes.internet(null))
        assertEquals(KitInternet.Once, KitRecipes.internet(7))
    }

    @Test
    fun `recipes come in the guide's order, without repeats or broken ones`() {
        val web = """{"id":"web-basic","title":"Web page"}"""
        val extra = """{"id":"zig","title":"Zig"}"""
        val all = KitRecipes.parseAll(listOf(java, extra, "oops", react, web, web))
        assertEquals(listOf("web-basic", "react-vite", "java", "zig"), all.map { it.id })
    }

    @Test
    fun `the newest kit extension is found by version`() {
        val names = listOf("diligent.diligent-bridge-0.1.5", "diligent.diligent-kit-0.1.0", "diligent.diligent-kit-0.10.0", "diligent.diligent-kit-0.9.1")
        assertEquals("diligent.diligent-kit-0.10.0", KitRecipes.newestExtension(names))
        assertNull(KitRecipes.newestExtension(listOf("diligent.diligent-core-0.1.0")))
        assertNull(KitRecipes.newestExtension(emptyList()))
    }

    @Test
    fun `ready offline follows the engine's rule`() {
        val web = KitRecipes.parse("""{"id":"web-basic","title":"Web","needs":{"toolchains":[],"internet":"no"}}""")!!
        val next = KitRecipes.parse("""{"id":"nextjs","title":"Next","tested":{"next":"16"},"needs":{"toolchains":[],"internet":"once"}}""")!!
        val java = KitRecipes.parse("""{"id":"java","title":"Java","needs":{"toolchains":["toolchain_java"],"internet":"pack"}}""")!!
        val all = listOf(web, next, java)
        assertEquals(mapOf("next" to "16"), next.tested)

        assertEquals(setOf("web-basic"), KitOffline.readyIds(all, null, null))
        assertEquals(setOf("web-basic"), KitOffline.readyIds(all, "{broken", "[broken"))
        val prepared = """{"recipes":{"nextjs":{"preparedAt":"x","tested":{"next":"16"}}}}"""
        assertEquals(setOf("web-basic", "nextjs", "java"), KitOffline.readyIds(all, prepared, """[{"name":"java"}]"""))
        // Prepared for an older version: prepare again.
        val stale = """{"recipes":{"nextjs":{"preparedAt":"x","tested":{"next":"15"}}}}"""
        assertTrue("nextjs" !in KitOffline.readyIds(all, stale, null))
    }
}
