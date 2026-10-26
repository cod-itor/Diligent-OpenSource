package com.vscodroid.diligent.ui.screens

import com.vscodroid.diligent.AppView
import com.vscodroid.diligent.DeviceFolder
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.ui.navigation.MobileTab

/** [MobileActions] that do nothing, for `@Preview`s. */
internal object PreviewActions : MobileActions {
    override fun openTab(tab: MobileTab) = Unit
    override fun openFile(path: String) = Unit
    override fun openFolder(path: String) = Unit
    override fun openFolderPicker() = Unit
    override fun recentDeviceFolders(): List<DeviceFolder> = listOf(
        DeviceFolder("content://preview/notes", "notes", 0L),
    )
    override fun openDeviceFolder(folder: DeviceFolder) = Unit
    override fun switchView(view: AppView) = Unit
    override fun editorCommand(command: String, vararg args: Any?) = Unit
    override val projectsDir: String = "/home/projects"
}
