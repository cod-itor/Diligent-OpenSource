package com.vscodroid.diligent.files

import java.io.File

/** One visible line of the tree: an entry and how deep it sits. */
data class TreeRow(
    val entry: FileEntry,
    /** 0 for the project's own entries. */
    val depth: Int,
    val expanded: Boolean,
    /** Expanded, and its entries are still being read. */
    val loading: Boolean,
)

/**
 * The Files tab's tree, as an immutable value: which folders are open and
 * what was last read from each.
 *
 * Only open folders are ever read, so a project of thousands of files costs
 * one listing per folder the user opens, and [rows] walks only what is on
 * screen. Each change answers a new tree, which is what lets Compose hold it in
 * one piece of state and redraw when it changes.
 */
data class FileTree(
    val root: String,
    /** Last listing of each folder read, by path. The root's is there once it has been read. */
    val children: Map<String, List<FileEntry>> = emptyMap(),
    /** Open folders, by path. The root is always open and never listed here. */
    val expanded: Set<String> = emptySet(),
) {
    val rootLoaded: Boolean get() = root in children

    /** The visible rows, in order: each open folder's entries straight under it. */
    fun rows(): List<TreeRow> {
        val out = ArrayList<TreeRow>()
        fun walk(folder: String, depth: Int) {
            for (entry in children[folder].orEmpty()) {
                val open = entry.isDirectory && entry.path in expanded
                out += TreeRow(entry, depth, expanded = open, loading = open && entry.path !in children)
                if (open) walk(entry.path, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    fun withChildren(folder: String, entries: List<FileEntry>): FileTree =
        copy(children = children + (folder to entries))

    fun expand(folder: String): FileTree = if (folder == root) this else copy(expanded = expanded + folder)

    /**
     * Closes [folder] and every folder inside it, and forgets their listings:
     * opening it again reads it afresh, which is when a stale listing would
     * matter, and a closed `node_modules` should not keep its entries in memory.
     */
    fun collapse(folder: String): FileTree = copy(
        expanded = expanded.filterNot { isSelfOrUnder(it, folder) }.toSet(),
        children = children.filterKeys { it == root || !isSelfOrUnder(it, folder) },
    )

    /** The folders a refresh reads again: the root, then each open folder, parents before children. */
    fun foldersToRefresh(): List<String> = listOf(root) + expanded.sortedBy { it.count { c -> c == File.separatorChar } }

    /** After [from] was renamed or moved to [to]: open folders under it stay open under the new path. */
    fun moved(from: String, to: String): FileTree {
        fun remap(path: String) = if (isSelfOrUnder(path, from)) to + path.substring(from.length) else path
        return copy(
            expanded = expanded.map(::remap).toSet(),
            // Listings under the old path name entries by old paths; they are
            // dropped and read again rather than rewritten entry by entry.
            children = children.filterKeys { it == root || !isSelfOrUnder(it, from) },
        )
    }

    /** After [path] was deleted, or found gone on a refresh. */
    fun removed(path: String): FileTree = collapse(path)

    /**
     * The folder a "New" action without a row in mind should create in: the
     * folder opened most recently whose own folders are all open, so it is on
     * screen, or the root.
     */
    fun folderOnScreen(candidate: String?): String =
        candidate?.takeIf { it == root || isVisible(it) } ?: root

    private fun isVisible(folder: String): Boolean {
        if (folder !in expanded) return false
        var parent = File(folder).parent ?: return false
        while (parent != root) {
            if (parent !in expanded) return false
            parent = File(parent).parent ?: return false
        }
        return true
    }

    companion object {
        fun isSelfOrUnder(path: String, folder: String): Boolean =
            path == folder || path.startsWith(folder.trimEnd(File.separatorChar) + File.separator)
    }
}

/** What a file is, as far as its icon is concerned. */
enum class FileKind { Folder, Code, Script, Web, Style, Data, Config, Text, Image, Archive, Lock, Git, Other }

/**
 * The kind of [name], from its extension or, for the files every project has,
 * its whole name. Folders are [FileKind.Folder] whatever they are called.
 */
fun fileKind(name: String, isDirectory: Boolean = false): FileKind {
    if (isDirectory) return FileKind.Folder
    val lower = name.lowercase()
    WHOLE_NAMES[lower]?.let { return it }
    if (lower.startsWith(".env")) return FileKind.Config
    val dot = lower.lastIndexOf('.')
    if (dot <= 0 || dot == lower.length - 1) return FileKind.Other
    return EXTENSIONS[lower.substring(dot + 1)] ?: FileKind.Other
}

private val WHOLE_NAMES = mapOf(
    ".gitignore" to FileKind.Git,
    ".gitattributes" to FileKind.Git,
    ".gitmodules" to FileKind.Git,
    "dockerfile" to FileKind.Config,
    "makefile" to FileKind.Script,
    "package-lock.json" to FileKind.Lock,
    "yarn.lock" to FileKind.Lock,
    "pnpm-lock.yaml" to FileKind.Lock,
    "cargo.lock" to FileKind.Lock,
    "poetry.lock" to FileKind.Lock,
    "license" to FileKind.Text,
    "readme" to FileKind.Text,
)

private val EXTENSIONS: Map<String, FileKind> = buildMap {
    listOf("kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs", "swift", "dart",
        "php", "lua", "scala", "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte")
        .forEach { put(it, FileKind.Code) }
    listOf("sh", "bash", "zsh", "fish", "ps1", "bat").forEach { put(it, FileKind.Script) }
    listOf("html", "htm", "xhtml").forEach { put(it, FileKind.Web) }
    listOf("css", "scss", "sass", "less").forEach { put(it, FileKind.Style) }
    listOf("json", "jsonc", "xml", "csv", "tsv", "sql", "graphql", "proto").forEach { put(it, FileKind.Data) }
    listOf("yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "gradle", "env", "editorconfig")
        .forEach { put(it, FileKind.Config) }
    listOf("md", "markdown", "txt", "rst", "adoc", "log").forEach { put(it, FileKind.Text) }
    listOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif").forEach { put(it, FileKind.Image) }
    listOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "apk").forEach { put(it, FileKind.Archive) }
    put("lock", FileKind.Lock)
}
