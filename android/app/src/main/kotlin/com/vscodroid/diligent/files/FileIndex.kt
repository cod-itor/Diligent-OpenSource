package com.vscodroid.diligent.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** One file of the project, as the all-files list shows it. */
class IndexedFile(
    /** Absolute path under the project root as the editor knows it (see [FileEntry.path]). */
    val path: String,
    /** [path] relative to the root, with `/` between folders. */
    val relative: String,
) {
    val name: String = relative.substringAfterLast('/')

    /** The folder it sits in, relative to the root; `""` for the root itself. */
    val folder: String = relative.substringBeforeLast('/', "")

    internal val lowerName: String = name.lowercase()
    internal val lowerRelative: String = relative.lowercase()

    override fun equals(other: Any?): Boolean = other is IndexedFile && other.path == path
    override fun hashCode(): Int = path.hashCode()
    override fun toString(): String = "IndexedFile($relative)"
}

/** Every file of a project, or as many as [FileIndex.MAX_FILES] allows. */
data class FileIndex(
    /** Grouped by folder: the root's own files first, then each folder's, folders in [NaturalOrder]. */
    val files: List<IndexedFile>,
    /** True when the project has more files than were listed. */
    val capped: Boolean,
) {
    private val byPath: Map<String, IndexedFile> by lazy { files.associateBy { it.path } }

    operator fun get(path: String): IndexedFile? = byPath[path]

    companion object {
        /** Enough for any project typed on a phone; a cap so a huge one cannot stall the list. */
        const val MAX_FILES = 5_000

        /**
         * Folders the list does not go into: version control data, installed
         * packages and build output. Nobody looks for a file to edit there,
         * and any one of them can hold more files than the rest of the project.
         * The tree still shows them (dimmed, see [FileEntry.isHeavy]).
         */
        val SKIPPED_FOLDERS: Set<String> = FileEntry.HEAVY_FOLDERS + setOf(
            ".hg", ".svn", ".venv", "venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".tox",
            "build", "dist", "out", "target", ".gradle", ".next", ".nuxt", ".svelte-kit", ".turbo",
            ".parcel-cache", ".cache", "coverage", ".dart_tool", "Pods", ".expo", ".idea",
        )

        /**
         * Lists every file under [root], off the calling thread on [io].
         *
         * Folders are read breadth first, so when the cap is reached it is the
         * deep files that are missing, not the ones near the top. Links are
         * never followed into folders (no loops, and nothing outside the
         * project is reached); a link to a file inside the project is listed.
         * Answers null when [root] is not a folder that can be read.
         */
        suspend fun build(
            root: String,
            max: Int = MAX_FILES,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): FileIndex? = withContext(io) {
            try {
                scan(root, max)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }

        private suspend fun scan(root: String, max: Int): FileIndex? {
            val shownRoot = File(root).absoluteFile.toPath().normalize().toString().trimEnd(File.separatorChar)
            val realRoot = File(root).canonicalFile
            if (!realRoot.isDirectory) return null
            val found = ArrayList<IndexedFile>()
            val folders = ArrayDeque<Pair<File, String>>()
            folders.addLast(realRoot to "")
            while (folders.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (folder, relative) = folders.removeFirst()
                val names = folder.list() ?: continue
                names.sortWith(NaturalOrder)
                for (name in names) {
                    val real = File(folder, name)
                    val rel = if (relative.isEmpty()) name else "$relative/$name"
                    val attrs = try {
                        Files.readAttributes(real.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (e: IOException) {
                        continue
                    }
                    val isFile = when {
                        attrs.isSymbolicLink -> linkToFileInside(real, realRoot)
                        attrs.isDirectory -> {
                            if (name !in SKIPPED_FOLDERS) folders.addLast(real to rel)
                            false
                        }
                        else -> true
                    }
                    if (!isFile) continue
                    if (found.size >= max) return FileIndex(grouped(found), capped = true)
                    found += IndexedFile("$shownRoot/$rel", rel)
                }
            }
            return FileIndex(grouped(found), capped = false)
        }

        private fun linkToFileInside(link: File, realRoot: File): Boolean = try {
            val target = link.canonicalFile
            target.isFile && target.path.startsWith(realRoot.path.trimEnd(File.separatorChar) + File.separator)
        } catch (e: IOException) {
            false
        }

        /** The root's files, then each folder's files together, folders and names in [NaturalOrder]. */
        fun grouped(files: List<IndexedFile>): List<IndexedFile> = files.sortedWith(
            compareBy<IndexedFile> { it.folder.isNotEmpty() }
                .thenBy(NaturalOrder) { it.folder }
                .thenBy(NaturalOrder) { it.name },
        )
    }
}

/** What the all-files list shows: the recent files on top, then the rest. */
data class FileMatches(val recent: List<IndexedFile>, val others: List<IndexedFile>) {
    val isEmpty: Boolean get() = recent.isEmpty() && others.isEmpty()
}

/**
 * The all-files list's search, as plain functions so it is tested without a
 * screen.
 *
 * Case does not matter. A file matches when, best first: its name starts
 * with the query, its name contains it, its path contains it, or the query's
 * letters appear in its path in order (`apts` finds `app/page.tsx`). Within
 * one of those, recently opened files come first, then shorter names, then
 * the list's own order.
 */
object FileSearch {
    /** How many recently opened files are shown on top. */
    const val RECENT_SHOWN = 10

    private const val NAME_PREFIX = 0
    private const val NAME_CONTAINS = 1
    private const val PATH_CONTAINS = 2
    private const val IN_ORDER = 3

    /**
     * With an empty [query]: the recently opened files still in [index]
     * (newest first, from [recent], which are paths), then every file. With
     * a query: one ranked list of matches, in [FileMatches.others].
     */
    fun matches(index: FileIndex, query: String, recent: List<String>): FileMatches {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            val shown = recent.asSequence().mapNotNull { index[it] }.distinct().take(RECENT_SHOWN).toList()
            return FileMatches(shown, index.files)
        }
        val recentRank = HashMap<String, Int>()
        recent.forEachIndexed { i, path -> recentRank.putIfAbsent(path, i) }
        val ranked = ArrayList<Pair<Int, IndexedFile>>()
        for (file in index.files) {
            val tier = tier(file, q) ?: continue
            ranked += tier to file
        }
        val order = compareBy<Pair<Int, IndexedFile>> { it.first }
            .thenBy { recentRank[it.second.path] ?: Int.MAX_VALUE }
            .thenBy { if (it.first <= NAME_CONTAINS) it.second.name.length else 0 }
        // A stable sort, so equal files keep the list's grouped order.
        return FileMatches(emptyList(), ranked.sortedWith(order).map { it.second })
    }

    /** How well [file] matches the lower-case [query], best is 0; null when it does not. */
    internal fun tier(file: IndexedFile, query: String): Int? = when {
        file.lowerName.startsWith(query) -> NAME_PREFIX
        file.lowerName.contains(query) -> NAME_CONTAINS
        file.lowerRelative.contains(query) -> PATH_CONTAINS
        inOrder(file.lowerRelative, query) -> IN_ORDER
        else -> null
    }

    private fun inOrder(text: String, query: String): Boolean {
        var at = 0
        for (c in query) {
            if (c == ' ') continue
            at = text.indexOf(c, at)
            if (at < 0) return false
            at++
        }
        return true
    }
}
