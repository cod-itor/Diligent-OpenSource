package com.vscodroid.diligent.devices

import com.ditarector.diligent.sync.manifest.ProjectPaths
import com.ditarector.diligent.sync.port.FileStat
import com.ditarector.diligent.sync.port.FileStore
import com.ditarector.diligent.sync.port.PathFilter
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * sync-core's [FileStore] over one project folder.
 *
 * - Every path is checked with [ProjectPaths.isValid] and resolved under
 *   [root]; a path whose folder chain contains a symbolic link is refused,
 *   so nothing a sync reads, writes or deletes can land outside the project.
 *   [list] skips links instead of following them.
 * - Received files go to [tempDir] (app data, never inside the project, so a
 *   half-received file is never listed or synced), one per path, and are
 *   moved into place atomically. When the project lives on another volume
 *   the file is first copied next to its target, then renamed there.
 */
class ProjectFileStore(root: File, private val tempDir: File) : FileStore {
    private val root: Path = root.toPath().toAbsolutePath().normalize()

    /** [path] inside the project, or an exception when it is invalid or leaves it. */
    internal fun resolve(path: String): Path {
        if (!ProjectPaths.isValid(path)) throw IOException("invalid project path: $path")
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root) || target == root) throw IOException("outside the project: $path")
        var current = root
        for (part in root.relativize(target)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw IOException("symbolic link in the way: $path")
        }
        return target
    }

    override fun list(filter: PathFilter): List<FileStat> {
        val out = ArrayList<FileStat>()
        walk(root, "", filter, out)
        return out
    }

    private fun walk(dir: Path, prefix: String, filter: PathFilter, out: MutableList<FileStat>) {
        val children = try {
            Files.newDirectoryStream(dir).use { stream -> stream.toList() }
        } catch (e: IOException) {
            return
        }
        for (child in children.sortedBy { it.fileName.toString() }) {
            val rel = prefix + child.fileName.toString()
            if (Files.isSymbolicLink(child)) continue
            when {
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ->
                    if (filter.include(rel, true)) walk(child, "$rel/", filter, out)
                Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) ->
                    if (filter.include(rel, false)) {
                        out += FileStat(
                            path = rel,
                            size = Files.size(child),
                            mtimeMs = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis(),
                            executable = child.toFile().canExecute(),
                        )
                    }
            }
        }
    }

    override fun readText(path: String): String? {
        val file = resolve(path)
        return if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) String(Files.readAllBytes(file), Charsets.UTF_8) else null
    }

    override fun sha256(path: String): String = hashFile(resolve(path))

    override fun read(path: String, offset: Long, length: Int): ByteArray {
        RandomAccessFile(resolve(path).toFile(), "r").use { file ->
            file.seek(offset)
            val buffer = ByteArray(length)
            var done = 0
            while (done < length) {
                val n = file.read(buffer, done, length - done)
                if (n < 0) break
                done += n
            }
            return if (done == length) buffer else buffer.copyOf(done)
        }
    }

    override fun exists(path: String): Boolean = Files.exists(resolve(path), LinkOption.NOFOLLOW_LINKS)

    /** The temporary file for [path]: named by the hash of the path, so any path maps to one flat name. */
    internal fun tempFile(path: String): File {
        resolve(path)
        tempDir.mkdirs()
        return File(tempDir, JvmHashing.sha256Hex(path.toByteArray(Charsets.UTF_8)) + ".part")
    }

    override fun writeTemp(path: String, offset: Long, bytes: ByteArray) {
        val temp = tempFile(path)
        if (offset == 0L) temp.delete()
        RandomAccessFile(temp, "rw").use { file ->
            if (file.length() != offset) throw IOException("temporary file for $path is not at offset $offset")
            file.seek(offset)
            file.write(bytes)
        }
    }

    override fun tempSha256(path: String): String = hashFile(tempFile(path).toPath())

    override fun commitTemp(path: String, target: String, executable: Boolean) {
        val temp = tempFile(path).toPath()
        val destination = resolve(target)
        Files.createDirectories(destination.parent)
        // Created folders may not be links either; check again now that they exist.
        resolve(target)
        try {
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            // Another volume: copy beside the target, then rename on that volume.
            val beside = destination.resolveSibling(".${destination.fileName}.diligent-part")
            Files.copy(temp, beside, StandardCopyOption.REPLACE_EXISTING)
            Files.move(beside, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(temp)
        } catch (e: IOException) {
            if (Files.exists(temp)) {
                val beside = destination.resolveSibling(".${destination.fileName}.diligent-part")
                Files.copy(temp, beside, StandardCopyOption.REPLACE_EXISTING)
                Files.move(beside, destination, StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(temp)
            } else {
                throw e
            }
        }
        destination.toFile().setExecutable(executable, false)
    }

    override fun discardTemp(path: String) {
        tempFile(path).delete()
    }

    override fun delete(path: String) {
        val file = resolve(path)
        Files.deleteIfExists(file)
        var parent = file.parent
        while (parent != null && parent != root && parent.startsWith(root)) {
            val empty = Files.newDirectoryStream(parent).use { !it.iterator().hasNext() }
            if (!empty) break
            Files.delete(parent)
            parent = parent.parent
        }
    }

    private fun hashFile(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return JvmHashing.hex(digest.digest())
    }
}
