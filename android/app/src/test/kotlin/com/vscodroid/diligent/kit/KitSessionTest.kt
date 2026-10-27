package com.vscodroid.diligent.kit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KitSessionTest {
    private val sent = mutableListOf<List<Any?>>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun session(timeout: Long = 60_000L) =
        KitSession(scope, { cmd, args -> sent += listOf(cmd, *args) }, timeout)

    @Test
    fun `start sends kit add and cancel names the run`() {
        val kit = session()
        kit.start("web-basic", "site")
        assertEquals(listOf(listOf<Any?>("diligent.kit.add", "web-basic", "site")), sent)
        assertTrue(kit.busy)
        // A second start while busy is ignored.
        kit.start("java", "x")
        assertEquals(1, sent.size)

        kit.onProgress(KitProgress.parse("""{"runId":"r9","recipe":"web-basic","project":"/p/site","step":{"index":0,"total":2,"title":"Write files","state":"running"}}""")!!)
        kit.cancel()
        assertEquals(listOf<Any?>("diligent.kit.cancel", "r9"), sent.last())
        kit.onProgress(KitProgress.parse("""{"runId":"r9","done":{"ok":false,"message":"Stopped."}}""")!!)
        assertFalse(kit.busy)
        kit.dismiss()
        assertNull(kit.run)
    }

    @Test
    fun `no answer at all ends the run`() = runBlocking {
        val kit = session(timeout = 10)
        kit.start("web-basic", "site")
        kotlinx.coroutines.delay(200)
        assertTrue(kit.run!!.timedOut)
        assertFalse(kit.busy)
    }

    @Test
    fun `prepare sends kit add in prepare mode and hears the prepare folder`() {
        val kit = session()
        kit.prepare("nextjs")
        assertEquals(listOf(listOf<Any?>("diligent.kit.add", "nextjs", "nextjs", "prepare")), sent)
        assertTrue(kit.run!!.prepare)
        kit.onProgress(KitProgress.parse("""{"runId":"p1","recipe":"nextjs","project":"/h/.diligent/kit-prepare/nextjs","step":{"index":0,"total":4,"title":"Create","state":"running"}}""")!!)
        assertEquals("p1", kit.run!!.runId)
        kit.onProgress(KitProgress.parse("""{"runId":"p1","done":{"ok":true,"projectPath":"/h/.diligent/kit-prepare/nextjs","message":"Ready offline."}}""")!!)
        assertTrue(kit.run!!.succeeded)
        assertFalse(kit.busy)
    }
}
