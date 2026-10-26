package com.vscodroid.diligent

import org.json.JSONArray
import org.json.JSONObject

/**
 * A file or folder for an editor command, by its absolute path on the phone.
 *
 * The editor addresses files by URI, and in this app the URI is the editor
 * server's (`vscode-remote://...`), not `file://`, which Kotlin cannot know.
 * So the path travels as `{"$path": "..."}` and the diligent-bridge extension
 * turns it into the right URI on the editor's side.
 */
data class EditorPath(val path: String)

/**
 * How Kotlin asks the editor to run a command: Mobile view's Save button,
 * opening a file tapped in Files, the editor layout of each view.
 *
 * A message `{cmd, args}` is posted on the page's
 * `BroadcastChannel('diligent-events')`, where the bundled `diligent-bridge`
 * web extension runs `vscode.commands.executeCommand(cmd, ...args)`, for the
 * commands on its allowlist only. The allowlist is not optional: any web
 * extension in the page can post on the same channel.
 *
 * **Who is listening.** The extension starts some time after the page loads,
 * and says so through the `diligentEditorReady` bridge method. [editorReady]
 * answers by setting a flag in that page, and every post checks the flag
 * first. A page that has since been replaced (a reload, a folder switch, a
 * renderer crash) has no flag, so the post reports it was not delivered and
 * the message is held for the next page's extension. No page-load hook is
 * needed, which matters because the WebView's "page finished" and the
 * extension's "ready" can arrive in either order.
 *
 * Messages sent while nobody listens are held and sent in order once someone
 * does, so a tap during a cold start is not lost.
 *
 * Main thread only: [evaluate] is `WebView.evaluateJavascript`, whose callback
 * receives the script's value as JSON text.
 */
class EditorChannel(
    private val evaluate: (script: String, result: (String?) -> Unit) -> Unit,
) {
    private val pending = ArrayDeque<String>()

    /** Whether the last thing heard is that the extension is listening. */
    var isReady: Boolean = false
        private set

    /**
     * Asks the editor to run [command] with [args]: strings, numbers,
     * booleans, null, or an [EditorPath].
     */
    fun run(command: String, vararg args: Any?) {
        val message = message(command, args.toList())
        if (isReady) post(message) else hold(message)
    }

    /**
     * The extension is listening in the page on screen now: mark that page,
     * then send what was held, in order.
     */
    fun editorReady() {
        evaluate(READY_SCRIPT) { }
        isReady = true
        val held = pending.toList()
        pending.clear()
        held.forEach(::post)
    }

    private fun post(message: String) {
        evaluate(postScript(message)) { result ->
            if (result != "true") {
                // The page that said it was listening is gone. Hold this one
                // for the next page's extension, which will say so again.
                isReady = false
                hold(message)
            }
        }
    }

    private fun hold(message: String) {
        pending.addLast(message)
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    companion object {
        /** The channel name, shared with the diligent-bridge extension. */
        const val CHANNEL = "diligent-events"

        /** The page flag that says this document's extension is listening. */
        private const val FLAG = "__diligentEditorReady"

        /**
         * How many messages to hold for a page that is not listening. A
         * user cannot tap faster than this during a cold start; past it the
         * oldest go, since the newest is what the user is waiting for.
         */
        const val MAX_PENDING = 32

        internal const val READY_SCRIPT = "window.$FLAG = true;"

        /** The message as JSON text. */
        internal fun message(command: String, args: List<Any?>): String {
            val array = JSONArray()
            args.forEach { arg ->
                array.put(
                    when (arg) {
                        null -> JSONObject.NULL
                        is EditorPath -> JSONObject().put("\$path", arg.path)
                        is String, is Boolean, is Int, is Long, is Double -> arg
                        else -> throw IllegalArgumentException(
                            "EditorChannel cannot send ${arg::class.java.simpleName}"
                        )
                    }
                )
            }
            return JSONObject().put("cmd", command).put("args", array).toString()
        }

        /**
         * The script that posts [message] on the channel and answers whether
         * it did. JSON is a valid JavaScript literal, so the message is spliced
         * in as is. A page without the flag, or one that throws, answers false.
         */
        internal fun postScript(message: String): String =
            "(function(){if(!window.$FLAG)return false;" +
                "try{var c=new BroadcastChannel('$CHANNEL');" +
                "c.postMessage($message);c.close();return true;}catch(e){return false;}})();"
    }
}
