package com.vscodroid.diligent.ui.screens

import com.vscodroid.diligent.AppView
import com.vscodroid.diligent.DeviceFolder
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.kit.KitSession
import com.vscodroid.diligent.kit.PackInstallQueue
import com.vscodroid.diligent.kit.PackInstaller
import com.vscodroid.setup.ToolchainFailure
import com.vscodroid.diligent.run.RunControl
import com.vscodroid.diligent.run.RunProfile
import com.vscodroid.diligent.run.RunUiState
import com.vscodroid.diligent.ui.navigation.MobileTab
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext

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
    override fun setSuggestDesktop(on: Boolean) = Unit
    override fun editorCommand(command: String, vararg args: Any?) = Unit
    override val projectsDir: String = "/home/projects"
    override val run: RunControl = PreviewRun(RunUiState())
    override val kit: KitSession = KitSession(CoroutineScope(EmptyCoroutineContext), send = { _, _ -> })
    override val packs: PackInstallQueue = PackInstallQueue(PreviewInstaller, post = { it() })
}

/** A [PackInstaller] that installs nothing, for `@Preview`s. */
internal object PreviewInstaller : PackInstaller {
    override fun install(pack: String) = Unit
    override fun cancel(pack: String) = Unit
    override fun listen(onState: ((String, Int, Int, ToolchainFailure?) -> Unit)?) = Unit
    override fun heldElsewhere(pack: String) = false
    override fun confirm() = Unit
    override fun installed(): Set<String> = emptySet()
}

/** [PreviewActions] with the Run screen in [state]. */
internal class PreviewRunActions(state: RunUiState) : MobileActions by PreviewActions {
    override val run: RunControl = PreviewRun(state)
}

/** A [RunControl] frozen in [state], for `@Preview`s. */
internal class PreviewRun(override val state: RunUiState) : RunControl {
    override suspend fun watch(withLog: Boolean) = kotlinx.coroutines.awaitCancellation()
    override fun select(profile: String) = Unit
    override fun start() = Unit
    override fun stop() = Unit
    override fun save(profile: RunProfile) = Unit
    override fun dismissWebpackNote() = Unit
    override fun runOnLaptop(name: String) = Unit
    override fun dismissOffload() = Unit
    override fun openPreview() = Unit
    override fun openConfig() = Unit
}
