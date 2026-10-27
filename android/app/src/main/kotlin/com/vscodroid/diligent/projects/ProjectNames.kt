package com.vscodroid.diligent.projects

import java.io.File

/** Why a name cannot be a new project folder. */
enum class NameProblem { Empty, Slash, Reserved, TooLong, Exists }

/**
 * The rules for a new project's folder name, shared by New project and
 * Clone from Git.
 *
 * Kept to what would actually go wrong: a slash would make a folder somewhere
 * else, `.` and `..` are not new folders, and the file system stops at 255
 * bytes. Spaces and capitals are fine; a beginner's "My site" works, and the
 * terminal quotes it.
 */
object ProjectNames {
    /** The longest name, in UTF-8 bytes, that ext4 and f2fs accept. */
    const val MAX_BYTES = 255

    /** What the folder is called: [input] without the spaces a keyboard leaves around it. */
    fun clean(input: String): String = input.trim()

    /** The problem with [input] as a folder in a parent where [taken] answers which names exist, or null. */
    fun problem(input: String, taken: (String) -> Boolean): NameProblem? {
        val name = clean(input)
        return when {
            name.isEmpty() -> NameProblem.Empty
            '/' in name || '\u0000' in name -> NameProblem.Slash
            name == "." || name == ".." -> NameProblem.Reserved
            name.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> NameProblem.TooLong
            taken(name) -> NameProblem.Exists
            else -> null
        }
    }

    /**
     * [base] when no folder has that name, else the first free `base-2`,
     * `base-3`, and so on. For names Diligent picks itself (the guided start's
     * suggestion, an imported zip), where asking the user would be a chore.
     */
    fun free(base: String, taken: (String) -> Boolean): String {
        val name = clean(base).ifEmpty { FALLBACK_NAME }
        if (!taken(name)) return name
        var n = 2
        while (taken("$name-$n")) n++
        return "$name-$n"
    }

    /** What [free] calls a project whose own name is empty. */
    const val FALLBACK_NAME = "project"

    /** [problem] against a real folder. Touches the disk: call it off the main thread. */
    fun problemIn(parent: File, input: String): NameProblem? = problem(input) { File(parent, it).exists() }
}
