package com.vscodroid.diligent

/**
 * What Diligent's screens may ask of the editor activity, and nothing more.
 *
 * `MainActivity` implements it with a few lines that call its own private
 * methods, so Diligent never reaches into VSCodroid's activity directly and
 * the activity's internals can change under a VSCodroid update without
 * Diligent's screens noticing. Every call is on the main thread.
 */
interface DiligentActivityHost {

    /** The folder new projects and clones go in (`~/projects`). */
    val projectsDir: String

    /**
     * Opens [path] (a folder, or a `.code-workspace` file) in the editor. The
     * editor page reloads for it, as VS Code's own Open Folder does; open files
     * of the previous folder close with it. Answers false, after telling the
     * user, when the editor is not running yet and nothing was opened.
     */
    fun openFolder(path: String): Boolean

    /** Opens the system folder picker, VSCodroid's route to folders on shared storage. */
    fun openFolderPicker()

    /** Folders picked earlier through the system picker, newest first. */
    fun recentDeviceFolders(): List<DeviceFolder>

    /** Opens a folder from [recentDeviceFolders]. */
    fun openDeviceFolder(folder: DeviceFolder)
}

/** A folder on shared storage the user picked through the system picker. */
data class DeviceFolder(val uri: String, val name: String, val lastOpened: Long)
