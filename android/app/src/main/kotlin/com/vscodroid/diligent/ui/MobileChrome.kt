package com.vscodroid.diligent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vscodroid.R
import com.vscodroid.diligent.ActiveFile
import com.vscodroid.diligent.AppView
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.run.WatchRun
import com.vscodroid.diligent.ui.components.DiligentTabBar
import com.vscodroid.diligent.ui.components.DiligentTopBar
import com.vscodroid.diligent.ui.components.EditorActionBar
import com.vscodroid.diligent.ui.components.FileNameChipBar
import com.vscodroid.diligent.ui.components.MenuAction
import com.vscodroid.diligent.ui.components.NextStepCard
import com.vscodroid.diligent.ui.components.standardEditorActions
import com.vscodroid.diligent.ui.components.suggestionActions
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.screens.FileSwitcherSheet
import com.vscodroid.diligent.ui.screens.FilesMode
import com.vscodroid.diligent.ui.screens.FilesScreen
import com.vscodroid.diligent.ui.screens.IncomingProjectDialog
import com.vscodroid.diligent.ui.screens.MoreScreen
import com.vscodroid.diligent.ui.screens.PairingSheet
import com.vscodroid.diligent.ui.screens.PreviewActions
import com.vscodroid.diligent.ui.screens.ProjectsScreen
import com.vscodroid.diligent.ui.screens.SyncPickerSheet
import com.vscodroid.diligent.ui.screens.SyncSheet
import com.vscodroid.diligent.ui.screens.syncTargets
import com.vscodroid.diligent.ui.theme.DiligentTheme

/*
 * The three places Mobile view draws around the editor's WebView. Each is
 * its own ComposeView in the activity's layout (see DiligentController), so
 * the WebView itself is never moved, resized by a native screen, or reloaded.
 * Each draws nothing when MobileShell.chrome says it is off, which leaves it
 * zero height in the column and Desktop view exactly as VSCodroid lays it out.
 */

/**
 * Above the editor: project name, run status and the menu; on the Editor tab
 * only the file name chip, whose long press holds the same menu.
 */
@Composable
fun TopChrome(shell: MobileShell, actions: MobileActions) {
    val chrome = shell.chrome
    if (!chrome.topBar) return
    val menu = listOf(
        MenuAction(stringResource(R.string.diligent_menu_desktop_view), Icons.Outlined.DesktopWindows) {
            actions.switchView(AppView.Desktop)
        },
        MenuAction(stringResource(R.string.diligent_menu_sync), Icons.Outlined.Sync) {
            // One reachable device: straight to its plan. Otherwise pick one, or learn why there is none.
            val folder = shell.folder
            val targets = syncTargets(actions.devices.state.value)
            if (folder != null && targets.size == 1) {
                actions.devices.planSync(targets.single().deviceId, folder)
            } else {
                shell.syncPickerOpen = true
            }
        },
        MenuAction(stringResource(R.string.diligent_menu_share), Icons.Outlined.Share) {
            shell.shareRequested = true
            actions.openTab(MobileTab.More)
        },
        MenuAction(stringResource(R.string.diligent_menu_settings), Icons.Outlined.Settings) { actions.openTab(MobileTab.More) },
    )
    if (chrome.fileChip) {
        FileNameChipBar(file = shell.activeFile, onOpenSwitcher = { shell.fileSwitcherOpen = true }, menu = menu)
    } else {
        DiligentTopBar(projectName = shell.projectName, status = shell.runStatus, menu = menu)
    }
}

/**
 * Over the editor, on the tabs that are not the editor. Also what comes on
 * any tab: the file switcher and diligent-link's prompts. Those are sheets and
 * dialogs in their own windows, so they show even while this layer is GONE
 * over the Editor tab.
 */
@Composable
fun NativeScreens(shell: MobileShell, actions: MobileActions) {
    // Held here rather than in the Files tab, which leaves composition when
    // another tab is in front: Folders stays chosen across a trip to the editor.
    var filesMode by rememberSaveable { mutableStateOf(FilesMode.AllFiles) }
    if (shell.view == AppView.Mobile && shell.fileSwitcherOpen) FileSwitcherSheet(shell, actions)
    if (shell.view == AppView.Mobile) DevicePrompts(shell, actions)
    if (!shell.chrome.nativeScreen) return
    when (shell.tab) {
        MobileTab.Projects -> ProjectsScreen(shell, actions)
        MobileTab.Files -> FilesScreen(shell, actions, mode = filesMode, onMode = { filesMode = it })
        MobileTab.More -> MoreScreen(shell, actions)
        MobileTab.Editor, MobileTab.Terminal -> Unit
    }
}

