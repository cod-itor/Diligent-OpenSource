package com.vscodroid.diligent.projects

import com.vscodroid.diligent.process.BundledShell
import com.vscodroid.diligent.process.ShellResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** How a clone ended. Cancelling is not an outcome: it throws, as cancelling does, after the cleanup. */
sealed interface CloneOutcome {
    data class Done(val folder: File) : CloneOutcome
    /** Git ran and failed; [lastLines] are the end of what it said. */
    data class Failed(val lastLines: List<String>) : CloneOutcome
    /** Nothing ran: the folder name is not usable (most often, it is taken). */
    data class Refused(val problem: NameProblem) : CloneOutcome
}

/**
 * Clone from Git: `git clone` into the projects folder, never over anything.
 *
 * **The folder is made first, here.** [File.mkdir] fails when the name is
 * taken, atomically, so from then on the folder is known to be this clone's
 * and nobody else's, and git (which clones into an empty folder happily) is
 * pointed at it. That is what makes the cleanup safe: a failed or cancelled
 * clone removes that folder, and it can only ever be the one made here.
 *
 * **Removal does not follow links.** A half-checked-out repository can hold
 * symbolic links, to anywhere. Removing them walks the tree without
 * following one, so a link to the user's other projects is removed as a link.
 *
 * **Git must not wait for input.** There is no terminal to type a password
 * into. `GIT_TERMINAL_PROMPT=0` and a closed stdin make a private
 * repository fail at once with git's own message instead of hanging.
 *
 * [runShell] is [BundledShell.run] in the app and a fake in tests.
 */
class GitClone(
    private val runShell: suspend (script: String, cwd: File, onLine: (String) -> Unit) -> ShellResult,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(shell: BundledShell) : this({ script, cwd, onLine -> shell.run(script, cwd, onLine = onLine) })

    suspend fun clone(url: String, parent: File, name: String, onLine: (String) -> Unit = {}): CloneOutcome {
        val target = File(parent, ProjectNames.clean(name))
        // Not cancellable: a Stop landing between mkdir and the try below
        // would leave an empty folder that blocks the next attempt.
        val problem = withContext(NonCancellable + io) {
            ProjectNames.problemIn(parent, name) ?: run {
                parent.mkdirs()
                if (target.mkdir()) null else NameProblem.Exists
            }
        }
        if (problem != null) return CloneOutcome.Refused(problem)

        var kept = false
        try {
            val result = runShell(script(url.trim(), target.name), parent, onLine)
            if (result.succeeded) {
                kept = true
                return CloneOutcome.Done(target)
            }
            return CloneOutcome.Failed(result.lastLines)
        } finally {
            if (!kept) withContext(NonCancellable + io) { removeUntilGone(target.toPath()) }
        }
    }

    companion object {
        /** The command line. Every value from the user goes through [BundledShell.quote]. */
        fun script(url: String, folderName: String): String =
            "GIT_TERMINAL_PROMPT=0 git clone --progress -- ${BundledShell.quote(url)} ${BundledShell.quote(folderName)} < /dev/null"

        /**
         * [removeTree], a few times over: git's own signal handler may still be
         * deleting, or a helper still writing, in the moment after a Stop, and a
         * file landing behind the walk leaves its folder not empty.
         */
        internal fun removeUntilGone(root: Path, attempts: Int = REMOVE_ATTEMPTS, pauseMs: Long = REMOVE_PAUSE_MS) {
            repeat(attempts) { attempt ->
                removeTree(root)
                if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
                if (attempt < attempts - 1) Thread.sleep(pauseMs)
            }
        }

        private const val REMOVE_ATTEMPTS = 5
        private const val REMOVE_PAUSE_MS = 200L

        /** Deletes [root] and everything under it, removing links as links. Best effort: what cannot go stays. */
        internal fun removeTree(root: Path) {
            if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            try {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: IOException) {
                // Left behind is a partial folder the user can delete from the
                // Files tab; failing the screen over it would help nobody.
            }
        }
    }
}
