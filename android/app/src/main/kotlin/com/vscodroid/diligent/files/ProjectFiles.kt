package com.vscodroid.diligent.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** One entry of a project folder, as the Files tab shows it. */
data class FileEntry(
    /**
     * Absolute path, under the project root exactly as the editor was given it.
     *
     * Not the canonical path: a folder from device storage is opened through a
     * link named after it (`saf-mirrors/by-name/<hash>/<name>`, see
     * `SafStorageManager.namedPathFor`), and a file sent to the editor under the
     * link's target would open as a second, out-of-workspace copy of itself.
     */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    /** A dot file: shown, but dimmed. */
    val isHidden: Boolean = name.startsWith("."),
    /** `.git` or `node_modules`: a folder nobody browses by hand that can hold tens of thousands of entries. */
    val isHeavy: Boolean = isDirectory && name in HEAVY_FOLDERS,
    /** A symbolic link. Its own row can be renamed, moved and deleted; what it points at is never touched. */
    val isLink: Boolean = false,
    /** A link that resolves outside the project: listed so nothing is hidden, but never opened or entered. */
    val leavesProject: Boolean = false,
) {
    companion object {
        val HEAVY_FOLDERS = setOf(".git", "node_modules")
    }
}

/** Why an operation was refused. The screen turns each into a sentence. */
enum class FileProblem {
    EmptyName,
    /** Holds `/` or a NUL, is `.` or `..`, or is longer than a file name can be. */
    BadName,
    NameTaken,
    /** The path, or what it resolves to through a link, is not inside the project. */
    OutsideProject,
    Missing,
    NotAFolder,
    /** The project folder itself cannot be renamed, moved or deleted from here. */
    ProjectRoot,
    /** A folder cannot be moved into itself or into one of its own folders. */
    IntoItself,
    /** The item is already in that folder. */
    AlreadyThere,
    /** The file system said no (permissions, a full disk, a race with another writer). */
    Failed,
}

/** What an operation gave back: its result, or why there is none. */
sealed interface FileResult<out T> {
    data class Done<T>(val value: T) : FileResult<T>
    data class Refused(val problem: FileProblem) : FileResult<Nothing>
}

/**
 * The file operations of the Files tab, confined to one project folder.
 *
 * **Confinement.** Every path an operation is handed is checked against the
 * project root by canonical path, so `..` segments and symbolic links are both
 * resolved before the comparison: a link inside the project that points at
 * `/` cannot be listed, written into or deleted through. Two different checks
 * are used on purpose. A folder that is read or written into must resolve,
 * links and all, to somewhere inside. An entry that is itself renamed, moved
 * or deleted is located by its parent's canonical path plus its own name, so a
 * link is acted on as a link (the link goes, its target stays) and the check
 * still sees where the link really lives.
 *
 * **Folders from device storage.** A folder picked from shared storage is
 * edited through a mirror in the app's files directory, and `shell.folder` is
 * the mirror's path. Nothing special is needed here: `SafSyncEngine` watches
 * every directory of the open mirror with a `FileObserver` and writes creates,
 * deletes, renames and moves back to the device folder whoever made them (the
 * editor, the terminal or this class), and the editor's own `@parcel/watcher`
 * sees the same inotify events and updates its Explorer. Two consequences
 * follow from using plain file system calls. A rename is one `rename(2)`, so
 * the engine pairs its MOVED_FROM and MOVED_TO and moves the device's own
 * document, subtree included, rather than deleting and re-uploading it. And a
 * folder delete unlinks children before the folder, which is the `rm -r` shape
 * the engine propagates, unlike the editor's Explorer, which moves a folder to
 * its trash and leaves the device copy behind.
 *
 * Every operation runs on [io], never on the caller's thread.
 */