/** What diligent-link asks of the user wherever they are in Mobile view. */
@Composable
private fun DevicePrompts(shell: MobileShell, actions: MobileActions) {
    val link = actions.devices
    val state by link.state.collectAsStateWithLifecycle()
    state.pairing?.let { PairingSheet(it, link) }
    state.sync?.let { SyncSheet(it, link, onOpenTerminal = { actions.openTab(MobileTab.Terminal) }) }
    if (shell.syncPickerOpen) {
        SyncPickerSheet(
            state = state,
            projectPath = shell.folder,
            onPick = { device -> shell.folder?.let { link.planSync(device.deviceId, it) } },
            onOpenDevices = {
                shell.devicesRequested = true
                actions.openTab(MobileTab.More)
            },
            onDismiss = { shell.syncPickerOpen = false },
        )
    }
    state.incomingProject?.let { incoming ->
        IncomingProjectDialog(incoming, actions.projectsDir, onAnswer = link::answerIncomingProject)
    }
}

/** Under the editor: the action bar on the Editor tab, then the tab bar. */
@Composable
fun BottomChrome(shell: MobileShell, actions: MobileActions) {
    val chrome = shell.chrome
    // A guided start's card has done its job once the project runs.
    LaunchedEffect(shell.running) { if (shell.running) shell.nextStep = null }
    if (!chrome.actionBar && !chrome.tabBar) return
    Column {
        shell.nextStep?.let { NextStepCard(it, onDismiss = { shell.nextStep = null }) }
        if (chrome.actionBar) {
            WatchRun(actions.run, withLog = false)
            EditorActionBar(
                standardEditorActions(
                    running = shell.running,
                    canPreview = shell.previewPort != null,
                    // Runs the default profile and shows its output on the
                    // Terminal tab; with none yet, opens Run, which offers the
                    // detected one or a form.
                    onRun = {
                        if (actions.run.state.activeProfile != null) {
                            // The output shows where a program's output belongs:
                            // in a terminal of its own, on the Terminal tab.
                            actions.run.showInTerminal { session, label ->
                                actions.openTab(MobileTab.Terminal)
                                actions.editorCommand(COMMAND_SHOW_RUN, session, label)
                            }
                        } else {
                            shell.runRequested = true
                            actions.openTab(MobileTab.More)
                        }
                    },
                    onStop = { actions.run.stop() },
                    onPreview = { actions.run.openPreview() },
                    onSave = { actions.editorCommand("workbench.action.files.save") },
                    // Find in this file, what a phone user expects from Search;
                    // searching the project opens a side bar Mobile view hides.
                    onSearch = { actions.editorCommand("actions.find") },
                    onCommands = { actions.editorCommand("workbench.action.showCommands") },
                    // The snapshot list (diligent-core): every saved version of
                    // the project by time and reason, so a beginner who broke
                    // something can go back without knowing Git. Still labelled
                    // Undo, the word a beginner looks for at that moment.
                    onUndo = { actions.editorCommand("diligent.snapshot.showList") },
                ),
                // AI suggestions (diligent-ai): take the one showing, or show
                // the next. The page does not report whether one shows, so the
                // pair is always there, small, and does nothing without one.
                compact = suggestionActions(
                    onAccept = { actions.editorCommand("editor.action.inlineSuggest.commit") },
                    onNext = { actions.editorCommand("editor.action.inlineSuggest.showNext") },
                ),
            )
        }
        if (chrome.tabBar) DiligentTabBar(current = shell.tab, onSelect = actions::openTab)
    }
}

@Composable
private fun ShellSample(tab: MobileTab) {
    val shell = MobileShell(AppView.Mobile).apply {
        folder = "/home/projects/hello-next"
        activeFile = ActiveFile("/home/projects/hello-next/app/page.tsx", "page.tsx", dirty = true)
        open(tab)
    }
    Column(Modifier) {
        TopChrome(shell, PreviewActions)
        BottomChrome(shell, PreviewActions)
    }
}

@Preview(name = "Chrome on Editor, light", widthDp = 360)
@Composable
private fun ChromeEditorLightPreview() = DiligentTheme(darkTheme = false) { ShellSample(MobileTab.Editor) }

@Preview(name = "Chrome on Editor, dark", widthDp = 360)
@Composable
private fun ChromeEditorDarkPreview() = DiligentTheme(darkTheme = true) { ShellSample(MobileTab.Editor) }

@Preview(name = "Chrome on Files, dark", widthDp = 360)
@Composable
private fun ChromeFilesDarkPreview() = DiligentTheme(darkTheme = true) { ShellSample(MobileTab.Files) }

/**
 * Opens the terminal showing one run's output (the bridge extension's
 * `diligent.mobile.showRun`, which accepts a run's session name only).
 */
private const val COMMAND_SHOW_RUN = "diligent.mobile.showRun"
