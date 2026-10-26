package com.vscodroid.diligent

import android.app.Activity
import android.webkit.WebView

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
    /** Kotlin to editor: runs allowlisted editor commands. */
    val editor = EditorChannel { script, result ->
        val view = webView()
        if (view == null) result(null) else view.evaluateJavascript(script) { result(it) }
    }

    /** What the `diligent*` bridge methods call. On the JavaBridge thread, so each hops. */
    val bridgeHost: DiligentBridgeHost = object : DiligentBridgeHost {
        override fun editorReady() = activity.runOnUiThread { editor.editorReady() }
    }
}
