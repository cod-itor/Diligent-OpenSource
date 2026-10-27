package com.vscodroid.diligent.snapshot

import com.vscodroid.diligent.process.BundledShell
import com.vscodroid.diligent.process.BundledShell.Companion.quote
import java.io.File
import java.io.IOException

/**
 * Snapshots from Kotlin, with exactly the Git plumbing of the diligent-core
 * extension's `snapshots.js`, so a snapshot taken here shows in the editor's
 * snapshot list (Undo) beside the ones the extension takes:
 *
 *     export GIT_INDEX_FILE=<git dir>/diligent-index
 *     git add -A && tree=$(git write-tree)
 *     commit=$(git commit-tree "$tree" -p refs/diligent/snapshots -m "diligent: <reason>")
 *     git update-ref refs/diligent/snapshots "$commit" <old value>
 *
 * A private index keeps the user's staging area as it is, and nothing touches
 * HEAD, the branch or the files. Commits carry Diligent's own identity, as the
 * extension's do. Restoring takes a "before restore" snapshot first, then runs
 * `git checkout <commit> -- .` through the private index.
 *
 * Unlike the extension's `take`, a snapshot of files that did not change since
 * the last one answers that last commit instead of nothing: a caller recording
 * "the files as they were when this started" needs a commit either way.
 * Cutting the chain back to its newest 100 is left to the extension, which
 * does it on its next snapshot, or to the Storage screen ([trimScript]).
 *
 * Every value goes into the scripts through [quote]; the reason also reaches
 * Git as a positional parameter, never as script text.
 */
object GitSnapshots {
    const val REF = "refs/diligent/snapshots"
    const val INDEX_NAME = "diligent-index"
    const val MESSAGE_PREFIX = "diligent: "

    /** Exit code of the scripts when the folder is not in a Git repository. */
    const val NOT_A_REPOSITORY = 3

    /** What the core extension excludes when it makes a folder a repository. */
    val FRESH_EXCLUDES = listOf("node_modules/", ".venv/", "venv/", "__pycache__/", ".next/", "dist/", "build/")

    private val COMMIT = Regex("^[0-9a-f]{40,64}$")
    private const val SNAPSHOT_LINE = "snapshot "

    /** A reason as one line of a commit message, as `cleanReason` in snapshots.js. */
    fun cleanReason(reason: String): String = reason.replace(Regex("\\s+"), " ").trim().ifEmpty { "snapshot" }.take(200)

    fun isCommit(text: String): Boolean = COMMIT.matches(text)

    /**
     * A bash function `diligent_take <reason>` that takes one snapshot and
     * prints `snapshot <commit>`. Returns [NOT_A_REPOSITORY] outside a repository.
     */
    private val TAKE_FUNCTION = listOf(
        "diligent_take() {",
        "  local gd tree parent ptree commit",
        "  gd=\$(git rev-parse --absolute-git-dir 2>/dev/null) || { echo 'Not a Git repository'; return $NOT_A_REPOSITORY; }",
        "  tree=\$(GIT_INDEX_FILE=\"\$gd/$INDEX_NAME\" git add -A && GIT_INDEX_FILE=\"\$gd/$INDEX_NAME\" git write-tree) || return 1",
        "  parent=\$(git rev-parse --verify -q ${quote("$REF^{commit}")})",
        "  if [ -n \"\$parent\" ]; then",
        "    ptree=\$(git rev-parse \"\$parent^{tree}\") || return 1",
        "    if [ \"\$ptree\" = \"\$tree\" ]; then printf '$SNAPSHOT_LINE%s\\n' \"\$parent\"; return 0; fi",
        "    commit=\$(GIT_AUTHOR_NAME=Diligent GIT_AUTHOR_EMAIL=diligent@localhost GIT_COMMITTER_NAME=Diligent GIT_COMMITTER_EMAIL=diligent@localhost " +
            "git commit-tree \"\$tree\" -p \"\$parent\" -m \"$MESSAGE_PREFIX\$1\") || return 1",
        "  else",
        "    commit=\$(GIT_AUTHOR_NAME=Diligent GIT_AUTHOR_EMAIL=diligent@localhost GIT_COMMITTER_NAME=Diligent GIT_COMMITTER_EMAIL=diligent@localhost " +
            "git commit-tree \"\$tree\" -m \"$MESSAGE_PREFIX\$1\") || return 1",
        "  fi",
        // The old value makes the update refuse if another snapshot moved the ref meanwhile.
        "  git update-ref ${quote(REF)} \"\$commit\" \"\$parent\" || return 1",
        "  printf '$SNAPSHOT_LINE%s\\n' \"\$commit\"",
        "}",
    ).joinToString("\n")

