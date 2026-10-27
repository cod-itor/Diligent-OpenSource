package com.vscodroid.diligent.projects

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** How importing a zip ended. */
sealed interface ZipImport {
    /** Unpacked into [folder]. [skippedLinks] symbolic links in the zip were left out. */
    data class Imported(val folder: File, val skippedLinks: Int) : ZipImport

    /** An entry would land outside the project (`..`, an absolute path): nothing was unpacked. */
    data class Unsafe(val entry: String) : ZipImport

    /** Not a zip this can read (broken, or a format the reader does not know). */
    data object NotAZip : ZipImport

    /** No files in it. */
    data object Empty : ZipImport

    /** More files or bytes than a project from a phone has; nothing was kept. */
    data object TooBig : ZipImport

    /** The disk said no part of the way (full, or the folder could not be made). Nothing was kept. */
    data object Failed : ZipImport
}

/**
 * Sharing a project as a zip: export without what can be made again, and
 * import with the checks a zip from anywhere needs.
 *
 * **Export** leaves out installed packages and build output ([skips]): a
 * zip that fits in a chat message, and what the other side runs
 * `npm install` or `pip install` for anyway. `.git` is kept, history and
 * Diligent's snapshots with it: a project that arrives without them is
 * one the other side cannot go back in, and they are small next to
 * `node_modules`. Symbolic links are not followed or stored.
 *
 * **Import** unpacks into a new folder of the projects folder and never
 * over an existing one ([ProjectNames.free] adds a suffix). An entry that
 * would land outside it (`..`, an absolute path, a drive letter) refuses
 * the whole zip before anything is written ("zip slip"); symbolic links
 * are left out; only plain files and folders are made. Unpacking goes to a
 * hidden folder first and is renamed into place at the end, so a failure
 * halfway leaves nothing behind. A zip whose files all sit in one top
 * folder (what [export] writes) is unpacked without that extra level.
 *
 * Plain Kotlin and java.io, tested on the JVM; touches the disk, so call
 * it off the main thread.
 */
object ProjectZip {
    /** Folders left out of an export wherever they are: packages, caches and build output. */
    val SKIPPED_FOLDERS = setOf(
        "node_modules", ".venv", "__pycache__", ".next", "dist", ".turbo", ".parcel-cache",
        ".pytest_cache", ".mypy_cache", ".gradle",
    )

    /** Files left out wherever they are, by ending: compiled Python. */
    private val SKIPPED_ENDINGS = listOf(".pyc")

    /** The most entries an import takes. */
    const val MAX_ENTRIES = 200_000

    /** The most bytes an import writes, unpacked. */
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    /**
     * Whether an export leaves out [relativePath] (with `/` between folders),
     * which is a folder when [isDirectory]: inside or itself one of
     * [SKIPPED_FOLDERS], or a compiled Python file.
     */
    fun skips(relativePath: String, isDirectory: Boolean): Boolean {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        val folders = if (isDirectory) segments else segments.dropLast(1)
        if (folders.any { it in SKIPPED_FOLDERS }) return true
        return !isDirectory && SKIPPED_ENDINGS.any { segments.last().endsWith(it) }
    }