class ProjectFiles(
    root: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The root as the editor knows it, normalised but with links left in place (see [FileEntry.path]). */
    val root: String = File(root).absoluteFile.toPath().normalize().toString()

    /** The entries of [folder], folders first, each group in [NaturalOrder]. */
    suspend fun list(folder: String): FileResult<List<FileEntry>> = withContext(io) {
        guarded {
            val real = folderInside(folder) ?: return@guarded FileResult.Refused(FileProblem.OutsideProject)
            if (!real.exists()) return@guarded FileResult.Refused(FileProblem.Missing)
            if (!real.isDirectory) return@guarded FileResult.Refused(FileProblem.NotAFolder)
            val names = real.list() ?: return@guarded FileResult.Refused(FileProblem.Failed)
            val canonicalRoot = canonicalRoot()
            val entries = names.map { name -> entryFor(File(folder, name), File(real, name), canonicalRoot) }
            FileResult.Done(sorted(entries))
        }
    }

    /** Whether [path] is a folder inside the project that can be listed. */
    suspend fun isFolder(path: String): Boolean = withContext(io) {
        try {
            folderInside(path)?.isDirectory == true
        } catch (e: IOException) {
            false
        }
    }

    suspend fun createFile(parent: String, name: String): FileResult<String> =
        create(parent, name) { Files.createFile(it) }

    suspend fun createFolder(parent: String, name: String): FileResult<String> =
        create(parent, name) { Files.createDirectory(it) }

    private suspend fun create(parent: String, rawName: String, make: (Path) -> Unit): FileResult<String> =
        withContext(io) {
            guarded {
                val name = rawName.trim()
                checkName(name)?.let { return@guarded FileResult.Refused(it) }
                val real = folderInside(parent) ?: return@guarded FileResult.Refused(FileProblem.OutsideProject)
                if (!real.isDirectory) return@guarded FileResult.Refused(FileProblem.NotAFolder)
                val target = File(real, name)
                if (existsNoFollow(target)) return@guarded FileResult.Refused(FileProblem.NameTaken)
                try {
                    make(target.toPath())
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    return@guarded FileResult.Refused(FileProblem.NameTaken)
                }
                FileResult.Done(File(parent, name).path)
            }
        }

    /** Renames [path] in place to [newName], and answers its new path. */
    suspend fun rename(path: String, newName: String): FileResult<String> = withContext(io) {
        guarded {
            val name = newName.trim()
            checkName(name)?.let { return@guarded FileResult.Refused(it) }
            val location = when (val found = entryInside(path)) {
                is FileResult.Refused -> return@guarded found
                is FileResult.Done -> found.value
            }
            val newPath = File(File(path).parentFile, name).path
            if (name == location.name) return@guarded FileResult.Done(newPath)
            val target = File(location.parentFile, name)
            // On a case-insensitive file system `readme.md` to `README.md` finds
            // the file itself at the new name; that is a rename, not a clash.
            if (existsNoFollow(target)) {
                if (!sameEntry(location, target)) return@guarded FileResult.Refused(FileProblem.NameTaken)
                // `Files.move` treats a target that is the source as nothing to
                // do, so the case-only rename goes to rename(2) directly.
                return@guarded if (location.renameTo(target)) FileResult.Done(newPath)
                else FileResult.Refused(FileProblem.Failed)
            }
            Files.move(location.toPath(), target.toPath(), LinkOption.NOFOLLOW_LINKS)
            FileResult.Done(newPath)
        }
    }

    /** Moves [path] into [folder], keeping its name, and answers its new path. */
    suspend fun move(path: String, folder: String): FileResult<String> = withContext(io) {
        guarded {
            val location = when (val found = entryInside(path)) {
                is FileResult.Refused -> return@guarded found
                is FileResult.Done -> found.value
            }
            val destination = folderInside(folder) ?: return@guarded FileResult.Refused(FileProblem.OutsideProject)
            if (!destination.isDirectory) return@guarded FileResult.Refused(FileProblem.NotAFolder)
            if (destination == location || isUnder(destination, location)) {
                return@guarded FileResult.Refused(FileProblem.IntoItself)
            }
            if (destination == location.parentFile) return@guarded FileResult.Refused(FileProblem.AlreadyThere)
            val target = File(destination, location.name)
            if (existsNoFollow(target)) return@guarded FileResult.Refused(FileProblem.NameTaken)
            Files.move(location.toPath(), target.toPath(), LinkOption.NOFOLLOW_LINKS)
            FileResult.Done(File(folder, location.name).path)
        }
    }

    /**
     * Deletes [path], and everything under it when it is a folder. A link is
     * removed as a link: the walk does not follow links, so nothing outside
     * the project is reached through one.
     */
    suspend fun delete(path: String): FileResult<Unit> = withContext(io) {
        guarded {
            val location = when (val found = entryInside(path)) {
                is FileResult.Refused -> return@guarded found
                is FileResult.Done -> found.value
            }
            Files.walkFileTree(
                location.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            FileResult.Done(Unit)
        }
    }

    /** [path] relative to the root, `""` for the root itself; for labels such as "New in src/app". */
    fun relative(path: String): String {
        val normal = File(path).absoluteFile.toPath().normalize().toString()
        return when {
            normal == root -> ""
            normal.startsWith(root + File.separator) -> normal.substring(root.length + 1)
            else -> normal
        }
    }

    // -- Confinement --

    private fun canonicalRoot(): File = File(root).canonicalFile

    /** [path] with every link resolved, when that is inside the project; null otherwise. */
    private fun folderInside(path: String): File? {
        val real = File(path).canonicalFile
        val root = canonicalRoot()
        return real.takeIf { it == root || isUnder(it, root) }
    }

    /**
     * Where the entry [path] really lives: its parent's canonical path and its
     * own name, with a final link left unresolved. Refused for the root itself
     * and for anything whose parent is not inside the project.
     */
    private fun entryInside(path: String): FileResult<File> {
        val file = File(path).absoluteFile.toPath().normalize().toFile()
        val name = file.name
        if (name.isEmpty() || name == "." || name == "..") return FileResult.Refused(FileProblem.BadName)
        if (file.path == root) return FileResult.Refused(FileProblem.ProjectRoot)
        val canonicalRoot = canonicalRoot()
        val parent = file.parentFile?.canonicalFile ?: return FileResult.Refused(FileProblem.OutsideProject)
        val location = File(parent, name)
        if (location == canonicalRoot) return FileResult.Refused(FileProblem.ProjectRoot)
        if (parent != canonicalRoot && !isUnder(parent, canonicalRoot)) {
            return FileResult.Refused(FileProblem.OutsideProject)
        }
        if (!existsNoFollow(location)) return FileResult.Refused(FileProblem.Missing)
        return FileResult.Done(location)
    }

    private fun entryFor(shown: File, real: File, canonicalRoot: File): FileEntry {
        val path = real.toPath()
        val isLink = Files.isSymbolicLink(path)
        val leaves = isLink && try {
            val target = real.canonicalFile
            target != canonicalRoot && !isUnder(target, canonicalRoot)
        } catch (e: IOException) {
            true
        }
        return FileEntry(
            path = shown.path,
            name = real.name,
            // A link out of the project is never entered, so it is shown as a
            // file whatever it points at, and a dangling link is a file too.
            isDirectory = !leaves && Files.isDirectory(path),
            isLink = isLink,
            leavesProject = leaves,
        )
    }

    /**
     * Runs [block], turning a failure of the file system into [FileProblem.Failed]
     * rather than a crash. A [SecurityException] is included: on Android it is
     * what a denied storage permission looks like.
     */
    private inline fun <T> guarded(block: () -> FileResult<T>): FileResult<T> = try {
        block()
    } catch (e: IOException) {
        FileResult.Refused(FileProblem.Failed)
    } catch (e: SecurityException) {
        FileResult.Refused(FileProblem.Failed)
    }

    companion object {
        /** The longest name most Linux file systems accept, in bytes. */
        private const val MAX_NAME_BYTES = 255

        /**
         * Why [name] (already trimmed) cannot name a new file or folder, or null when it can.
         *
         * `..` is refused as a whole name, which is the only way it can climb a
         * level once `/` is refused; `notes..md` is an ordinary name and stays allowed.
         */
        fun checkName(name: String): FileProblem? = when {
            name.isEmpty() -> FileProblem.EmptyName
            '/' in name || '\u0000' in name || name == "." || name == ".." -> FileProblem.BadName
            name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> FileProblem.BadName
            else -> null
        }

        /** Folders first, then files, each in [NaturalOrder]. */
        fun sorted(entries: List<FileEntry>): List<FileEntry> =
            entries.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy(NaturalOrder) { it.name })

        private fun isUnder(file: File, folder: File): Boolean =
            file.path.startsWith(folder.path.trimEnd(File.separatorChar) + File.separator)

        private fun existsNoFollow(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

        /**
         * Whether [a] and [b] are one directory entry, without following links:
         * renaming link `a` (to `b`) as `b` must find `b` taken, not find the
         * link's target and call it the same file.
         */
        private fun sameEntry(a: File, b: File): Boolean = try {
            val keyA = Files.readAttributes(a.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
            val keyB = Files.readAttributes(b.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
            keyA != null && keyA == keyB
        } catch (e: IOException) {
            false
        }
    }
}

/**
 * Case-insensitive natural order: runs of digits compare by value, so
 * `file2` comes before `file10`. Ties (`a` and `A`, `1` and `01`) fall back to
 * the plain string order, so the order is total and a listing never shuffles
 * between refreshes.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val startA = i
                while (i < a.length && a[i].isAsciiDigit()) i++
                val startB = j
                while (j < b.length && b[j].isAsciiDigit()) j++
                val numberA = a.substring(startA, i).trimStart('0')
                val numberB = b.substring(startB, j).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length - numberB.length
                val byValue = numberA.compareTo(numberB)
                if (byValue != 0) return byValue
            } else {
                val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val byRest = (a.length - i) - (b.length - j)
        return if (byRest != 0) byRest else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
