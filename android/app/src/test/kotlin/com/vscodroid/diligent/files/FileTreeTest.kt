package com.vscodroid.diligent.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileTreeTest {

    private val root = "/p"
    private fun dir(path: String) = FileEntry(path, path.substringAfterLast('/'), isDirectory = true)
    private fun file(path: String) = FileEntry(path, path.substringAfterLast('/'), isDirectory = false)

    /** /p: src/ (app/ (page.tsx), index.ts), README.md */
    private val loaded = FileTree(root)
        .withChildren(root, listOf(dir("/p/src"), file("/p/README.md")))
        .withChildren("/p/src", listOf(dir("/p/src/app"), file("/p/src/index.ts")))
        .withChildren("/p/src/app", listOf(file("/p/src/app/page.tsx")))

    private fun FileTree.shown() = rows().map { "  ".repeat(it.depth) + it.entry.name + if (it.expanded) "/" else "" }

    @Test
    fun `only the root's entries show until a folder is opened`() {
        assertEquals(listOf("src", "README.md"), loaded.shown())
        assertFalse(FileTree(root).rootLoaded)
        assertTrue(loaded.rootLoaded)
    }

    @Test
    fun `an open folder shows its entries straight under it, one level deeper`() {
        val tree = loaded.expand("/p/src").expand("/p/src/app")
        assertEquals(listOf("src/", "  app/", "    page.tsx", "  index.ts", "README.md"), tree.shown())
        assertEquals(listOf(0, 1, 2, 1, 0), tree.rows().map { it.depth })
    }

    @Test
    fun `an open folder inside a closed one stays hidden`() {
        val tree = loaded.expand("/p/src/app")
        assertEquals(listOf("src", "README.md"), tree.shown())
    }

    @Test
    fun `an open folder not read yet is loading`() {
        val tree = FileTree(root).withChildren(root, listOf(dir("/p/lib"))).expand("/p/lib")
        val row = tree.rows().single()
        assertTrue(row.expanded)
        assertTrue(row.loading)
        assertFalse(tree.withChildren("/p/lib", emptyList()).rows().single().loading)
    }

    @Test
    fun `collapsing closes and forgets the folder and everything in it, never the root`() {
        val tree = loaded.expand("/p/src").expand("/p/src/app").collapse("/p/src")
        assertEquals(emptySet<String>(), tree.expanded)
        assertEquals(setOf(root), tree.children.keys)
        assertEquals(setOf(root), loaded.collapse(root).children.keys)
        assertEquals(loaded, loaded.expand(root))
    }

    @Test
    fun `collapsing src leaves src2 alone`() {
        val tree = loaded.withChildren(root, listOf(dir("/p/src"), dir("/p/src2")))
            .withChildren("/p/src2", emptyList())
            .expand("/p/src").expand("/p/src2")
            .collapse("/p/src")
        assertEquals(setOf("/p/src2"), tree.expanded)
        assertTrue("/p/src2" in tree.children)
    }

    @Test
    fun `refresh reads the root, then open folders parents first`() {
        val tree = loaded.expand("/p/src/app").expand("/p/src")
        assertEquals(listOf("/p", "/p/src", "/p/src/app"), tree.foldersToRefresh())
    }

    @Test
    fun `a moved folder keeps its open folders under the new path`() {
        val tree = loaded.expand("/p/src").expand("/p/src/app").moved("/p/src", "/p/lib")
        assertEquals(setOf("/p/lib", "/p/lib/app"), tree.expanded)
        assertEquals(setOf(root), tree.children.keys)
    }

    @Test
    fun `removing a folder closes it`() {
        val tree = loaded.expand("/p/src").removed("/p/src")
        assertEquals(emptySet<String>(), tree.expanded)
    }

    @Test
    fun `new things go in the folder last opened while it is on screen, otherwise the root`() {
        val tree = loaded.expand("/p/src").expand("/p/src/app")
        assertEquals("/p/src/app", tree.folderOnScreen("/p/src/app"))
        assertEquals(root, tree.collapse("/p/src").folderOnScreen("/p/src/app"))
        assertEquals(root, loaded.expand("/p/src/app").folderOnScreen("/p/src/app"))
        assertEquals(root, tree.folderOnScreen(null))
        assertEquals(root, tree.folderOnScreen(root))
    }

    @Test
    fun `isSelfOrUnder respects segment boundaries`() {
        assertTrue(FileTree.isSelfOrUnder("/p/src", "/p/src"))
        assertTrue(FileTree.isSelfOrUnder("/p/src/a", "/p/src"))
        assertFalse(FileTree.isSelfOrUnder("/p/src2", "/p/src"))
    }

    @Test
    fun `file kinds come from the extension or the whole name`() {
        assertEquals(FileKind.Folder, fileKind("src.ts", isDirectory = true))
        assertEquals(FileKind.Code, fileKind("Page.TSX"))
        assertEquals(FileKind.Code, fileKind("main.py"))
        assertEquals(FileKind.Web, fileKind("index.html"))
        assertEquals(FileKind.Style, fileKind("app.scss"))
        assertEquals(FileKind.Data, fileKind("package.json"))
        assertEquals(FileKind.Lock, fileKind("package-lock.json"))
        assertEquals(FileKind.Lock, fileKind("Gemfile.lock"))
        assertEquals(FileKind.Config, fileKind(".env.local"))
        assertEquals(FileKind.Config, fileKind("Dockerfile"))
        assertEquals(FileKind.Git, fileKind(".gitignore"))
        assertEquals(FileKind.Text, fileKind("README.md"))
        assertEquals(FileKind.Text, fileKind("LICENSE"))
        assertEquals(FileKind.Image, fileKind("logo.svg"))
        assertEquals(FileKind.Archive, fileKind("dist.tar.gz"))
        assertEquals(FileKind.Script, fileKind("build.sh"))
        assertEquals(FileKind.Other, fileKind("noextension"))
        assertEquals(FileKind.Other, fileKind(".hidden"))
        assertEquals(FileKind.Other, fileKind("trailing."))
    }
}
