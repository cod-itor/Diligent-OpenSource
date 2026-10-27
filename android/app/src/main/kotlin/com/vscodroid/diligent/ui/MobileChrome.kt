package com.vscodroid.diligent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.AppView
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.run.WatchRun
import com.vscodroid.diligent.ui.components.DiligentTabBar
import com.vscodroid.diligent.ui.components.DiligentTopBar
import com.vscodroid.diligent.ui.components.EditorActionBar
import com.vscodroid.diligent.ui.components.MenuAction
import com.vscodroid.diligent.ui.components.standardEditorActions
import com.vscodroid.diligent.ui.components.suggestionActions
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.screens.FilesMode
import com.vscodroid.diligent.ui.screens.FilesScreen
import com.vscodroid.diligent.ui.screens.MoreScreen
import com.vscodroid.diligent.ui.screens.PreviewActions
import com.vscodroid.diligent.ui.screens.ProjectsScreen
import com.vscodroid.diligent.ui.theme.DiligentTheme

/*
 * The three places Mobile view draws around the editor's WebView. Each is
 * its own ComposeView in the activity's layout (see DiligentController), so
 * the WebView itself is never moved, resized by a native screen, or reloaded.
 * Each draws nothing when MobileShell.chrome says it is off, which leaves it
 * zero height in the column and Desktop view exactly as VSCodroid lays it out.
 */

/** Above the editor: project name, run status and the menu. */
@Composable
fun TopChrome(shell: MobileShell, actions: MobileActions) {
    if (!shell.chrome.topBar) return
    DiligentTopBar(
        projectName = shell.projectName,
        status = shell.runStatus,
        menu = listOf(
            MenuAction(stringResource(R.string.diligent_menu_desktop_view), Icons.Outlined.DesktopWindows) {
                actions.switchView(AppView.Desktop)
            },
            MenuAction(stringResource(R.string.diligent_menu_share), Icons.Outlined.Share) {
                shell.shareRequested = true
                actions.openTab(MobileTab.More)
            },
            MenuAction(stringResource(R.string.diligent_menu_settings), Icons.Outlined.Settings) { actions.openTab(MobileTab.More) },
        ),
    )
}

/** Over the editor, on the tabs that are not the editor. */
@Composable
fun NativeScreens(shell: MobileShell, actions: MobileActions) {
    // Held here rather than in the Files tab, which leaves composition when
    // another tab is in front: Folders stays chosen across a trip to the editor.
    var filesMode by rememberSaveable { mutableStateOf(FilesMode.AllFiles) }
    if (!shell.chrome.nativeScreen) return
    when (shell.tab) {
        MobileTab.Projects -> ProjectsScreen(shell, actions)
        MobileTab.Files -> FilesScreen(shell, actions, mode = filesMode, onMode = { filesMode = it })
        MobileTab.More -> MoreScreen(shell, actions)
        MobileTab.Editor, MobileTab.Terminal -> Unit
    }
}

/** Under the editor: the action bar on the Editor tab, then the tab bar. */
@Composable
fun BottomChrome(shell: MobileShell, actions: MobileActions) {
    val chrome = shell.chrome
    if (!chrome.actionBar && !chrome.tabBar) return
    Column {
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

@Preview(name = "Chrome on Files, dark", widthDp = 360)
@Composable
private fun ChromeFilesDarkPreview() = DiligentTheme(darkTheme = true) { ShellSample(MobileTab.Files) }

/**
 * Opens the terminal showing one run's output (the bridge extension's
 * `diligent.mobile.showRun`, which accepts a run's session name only).
 */
private const val COMMAND_SHOW_RUN = "diligent.mobile.showRun"