    /**
     * Writes the project folder [root] as a zip to [out], every entry under
     * one top folder named after it. Answers how many files went in.
     */
    fun export(root: File, out: OutputStream): Int {
        val top = root.name.ifEmpty { ProjectNames.FALLBACK_NAME }
        var count = 0
        ZipOutputStream(out.buffered()).use { zip ->
            root.walkTopDown()
                .onEnter { dir -> dir == root || (!isLink(dir) && !skips(dir.relativeTo(root).invariantSeparatorsPath, true)) }
                .filter { it.isFile && !isLink(it) && !skips(it.relativeTo(root).invariantSeparatorsPath, false) }
                .forEach { file ->
                    val entry = ZipEntry("$top/${file.relativeTo(root).invariantSeparatorsPath}")
                    entry.time = file.lastModified()
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
        }
        return count
    }

    /**
     * An entry's name as a safe path inside the project, with `/` between
     * folders: null when it is not safe (absolute, a drive letter, a `..`
     * anywhere, a NUL), and empty for the zip's own root.
     */
    fun entryPath(name: String): String? {
        val unified = name.replace('\\', '/')
        if (unified.startsWith("/") || '\u0000' in unified) return null
        if (unified.length >= 2 && unified[1] == ':' && unified[0].isLetter()) return null
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    /** The one top folder every path in [paths] sits in, or null when there is none or a file sits at the top. */
    fun commonTop(paths: List<Pair<String, Boolean>>): String? {
        val nonEmpty = paths.filter { it.first.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null
        val first = nonEmpty.first().first.substringBefore('/')
        val shared = nonEmpty.all { (path, isDir) ->
            path.substringBefore('/') == first && (path.contains('/') || isDir)
        }
        return if (shared && nonEmpty.any { it.first.contains('/') }) first else null
    }

    /**
     * Unpacks [zip] into a new folder of [projectsDir]: named after its one
     * top folder, else [fallbackName], with a suffix when that is taken.
     */
    fun import(zip: File, projectsDir: File, fallbackName: String): ZipImport {
        val links = try {
            ZipLinks.of(zip)
        } catch (_: IOException) {
            return ZipImport.NotAZip
        }
        val archive = try {
            ZipFile(zip)
        } catch (_: ZipException) {
            return ZipImport.NotAZip
        } catch (_: IOException) {
            return ZipImport.Failed
        }
        archive.use { files ->
            val entries = files.entries().asSequence().take(MAX_ENTRIES + 1).toList()
            if (entries.size > MAX_ENTRIES) return ZipImport.TooBig
            // Every name is checked before anything is written.
            val planned = mutableListOf<Pair<ZipEntry, String>>()
            var skippedLinks = 0
            for (entry in entries) {
                val path = entryPath(entry.name) ?: return ZipImport.Unsafe(entry.name)
                if (entry.name in links) {
                    skippedLinks++
                    continue
                }
                if (path.isNotEmpty()) planned += entry to path
            }
            if (planned.none { !it.first.isDirectory }) return ZipImport.Empty

            val top = commonTop(planned.map { it.second to it.first.isDirectory })
            val base = top ?: fallbackName
            val name = ProjectNames.free(base.takeIf { ProjectNames.problem(it) { false } == null } ?: ProjectNames.FALLBACK_NAME) {
                File(projectsDir, it).exists()
            }
            val staging = File(projectsDir, ".$name.importing-${System.nanoTime()}")
            val outcome = try {
                unpack(files, planned, top, staging)
            } catch (_: IOException) {
                ZipImport.Failed
            }
            if (outcome != null) {
                staging.deleteRecursively()
                return outcome
            }
            val target = File(projectsDir, ProjectNames.free(name) { File(projectsDir, it).exists() })
            if (!staging.renameTo(target)) {
                staging.deleteRecursively()
                return ZipImport.Failed
            }
            return ZipImport.Imported(target, skippedLinks)
        }
    }

    /** Writes [planned] into [staging]; null when it all went in, else why not. */
    private fun unpack(files: ZipFile, planned: List<Pair<ZipEntry, String>>, top: String?, staging: File): ZipImport? {
        if (!staging.mkdirs()) return ZipImport.Failed
        val root = staging.canonicalFile
        var written = 0L
        for ((entry, path) in planned) {
            val inside = if (top == null) path else path.removePrefix(top).removePrefix("/")
            if (inside.isEmpty()) continue
            val dest = File(root, inside)
            // The names were checked already; this is the same rule against the real disk.
            if (!dest.canonicalPath.startsWith(root.path + File.separator)) return ZipImport.Unsafe(entry.name)
            if (entry.isDirectory) {
                if (!dest.isDirectory && !dest.mkdirs()) return ZipImport.Failed
                continue
            }
            val parent = dest.parentFile ?: return ZipImport.Failed
            if (!parent.isDirectory && !parent.mkdirs()) return ZipImport.Failed
            files.getInputStream(entry).use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > MAX_BYTES) return ZipImport.TooBig
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (entry.time > 0) dest.setLastModified(entry.time)
        }
        return null
    }

    private fun isLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    private const val BUFFER = 64 * 1024
}

/**
 * The names of a zip's symbolic links. java.util.zip does not say which
 * entries are links, so this reads the central directory itself: a link
 * is an entry made on Unix whose external attributes carry `S_IFLNK`.
 * Throws when the file has no central directory it can read, including
 * ZIP64, which no project from a phone needs.
 */
internal object ZipLinks {
    private const val END_SIGNATURE = 0x06054b50
    private const val ENTRY_SIGNATURE = 0x02014b50
    private const val END_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val UNIX = 3
    private const val TYPE_MASK = 0xF000
    private const val TYPE_LINK = 0xA000

    fun of(zip: File): Set<String> = RandomAccessFile(zip, "r").use { file ->
        val length = file.length()
        if (length < END_SIZE) throw IOException("too short")
        val tailSize = minOf(length, (END_SIZE + MAX_COMMENT).toLong()).toInt()
        val tail = ByteArray(tailSize)
        file.seek(length - tailSize)
        file.readFully(tail)
        val end = (tailSize - END_SIZE downTo 0).firstOrNull { tail.int(it) == END_SIGNATURE }
            ?: throw IOException("no end of central directory")
        val count = tail.short(end + 10)
        val size = tail.int(end + 12).toLong() and 0xFFFFFFFFL
        val offset = tail.int(end + 16).toLong() and 0xFFFFFFFFL
        if (count == 0xFFFF || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) throw IOException("ZIP64")
        if (offset + size > length) throw IOException("central directory out of range")
        val directory = ByteArray(size.toInt())
        file.seek(offset)
        file.readFully(directory)
        val links = mutableSetOf<String>()
        var at = 0
        repeat(count) {
            if (at + 46 > directory.size || directory.int(at) != ENTRY_SIGNATURE) throw IOException("broken central directory")
            val madeBy = directory.short(at + 4) shr 8
            val nameLength = directory.short(at + 28)
            val extraLength = directory.short(at + 30)
            val commentLength = directory.short(at + 32)
            val mode = (directory.int(at + 38) ushr 16) and 0xFFFF
            if (at + 46 + nameLength > directory.size) throw IOException("broken central directory")
            if (madeBy == UNIX && (mode and TYPE_MASK) == TYPE_LINK) {
                links += String(directory, at + 46, nameLength, Charsets.UTF_8)
            }
            at += 46 + nameLength + extraLength + commentLength
        }
        links
    }

    private fun ByteArray.short(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.int(at: Int): Int = short(at) or (short(at + 2) shl 16)
}
