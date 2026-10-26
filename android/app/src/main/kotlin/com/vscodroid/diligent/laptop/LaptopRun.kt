package com.vscodroid.diligent.laptop

import com.vscodroid.diligent.process.BundledShell.Companion.quote
import com.vscodroid.diligent.run.RunConfig
import com.vscodroid.diligent.run.RunProfile
import com.vscodroid.diligent.run.RunSession

/** The sentences the scripts print, in the user's language, with the laptop's name already in them. */
data class LaptopMessages(
    val unreachable: String,
    val keyRefused: String,
    val hostKeyChanged: String,
    val noGit: String,
)

/**
 * The scripts that run a profile on [machine] for the project [project].
 *
 * **[sync]**, run on the phone before a start, in the project folder:
 * 1. reaches the laptop (and says why not: [LaptopMessages]) and makes the
 *    bare `~/diligent-sync/<project>.git` and the folder `~/diligent-work/<project>`;
 * 2. takes a snapshot with the same Git plumbing as the diligent-core
 *    extension (private index `.git/diligent-index`, ref
 *    `refs/diligent/snapshots`), making the folder a repository first if it
 *    is not one, with the same excludes;
 * 3. pushes `refs/diligent/snapshots` to the laptop's `refs/heads/diligent-sync`
 *    with `--force` and checks it out there with `-f`;
 * 4. runs the profile's `prepare` there when a lock file changed since the
 *    last time it succeeded (recorded in `<bare>/diligent-prepared`).
 *
 * **[command]** is what the tmux session runs (so the supervisor keeps it
 * alive like any run): `ssh -tt -L <localPort>:localhost:<port>` into the
 * work folder and the profile's command, through the laptop user's login
 * shell so its PATH (Homebrew, nvm) is the one their terminal has.
 *
 * Quoting: every value goes through [quote], once per shell it passes
 * (phone bash, the laptop's `sh -c`, the login shell). The project's folder
 * name on the laptop is cut to `[a-z0-9_-]` by [RunSession.part]. The
 * profile's command and prepare are shell text the user wrote, run as written.
 */
class LaptopRun(private val machine: Machine, project: String, private val messages: LaptopMessages) {

    /** The project's name on the laptop. */
    val remoteName: String = RunSession.part(project, "project")

    private val bare = "\"\$HOME\"/diligent-sync/${quote("$remoteName.git")}"
    private val work = "\"\$HOME\"/diligent-work/${quote(remoteName)}"

    /** [script] for the laptop's own `sh`, as one word for ssh. */
    private fun remote(script: String): String = quote("sh -c " + quote(script))

    private fun ssh(extra: String = ""): String = "ssh ${LaptopSsh.OPTIONS}$extra ${quote(machine.target)}"