    /**
     * Takes a snapshot of the repository the script runs in. With [initIfNeeded],
     * a folder that is not a repository is first made one (`git init`), with the
     * same excludes the core extension writes, so dependency folders stay out.
     */
    fun takeScript(reason: String, initIfNeeded: Boolean = false): String = buildList {
        if (initIfNeeded) {
            add("if ! git rev-parse --absolute-git-dir >/dev/null 2>&1; then")
            add("  git init -q || exit 1")
            add("  gd=\$(git rev-parse --absolute-git-dir) || exit 1")
            add("  mkdir -p \"\$gd/info\" && printf '%s\\n' ${FRESH_EXCLUDES.joinToString(" ") { quote(it) }} >> \"\$gd/info/exclude\"")
            add("fi")
        }
        add(TAKE_FUNCTION)
        add("diligent_take ${quote(cleanReason(reason))}")
    }.joinToString("\n")

    /**
     * Puts the files back as they were in [commit], after a "before restore"
     * snapshot. Files created since are left in place, as the extension's
     * restore does.
     */
    fun restoreScript(commit: String): String {
        require(isCommit(commit)) { "not a snapshot: $commit" }
        return listOf(
            TAKE_FUNCTION,
            "diligent_take 'before restore' || exit \$?",
            "gd=\$(git rev-parse --absolute-git-dir) || exit 1",
            "GIT_INDEX_FILE=\"\$gd/$INDEX_NAME\" git checkout ${quote(commit)} -- . || exit 1",
            "echo restored",
        ).joinToString("\n")
    }

    /** How many snapshots the core extension keeps (`KEEP` in snapshots.js), and the Storage screen trims to. */
    const val KEEP = 100

    private const val EXCESS_LINE = "excess "
    private const val IDENTITY_ENV = "GIT_AUTHOR_NAME=Diligent GIT_AUTHOR_EMAIL=diligent@localhost " +
        "GIT_COMMITTER_NAME=Diligent GIT_COMMITTER_EMAIL=diligent@localhost"

