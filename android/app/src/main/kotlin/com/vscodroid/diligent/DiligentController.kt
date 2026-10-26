package com.vscodroid.diligent

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.vscodroid.R
import com.vscodroid.diligent.projects.RecentProjects
import com.vscodroid.diligent.preview.PreviewActivity
import com.vscodroid.diligent.run.ProjectRunner
import com.vscodroid.diligent.run.RunControl
import com.vscodroid.diligent.run.RunNotes
import com.vscodroid.diligent.ui.BottomChrome
import com.vscodroid.diligent.ui.NativeScreens
import com.vscodroid.diligent.ui.TopChrome
import com.vscodroid.diligent.ui.components.ViewSuggestionCard
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.screens.ChooseViewScreen
import com.vscodroid.diligent.ui.theme.DiligentTheme
import kotlinx.coroutines.launch

/**
 * Diligent's layer on top of VSCodroid's editor activity.
 *
 * `MainActivity` is VSCodroid's, and every line added there is a line that can
 * conflict when VSCodroid's updates are merged. So the activity holds one of
 * these and calls into it from a handful of places, and what Diligent does
 * lives here and in the files next to it.
 *
 * **Layout.** `activity_main.xml` is a column: the editor frame (the WebView,
 * weight 1) and the extra key row. This adds three Compose views to it: the top
 * bar above the frame, a native screen layer inside the frame over the WebView,
 * and the action and tab bars at the bottom. None of them moves the WebView or
 * reloads it, which is what lets a view switch keep open files, unsaved text
 * and running terminals. In Desktop view all three are gone and the column is
 * exactly VSCodroid's.
 *
 * [webView] is a supplier rather than a view because the activity replaces its
 * WebView after a renderer crash.
 */
