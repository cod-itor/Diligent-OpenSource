package com.vscodroid.diligent

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EditorChannelTest {

    /** A page: whether its extension is listening, and what reached it. */
    private var pageListening = false
    private val delivered = mutableListOf<JSONObject>()

    private val channel = EditorChannel { script, result ->
        when {
            script == EditorChannel.READY_SCRIPT -> {
                pageListening = true
                result("true")
            }
            "c.postMessage(" in script -> {
                if (pageListening) {
                    delivered += messageIn(script)
                    result("true")
                } else {
                    result("false")
                }
            }
            else -> error("unexpected script $script")
        }
    }

    /** The message a posted script carries, parsed back. */
    private fun messageIn(script: String): JSONObject {
        val start = script.indexOf("c.postMessage(") + "c.postMessage(".length
        val end = script.indexOf(");c.close()")
        return JSONObject(script.substring(start, end))
    }

    /** A new document replaced the page: its extension has not started. */
    private fun reloadPage() {
        pageListening = false
    }

    @Test
    fun `a message carries the command and its arguments`() {
        val json = JSONObject(EditorChannel.message("cmd.x", listOf("a", 2, true, null, EditorPath("/p/f.js"))))
        assertEquals("cmd.x", json.getString("cmd"))
        val args = json.getJSONArray("args")
        assertEquals("a", args.getString(0))
        assertEquals(2, args.getInt(1))
        assertEquals(true, args.getBoolean(2))
        assertTrue(args.isNull(3))
        assertEquals("/p/f.js", args.getJSONObject(4).getString("\$path"))
    }

    @Test
    fun `text that would break out of the script stays inside the string`() {
        val tricky = "a'b\"c\\d\n</script>"
        channel.editorReady()
        channel.run("cmd", tricky)
        assertEquals(tricky, delivered.single().getJSONArray("args").getString(0))
    }

    @Test
    fun `messages wait until the editor is listening, then go in order`() {
        channel.run("first")
        channel.run("second")
        assertTrue(delivered.isEmpty())
        channel.editorReady()
        assertEquals(listOf("first", "second"), delivered.map { it.getString("cmd") })
    }

    @Test
    fun `a message to a page that was replaced waits for the next page`() {
        channel.editorReady()
        reloadPage()
        channel.run("held")
        assertTrue(delivered.isEmpty())
        assertFalse(channel.isReady)
        channel.editorReady()
        assertEquals("held", delivered.single().getString("cmd"))
    }

    @Test
    fun `only the newest messages are held`() {
        repeat(EditorChannel.MAX_PENDING + 5) { channel.run("m$it") }
        channel.editorReady()
        assertEquals(EditorChannel.MAX_PENDING, delivered.size)
        assertEquals("m5", delivered.first().getString("cmd"))
    }

    @Test
    fun `an argument the editor cannot read is refused`() {
        assertThrows<IllegalArgumentException> { channel.run("cmd", Any()) }
    }

    @Test
    fun `the script posts on the diligent-events channel only when the page is marked`() {
        val script = EditorChannel.postScript("{}")
        assertTrue("new BroadcastChannel('diligent-events')" in script)
        assertTrue(script.startsWith("(function(){if(!window.__diligentEditorReady)return false;"))
    }
}
