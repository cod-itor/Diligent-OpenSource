package com.vscodroid.diligent.projects

import java.io.File

/** What [ProjectsFolder.create] did. */
sealed interface CreateResult {
    data class Created(val folder: File) : CreateResult
    data class Refused(val problem: NameProblem) : CreateResult
    /** The name was fine and the folder still could not be made (no space, no permission). */
    data object Failed : CreateResult
}

/**
 * The projects folder (`~/projects`), where New project and Clone from Git
 * put what they make. Every function here touches the disk: call them off
 * the main thread.
 */
object ProjectsFolder {

    /**
     * Makes an empty folder called [input] in [parent]. The name is checked
     * again here, on the thread that makes the folder, because the check the
     * form showed ran a moment earlier and the Files tab or a terminal may
     * have made that folder since. [File.mkdir] then refuses a folder that
     * appeared in between, so an existing project is never adopted as new.
     */
    fun create(parent: File, input: String): CreateResult {
        ProjectNames.problemIn(parent, input)?.let { return CreateResult.Refused(it) }
        parent.mkdirs()
        val folder = File(parent, ProjectNames.clean(input))
        return if (folder.mkdir()) CreateResult.Created(folder) else CreateResult.Failed
    }

    /**
     * The folders directly in [parent], by name, ignoring case. Hidden ones
     * are left out: they are tools' folders (`.cache`, `.npm`), not projects.
     */
    fun list(parent: File): List<String> =
        parent.listFiles { file -> file.isDirectory && !file.name.startsWith('.') }
            .orEmpty()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { it.path }
}