class DiligentController(
    private val activity: ComponentActivity,
    private val host: DiligentActivityHost,
    private val webView: () -> WebView?,
) {
    /**
     * This device's view, Mobile or Desktop. Read on first use, which is the
     * first layout: one small preferences file of Diligent's own.
     */
    val views by lazy {
        ViewSettings(activity.getSharedPreferences(ViewSettings.PREFS_NAME, Context.MODE_PRIVATE))
    }

    /** Kotlin to editor: runs allowlisted editor commands. */
    val editor = EditorChannel { script, result ->
        val view = webView()
        if (view == null) result(null) else view.evaluateJavascript(script) { result(it) }
    }

    /** Projects the editor opened, for the Projects tab. Shared with the tab through [RecentProjects.of]. */
    private val recentProjects by lazy { RecentProjects.of(activity) }

    /** Mobile view's state; the bars and screens draw from it. */
    val shell by lazy {
        MobileShell(views.view.value).apply { suggestDesktop = views.suggestDesktopOnBigScreens }
    }

    // -- Run (diligent/run) --
    /** Runs the open project's profiles in tmux and keeps the shell's run state current. */
    val runner by lazy {
        ProjectRunner(
            context = activity,
            scope = activity.lifecycleScope,
            shell = shell,
            notes = RunNotes(activity.getSharedPreferences(ViewSettings.PREFS_NAME, Context.MODE_PRIVATE)),
            openFile = { actions.openFile(it) },
            openPreviewOn = { port, title -> activity.startActivity(PreviewActivity.intent(activity, port, title)) },
        )
    }
    // -- end Run --

    private var topBar: View? = null
    private var nativeScreens: View? = null
    private var bottomBars: View? = null

    /**
     * Called once the activity's layout is set. Adds Mobile view's pieces
     * around the editor, and on a device where no view has been chosen, asks
     * "How do you want to code?" over the editor, which keeps starting
     * underneath, so the editor is ready by the time the user picks.
     */
    fun onLayoutReady() {
        attachChrome()
        attachSuggestion()
        if (!views.hasChosen) showViewChooser()
        // After the first layout, when the window has its real size.
        activity.window.decorView.post { checkScreen() }
    }

    /** The window changed: size, density, display or DeX. May suggest a view. */
    fun onConfigurationChanged() = checkScreen()

    private val advisor = ViewAdvisor()
    private var suggestion by mutableStateOf<ViewSuggestion?>(null)

    private fun checkScreen() {
        if (activity.isFinishing) return
        advisor.onScreen(ViewAdvisor.measure(activity), shell.view, views.suggestDesktopOnBigScreens)
            ?.let { suggestion = it }
    }

    /**
     * The suggestion card, over everything at the bottom of the window, in
     * both views (Desktop view has no Diligent chrome to put it in).
     */
    private fun attachSuggestion() {
        val card = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DiligentTheme {
                    val shown = suggestion ?: return@DiligentTheme
                    ViewSuggestionCard(
                        suggestion = shown,
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        ),
                        onSwitch = {
                            advisor.answered(shown, accepted = true)
                            suggestion = null
                            switchView(if (shown == ViewSuggestion.SwitchToDesktop) AppView.Desktop else AppView.Mobile)
                        },
                        onNotNow = {
                            advisor.answered(shown, accepted = false)
                            suggestion = null
                        },
                    )
                }
            }
        }
        activity.addContentView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
    }

    private fun attachChrome() {
        val column = activity.findViewById<LinearLayout>(R.id.webViewContainer)
        val frame = activity.findViewById<FrameLayout>(R.id.editorFrame)
        val wrap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        topBar = composeView { TopChrome(shell, actions) }.also { column.addView(it, 0, wrap) }
        nativeScreens = composeView { NativeScreens(shell, actions) }.also {
            frame.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // Last, so it sits under the extra key row: the row belongs to the
        // keyboard, and the bars are hidden while the keyboard is up anyway.
        bottomBars = composeView { BottomChrome(shell, actions) }.also { column.addView(it, wrap) }

        // The views themselves go GONE when their part is off, rather than only
        // drawing nothing: an empty native screen layer would still take
        // accessibility focus over the editor.
        activity.lifecycleScope.launch {
            snapshotFlow { shell.chrome }.collect(::applyChrome)
        }
    }

    /**
     * Puts the views in line with [chrome]. Idempotent, so it is also called
     * straight from [onImeVisibilityChanged]: through the snapshot flow alone
     * the bars would go one frame after the keyboard came up, and the editor
     * would be resized twice.
     */
    private fun applyChrome(chrome: Chrome) {
        topBar?.visibility = visibleIf(chrome.topBar)
        bottomBars?.visibility = visibleIf(chrome.actionBar || chrome.tabBar)
        val layer = nativeScreens ?: return
        val wasShown = layer.isVisible
        if (chrome.nativeScreen == wasShown) return
        layer.visibility = visibleIf(chrome.nativeScreen)
        val view = webView() ?: return
        if (chrome.nativeScreen) {
            leaveEditor(view)
            // Under a native screen the editor is not on screen, so TalkBack
            // must not walk into it.
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            // Back on the editor: a hardware keyboard types into it again
            // without a tap first. The editor's own focus is the tab command's.
            view.requestFocus()
        }
    }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) =
        ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DiligentTheme { content() } }
            visibility = View.GONE
        }

    private fun visibleIf(shown: Boolean) = if (shown) View.VISIBLE else View.GONE

    /** A native screen is coming up over the editor: the keyboard must not keep typing into it. */
    private fun leaveEditor(view: WebView) {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun showViewChooser() {
        // The editor starts underneath, and TalkBack must not reach it until
        // the user has chosen.
        val behind = activity.findViewById<View>(R.id.webViewContainer)
        behind.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val chooser = ComposeView(activity)
        chooser.setContent {
            DiligentTheme {
                // There is nothing behind this screen to go back to yet:
                // Back leaves the app the way Home does, and asks again next time.
                BackHandler { activity.moveTaskToBack(true) }
                ChooseViewScreen(onChoose = { view ->
                    switchView(view)
                    behind.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    (chooser.parent as? ViewGroup)?.removeView(chooser)
                })
            }
        }
        activity.addContentView(
            chooser,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /**
     * Shows [view]: stores it, swaps the native pieces around the editor, and
     * asks the diligent-bridge extension for that view's editor layout. Nothing
     * reloads.
     */
    fun switchView(view: AppView) {
        views.choose(view)
        shell.view = view
        editor.run(COMMAND_APPLY_VIEW, view.key)
        if (view == AppView.Mobile) showTab(shell.tab)
    }

    private fun showTab(tab: MobileTab) {
        when (tab) {
            MobileTab.Editor -> editor.run(COMMAND_SHOW_EDITOR)
            MobileTab.Terminal -> editor.run(COMMAND_SHOW_TERMINAL)
            else -> Unit
        }
    }

    /** The soft keyboard came up or went down (from the extra key row's inset listener). */
    fun onImeVisibilityChanged(visible: Boolean) {
        shell.imeVisible = visible
        applyChrome(shell.chrome)
    }

    /** The editor is opening [folder] (null: no folder). */
    fun onWorkspaceFolder(folder: String?) {
        shell.folder = folder
        recentProjects.opened(folder)
        runner.setProject(folder) // Run: load the new project's profiles
    }

    /**
     * Back, before the activity's own handling. In Mobile view on a native tab
     * it returns to the previous tab and answers true. Everywhere else it
     * answers false and Back does what it always did.
     */
    fun onBack(): Boolean {
        if (shell.view != AppView.Mobile || shell.tab.showsEditor) return false
        if (!shell.back()) return false
        showTab(shell.tab)
        return true
    }

    /** What the screens call. */
    val actions: MobileActions = object : MobileActions {
        override fun openTab(tab: MobileTab) {
            // Tapping the Terminal tab again would close and reopen the panel.
            // Editor again is worth it: it clears a side bar a command opened.
            if (tab == shell.tab && tab == MobileTab.Terminal) return
            shell.open(tab)
            showTab(tab)
        }

        override fun openFile(path: String) {
            editor.run("vscode.open", EditorPath(path))
            openTab(MobileTab.Editor)
        }

        override fun openFolder(path: String) {
            if (host.openFolder(path)) openTab(MobileTab.Files)
        }

        override fun openFolderPicker() = host.openFolderPicker()
        override fun recentDeviceFolders() = host.recentDeviceFolders()
        override fun openDeviceFolder(folder: DeviceFolder) {
            host.openDeviceFolder(folder)
            openTab(MobileTab.Files)
        }

        override fun switchView(view: AppView) = this@DiligentController.switchView(view)
        override fun setSuggestDesktop(on: Boolean) {
            views.suggestDesktopOnBigScreens = on
            shell.suggestDesktop = on
        }
        override fun editorCommand(command: String, vararg args: Any?) = editor.run(command, *args)
        override val projectsDir: String get() = host.projectsDir
        override val run: RunControl get() = runner
    }

    /** What the `diligent*` bridge methods call. On the JavaBridge thread, so each hops. */
    val bridgeHost: DiligentBridgeHost = object : DiligentBridgeHost {
        override fun setView(view: String) {
            val target = AppView.fromKey(view) ?: return
            activity.runOnUiThread { switchView(target) }
        }

        override fun editorReady() = activity.runOnUiThread {
            editor.editorReady()
            // A new page starts in whatever layout its settings say; bring the
            // extension and the native pieces into step with this device's view.
            shell.view?.let { view ->
                editor.run(COMMAND_APPLY_VIEW, view.key)
                if (view == AppView.Mobile) showTab(shell.tab)
            }
        }

        override fun updateTasks(json: String) {
            // Parsed here, off the UI thread; text that does not parse is dropped.
            val update = TaskUpdate.parse(json) ?: return
            activity.runOnUiThread { onTasks(update) }
        }
    }

    /** The supervisor's latest tasks, or null before the first update. */
    var tasks: TaskUpdate? = null
        private set

    private fun onTasks(update: TaskUpdate) {
        tasks = update
    }

    companion object {
        /** Commands the diligent-bridge extension contributes and allows. */
        const val COMMAND_APPLY_VIEW = "diligent.view.apply"
        const val COMMAND_SHOW_EDITOR = "diligent.mobile.showEditor"
        const val COMMAND_SHOW_TERMINAL = "diligent.mobile.showTerminal"
    }
}
