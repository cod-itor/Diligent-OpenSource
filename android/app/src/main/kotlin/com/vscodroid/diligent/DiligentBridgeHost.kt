package com.vscodroid.diligent

/**
 * What the `diligent*` methods of [com.vscodroid.bridge.AndroidBridge] ask of
 * Diligent. Kept as one interface so the bridge, which is VSCodroid's file,
 * gains a single constructor argument rather than one per method.
 *
 * Every call arrives on the WebView's JavaBridge thread, never the UI thread;
 * an implementation that touches a view hops first.
 */
interface DiligentBridgeHost {
    /** The diligent-bridge extension is listening in the page. */
    fun editorReady()

    /** The editor asked for a view (`mobile` or `desktop`); anything else is ignored. */
    fun setView(view: String)

    /**
     * The supervisor's tasks, as JSON (see `diligentUpdateTasks` in
     * docs/05-API_SPEC.md). Text that does not parse is ignored.
     */
    fun updateTasks(json: String)
}