    fun sync(projectDir: String, profile: RunProfile): String = buildList {
        add("cd ${quote(projectDir)} || exit 2")

        // 1. Reach the laptop and make its two folders.
        val setup = listOf(
            "command -v git >/dev/null 2>&1 || { printf '%s\\n' ${quote(messages.noGit)}; exit 3; }",
            "mkdir -p \"\$HOME\"/diligent-sync $work || exit 3",
            "[ -d $bare ] || git init -q --bare $bare || exit 3",
        ).joinToString("\n")
        add("diligent_out=\$(${ssh()} ${remote(setup)} 2>&1 </dev/null); diligent_code=\$?")
        add("if [ \"\$diligent_code\" -ne 0 ]; then")
        add("  [ -n \"\$diligent_out\" ] && printf '%s\\n' \"\$diligent_out\"")
        add("  if [ \"\$diligent_code\" = ${LaptopSsh.SSH_FAILED} ]; then")
        add("    case \"\$diligent_out\" in")
        add("      *'Permission denied'*) printf '%s\\n' ${quote(messages.keyRefused)} ;;")
        add("      *'Host key verification failed'*|*'IDENTIFICATION HAS CHANGED'*) printf '%s\\n' ${quote(messages.hostKeyChanged)} ;;")
        add("      *) printf '%s\\n' ${quote(messages.unreachable)} ;;")
        add("    esac")
        add("  fi")
        add("  exit \"\$diligent_code\"")
        add("fi")

        // 2. The snapshot, as diligent-core's snapshots.js takes it.
        add("if ! diligent_gd=\$(git rev-parse --absolute-git-dir 2>/dev/null); then")
        add("  git init -q || exit 3")
        add("  diligent_gd=\$(git rev-parse --absolute-git-dir) || exit 3")
        add("  mkdir -p \"\$diligent_gd/info\" && printf '%s\\n' ${FRESH_EXCLUDES.joinToString(" ") { quote(it) }} >> \"\$diligent_gd/info/exclude\"")
        add("fi")
        add("export GIT_AUTHOR_NAME=Diligent GIT_AUTHOR_EMAIL=diligent@localhost GIT_COMMITTER_NAME=Diligent GIT_COMMITTER_EMAIL=diligent@localhost")
        add("GIT_INDEX_FILE=\"\$diligent_gd/$INDEX_NAME\" git add -A || exit 3")
        add("diligent_tree=\$(GIT_INDEX_FILE=\"\$diligent_gd/$INDEX_NAME\" git write-tree) || exit 3")
        add("diligent_parent=\$(git rev-parse --verify -q '$REF^{commit}')")
        add("if [ -z \"\$diligent_parent\" ] || [ \"\$(git rev-parse \"\$diligent_parent^{tree}\")\" != \"\$diligent_tree\" ]; then")
        add("  diligent_commit=\$(git commit-tree \"\$diligent_tree\" \${diligent_parent:+-p \"\$diligent_parent\"} -m ${quote("diligent: " + reason(profile))}) || exit 3")
        add("  git update-ref $REF \"\$diligent_commit\" \"\$diligent_parent\" || exit 3")
        add("fi")

        // 3. Push it and check it out there.
        add(
            "GIT_SSH_COMMAND=${quote("ssh " + LaptopSsh.OPTIONS)} git push -q --force " +
                "${quote("${machine.target}:diligent-sync/$remoteName.git")} $REF:refs/heads/$BRANCH || exit 4",
        )
        val checkout = buildList {
            add("git --git-dir=$bare --work-tree=$work checkout -q -f $BRANCH || exit 5")
            // 4. Prepare when a lock file changed since it last succeeded.
            if (profile.prepare != null) {
                val mark = "$bare/diligent-prepared"
                add("if [ ! -f $mark ] || ! git --git-dir=$bare diff --quiet \"\$(cat $mark)\" $BRANCH -- ${LOCK_FILES.joinToString(" ")} 2>/dev/null; then")
                add("  cd $work || exit 5")
                add("  \"\${SHELL:-/bin/sh}\" -lc ${quote(profile.prepare)} || exit 6")
                add("  git --git-dir=$bare rev-parse $BRANCH > $mark")
                add("fi")
            }
        }.joinToString("\n")
        add("${ssh()} ${remote(checkout)} </dev/null")
    }.joinToString("\n")

    /**
     * What the tmux session runs: the profile's command on the laptop, its
     * port forwarded to [localPort] on the phone. ssh's own failure (255)
     * leaves the unreachable sentence as the log's last line before the exit
     * marker.
     */
    fun command(profile: RunProfile, localPort: Int?): String {
        val inner = buildList {
            add("cd $work || exit 2")
            profile.env.forEach { (k, v) ->
                require(RunConfig.isEnvName(k)) { "not an environment variable name: $k" }
                add("export $k=${quote(v)}")
            }
            add(profile.command)
        }.joinToString("\n")
        val forward = if (profile.port != null && localPort != null) " -L $localPort:localhost:${profile.port}" else ""
        val run = "cd $work && exec \"\${SHELL:-/bin/sh}\" -lc ${quote(inner)}"
        return listOf(
            "${ssh(" -tt -o ExitOnForwardFailure=yes -o ServerAliveInterval=15$forward")} ${remote(run)}",
            "diligent_ssh=\$?",
            "[ \"\$diligent_ssh\" = ${LaptopSsh.SSH_FAILED} ] && printf '%s\\n' ${quote(messages.unreachable)}",
            "exit \"\$diligent_ssh\"",
        ).joinToString("\n")
    }

    private fun reason(profile: RunProfile) = ("run ${profile.name} on ${machine.name}").replace(Regex("\\s+"), " ").take(200)

    companion object {
        const val BRANCH = "diligent-sync"
        private const val REF = "refs/diligent/snapshots"
        private const val INDEX_NAME = "diligent-index"

        /** As diligent-core's extension.js writes for a repository it makes. */
        private val FRESH_EXCLUDES = listOf("node_modules/", ".venv/", "venv/", "__pycache__/", ".next/", "dist/", "build/")

        /** A change to any of these runs `prepare` again. */
        val LOCK_FILES = listOf(
            "package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb", "bun.lock",
            "requirements.txt", "poetry.lock", "Pipfile.lock", "uv.lock", "Gemfile.lock", "go.sum", "Cargo.lock",
        )
    }
}
