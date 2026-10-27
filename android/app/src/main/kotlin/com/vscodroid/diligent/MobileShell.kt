package com.vscodroid.diligent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vscodroid.diligent.devices.DeviceLink
import com.vscodroid.diligent.devices.IdleDeviceLink
import com.vscodroid.diligent.guide.NextStep
import com.vscodroid.diligent.kit.KitSession
import com.vscodroid.diligent.run.RunControl
import com.vscodroid.diligent.ui.components.TopBarStatus
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.navigation.TabHistory
import java.io.File

/**
 * Which of Diligent's pieces are on screen around the editor.
 *
 * Decided in one pure function, [chromeFor], so every combination is tested
 * without a device and the activity only applies the answer.
 */
data class Chrome(
    /** Project name, run status and menu, above everything. */
    val topBar: Boolean,
    /** A native screen (Projects, Files, More) covering the editor. */
    val nativeScreen: Boolean,
    /** Run, Preview, Save, Search, Command palette, Undo (the snapshot list), under the editor. */
    val actionBar: Boolean,
    /** The five tabs, at the bottom. */
    val tabBar: Boolean,
    /**
     * The top strip is the file name chip instead of the project bar: on the
     * Editor tab, where nothing but the file's name sits above the code.
     */
    val fileChip: Boolean = false,
)

/**
 * The rules:
 * - Desktop view, or no view chosen yet: none of it. The screen is VSCodroid's.
 * - Mobile view: top bar and tab bar, the action bar on the Editor tab only, and
 *   a native screen over the editor on the tabs that are not the editor. On the
 *   Editor tab the top bar is only the file name chip.
 * - While the soft keyboard is up, the bars step aside so the editor keeps the
 *   height (the extra key row takes their place). A native screen stays, since
 *   the keyboard is then typing into it.
 */
fun chromeFor(view: AppView?, tab: MobileTab, imeVisible: Boolean): Chrome {
    if (view != AppView.Mobile) return Chrome(topBar = false, nativeScreen = false, actionBar = false, tabBar = false)
    val bars = !imeVisible
    return Chrome(
        topBar = bars,
        nativeScreen = !tab.showsEditor,
        actionBar = bars && tab == MobileTab.Editor,
        tabBar = bars,
        fileChip = bars && tab == MobileTab.Editor,
    )
}

/**
 * Mobile view's state, as Compose state, so the bars and screens redraw when
 * it changes. Owned by [DiligentController]; the screens read it and act
 * through [MobileActions].
 */
class MobileShell(view: AppView?) {
    private val history = TabHistory(MobileTab.Projects)

    var view by mutableStateOf(view)
    var tab by mutableStateOf(history.current)
        private set
    var imeVisible by mutableStateOf(false)

    /** The folder open in the editor, as an absolute path, or null when none is. */
    var folder by mutableStateOf<String?>(null)

    /** What the top bar says about the running task, or null when nothing runs. */
    var runStatus by mutableStateOf<TopBarStatus?>(null)

    /** The file in front in the editor, for the file name chip, or null when none is. */
    var activeFile by mutableStateOf<ActiveFile?>(null)

    /** Set by the file name chip: the all-files switcher opens. */
    var fileSwitcherOpen by mutableStateOf(false)

    /** Whether a task of the open project is running (the action bar shows Stop). */
    var running by mutableStateOf(false)

    /** The port of the running task's preview, or null when there is none to show. */
    var previewPort by mutableStateOf<Int?>(null)

    /**
     * Set by the action bar's Run when the project has no run profile yet:
     * the More tab opens its Run screen directly and clears this.
     */
    var runRequested by mutableStateOf(false)

    /**
     * Set when the notification after a killed task is tapped: the More tab
     * opens its Health screen directly and clears this.
     */
    var healthRequested by mutableStateOf(false)

    /**
     * Set by the top bar's Share and a tap on the "Sharing on" notification:
     * the More tab opens its Share screen directly and clears this.
     */
    var shareRequested by mutableStateOf(false)

    /** Set to open the More tab on its Devices screen; the More tab clears it. */
    var devicesRequested by mutableStateOf(false)

    /** The top bar's Sync needs the user to pick a device (or learn there is none); the sheet clears it. */
    var syncPickerOpen by mutableStateOf(false)

    /**
     * Set to open the guided start over the Projects tab (after the first
     * view choice); the Projects tab clears it.
     */
    var guidedStartRequested by mutableStateOf(false)

    /** The "Next step" card a guided start leaves above the bars, or null when there is none. */
    var nextStep by mutableStateOf<NextStep?>(null)

    /** Settings: whether a big screen may suggest Desktop view. */
    var suggestDesktop by mutableStateOf(true)

    val chrome: Chrome get() = chromeFor(view, tab, imeVisible)

    /** The open project's name: the folder's last segment, without `.code-workspace`. */
    val projectName: String?
        get() = folder?.let { File(it).name.removeSuffix(".code-workspace") }?.takeIf { it.isNotEmpty() }

    fun open(target: MobileTab) {
        history.open(target)
        tab = history.current
    }

    /** Goes back one tab and answers true, or answers false when there is nowhere to go. */
    fun back(): Boolean {
        if (!history.back()) return false
        tab = history.current
        return true
    }
}

/** What a Mobile view screen can ask for. Implemented by [DiligentController]. */
interface MobileActions {
    fun openTab(tab: MobileTab)

    /** Opens [path] in the editor and shows the Editor tab. */
    fun openFile(path: String)

    /** Opens a project folder in the editor and shows the Files tab. */
    fun openFolder(path: String)

    fun openFolderPicker()
    fun recentDeviceFolders(): List<DeviceFolder>
    fun openDeviceFolder(folder: DeviceFolder)

    /** Switches between Mobile and Desktop view, without reloading the editor. */
    fun switchView(view: AppView)

    /** Runs an allowlisted editor command (see the diligent-bridge extension). */
    fun editorCommand(command: String, vararg args: Any?)

    /** Settings: whether a big screen may suggest Desktop view (stored per device). */
    fun setSuggestDesktop(on: Boolean)

    /** The folder new projects and clones go in. */
    val projectsDir: String

    /** Run profiles of the open project: start, stop, watch, preview. */
    val run: RunControl

    /** The Setup screen's run: a recipe through the kit engine. */
    val kit: KitSession

    /** The editor server's loopback port, for the Share screen, or null while it is not loaded. */
    fun editorPort(): Int? = null

    /** diligent-link: devices, pairing and sync, for the Devices screen and the Sync button. */
    val devices: DeviceLink get() = IdleDeviceLink
}
