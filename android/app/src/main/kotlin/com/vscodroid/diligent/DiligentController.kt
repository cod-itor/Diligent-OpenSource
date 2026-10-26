package com.vscodroid.diligent

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.ComposeView
import com.vscodroid.diligent.ui.screens.ChooseViewScreen
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * Diligent's layer on top of VSCodroid's editor activity.
 *
 * `MainActivity` is VSCodroid's, and every line added there is a line that can
 * conflict when VSCodroid's updates are merged. So the activity holds one of
 * these and calls into it from a handful of places, and what Diligent does
 * lives here and in the files next to it.
 *
 * [webView] is a supplier rather than a view because the activity replaces its
 * WebView after a renderer crash.
 */
class DiligentController(
    private val activity: Activity,
    private val webView: () -> WebView?,
) {
    /** This device's view, Mobile or Desktop. Read on first use, off the start path. */
    val views by lazy {
        ViewSettings(activity.getSharedPreferences(ViewSettings.PREFS_NAME, Context.MODE_PRIVATE))
    }

    /** Kotlin to editor: runs allowlisted editor commands. */
    val editor = EditorChannel { script, result ->
        val view = webView()
        if (view == null) result(null) else view.evaluateJavascript(script) { result(it) }
    }

    /**
     * Called once the activity's layout is set. On a device where no view has
     * been chosen, asks "How do you want to code?" over the editor, which keeps
     * starting underneath, so the editor is ready by the time the user picks.
     */
    fun onLayoutReady() {
        if (!views.hasChosen) showViewChooser()
    }

    private fun showViewChooser() {
        val chooser = ComposeView(activity)
        chooser.setContent {
            DiligentTheme {
                // There is nothing behind this screen to go back to yet:
                // Back leaves the app the way Home does, and asks again next time.
                BackHandler { activity.moveTaskToBack(true) }
                ChooseViewScreen(onChoose = { view ->
                    views.choose(view)
                    (chooser.parent as? ViewGroup)?.removeView(chooser)
                })
            }
        }
        activity.addContentView(
            chooser,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** What the `diligent*` bridge methods call. On the JavaBridge thread, so each hops. */
    val bridgeHost: DiligentBridgeHost = object : DiligentBridgeHost {
        override fun editorReady() = activity.runOnUiThread { editor.editorReady() }
    }
}