    /**
     * Prints `excess <count> <bytes>`: how many snapshots there are beyond the
     * newest [keep], and about how many bytes on disk only they hold. Those are
     * the objects reachable from the older snapshots and from nothing else:
     * not from a branch, tag, remote, the stash or HEAD (`--all` without
     * Diligent's own ref), and not from the newest [keep] snapshots' trees.
     * `excess 0 0` when there is no snapshot ref or nothing beyond [keep].
     */
    fun excessScript(keep: Int = KEEP): String = listOf(
        "git rev-parse --absolute-git-dir >/dev/null 2>&1 || { echo 'Not a Git repository'; exit $NOT_A_REPOSITORY; }",
        "count=\$(git rev-list --count ${quote(REF)} 2>/dev/null) || count=0",
        "if [ \"\$count\" -le $keep ]; then echo '${EXCESS_LINE}0 0'; exit 0; fi",
        "trees=\$(git log --format=%T -n $keep ${quote(REF)} --) || exit 1",
        // Word splitting of $trees is wanted: one argument per tree.
        "bytes=\$(git rev-list --objects --disk-usage ${quote("$REF~$keep")} --not --exclude=${quote("refs/diligent/*")} --all \$trees) || bytes=0",
        "printf '$EXCESS_LINE%s %s\\n' \"\$((count - $keep))\" \"\$bytes\"",
    ).joinToString("\n")

    /** The count and bytes an [excessScript] printed, or null. */
    fun parseExcess(lines: List<String>): Pair<Int, Long>? {
        val parts = lines.lastOrNull { it.startsWith(EXCESS_LINE) }?.removePrefix(EXCESS_LINE)?.trim()?.split(' ') ?: return null
        val count = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val bytes = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return count to bytes
    }

    /**
     * Cuts the chain back to its newest [keep] snapshots, as `prune` in
     * snapshots.js does (with no slack): the newest [keep] are written again,
     * oldest first, with their own dates and messages, the ref moves only if
     * no other snapshot moved it meanwhile, and `git gc --prune=now` then
     * removes the old commits and whatever only they held.
     *
     * `--prune=now` also removes any other object nothing reaches, which Git
     * would remove on its own after two weeks; the reflog still protects
     * recent branch history. The files and the user's branches are untouched.
     */
    fun trimScript(keep: Int = KEEP): String = listOf(
        "git rev-parse --absolute-git-dir >/dev/null 2>&1 || { echo 'Not a Git repository'; exit $NOT_A_REPOSITORY; }",
        "count=\$(git rev-list --count ${quote(REF)} 2>/dev/null) || count=0",
        "if [ \"\$count\" -le $keep ]; then echo trimmed; exit 0; fi",
        "old=\$(git rev-parse ${quote(REF)}) || exit 1",
        "lines=\$(git log --reverse --format='%T%x09%at%x09%ct%x09%s' -n $keep ${quote(REF)} --) || exit 1",
        "parent=''",
        "while IFS=\$'\\t' read -r tree authored committed subject; do",
        "  [ -n \"\$tree\" ] || continue",
        "  if [ -n \"\$parent\" ]; then set -- -p \"\$parent\"; else set --; fi",
        "  parent=\$($IDENTITY_ENV GIT_AUTHOR_DATE=\"@\$authored +0000\" GIT_COMMITTER_DATE=\"@\$committed +0000\" " +
            "git commit-tree \"\$tree\" \"\$@\" -m \"\$subject\") || exit 1",
        "done <<< \"\$lines\"",
        "[ -n \"\$parent\" ] || exit 1",
        "git update-ref ${quote(REF)} \"\$parent\" \"\$old\" || exit 1",
        "git gc --prune=now --quiet || true",
        "echo trimmed",
    ).joinToString("\n")

    /** The commit a take script printed, or null. */
    fun parseTake(lines: List<String>): String? =
        lines.lastOrNull { it.startsWith(SNAPSHOT_LINE) }?.removePrefix(SNAPSHOT_LINE)?.trim()?.takeIf(::isCommit)
}

/** What a snapshot step ended with. */
sealed interface SnapshotResult {
    data class Taken(val commit: String) : SnapshotResult
    data object NotARepository : SnapshotResult
    data class Failed(val lines: List<String>) : SnapshotResult
}

/** Runs [GitSnapshots]' scripts in a project folder through [BundledShell]. */
class SnapshotRunner(private val shell: BundledShell) {

    suspend fun take(projectDir: File, reason: String, initIfNeeded: Boolean = false): SnapshotResult = try {
        val r = shell.run(GitSnapshots.takeScript(reason, initIfNeeded), projectDir)
        val commit = GitSnapshots.parseTake(r.lastLines)
        when {
            r.succeeded && commit != null -> SnapshotResult.Taken(commit)
            r.exitCode == GitSnapshots.NOT_A_REPOSITORY -> SnapshotResult.NotARepository
            else -> SnapshotResult.Failed(r.lastLines)
        }
    } catch (e: IOException) {
        SnapshotResult.Failed(listOfNotNull(e.message))
    }

    /** Snapshots beyond the newest [GitSnapshots.KEEP] and the bytes only they hold, or null when unknown. */
    suspend fun excess(projectDir: File): Pair<Int, Long>? = try {
        val r = shell.run(GitSnapshots.excessScript(), projectDir)
        if (r.succeeded) GitSnapshots.parseExcess(r.lastLines) else null
    } catch (e: IOException) {
        null
    }

    /** True when the chain was cut back to the newest [GitSnapshots.KEEP] (or was already that short). */
    suspend fun trim(projectDir: File): Boolean = try {
        val r = shell.run(GitSnapshots.trimScript(), projectDir)
        r.succeeded && r.lastLines.lastOrNull() == "trimmed"
    } catch (e: IOException) {
        false
    }

    /** True when the files were put back as they were in [commit]. */
    suspend fun restore(projectDir: File, commit: String): Boolean = try {
        shell.run(GitSnapshots.restoreScript(commit), projectDir).succeeded
    } catch (e: IOException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}
