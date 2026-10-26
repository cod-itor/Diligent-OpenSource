package com.vscodroid.diligent.run

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunNotesTest {

    private val stored = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private val prefs: SharedPreferences = mockk {
        every { getStringSet(any(), any()) } answers { stored[firstArg()] as Set<String>? ?: secondArg() }
        every { edit() } returns mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putStringSet(any(), any()) } answers {
                stored[firstArg()] = secondArg<Set<String>>().toSet()
                self as SharedPreferences.Editor
            }
        }
    }

    private val next = RunProfile("dev", "npm run dev -- --webpack", 3000)

    @Test
    fun `the webpack note shows once per project`() {
        val notes = RunNotes(prefs)
        assertTrue(notes.showWebpackNote("/p/a", next))
        notes.dismissWebpackNote("/p/a")
        assertFalse(notes.showWebpackNote("/p/a", next))
        assertFalse(RunNotes(prefs).showWebpackNote("/p/a", next))
        assertTrue(notes.showWebpackNote("/p/b", next))
    }

    @Test
    fun `no note for a command without the rewrite, or no profile`() {
        val notes = RunNotes(prefs)
        assertFalse(notes.showWebpackNote("/p/a", RunProfile("dev", "npm run dev", 3000)))
        assertFalse(notes.showWebpackNote("/p/a", null))
    }

    @Test
    fun `what counts as the rewrite`() {
        assertTrue(RunNotes.isWebpackRewrite("npm run dev -- --webpack"))
        assertTrue(RunNotes.isWebpackRewrite("npm run dev -- --port 3001 --webpack"))
        assertFalse(RunNotes.isWebpackRewrite("npm run dev"))
        assertFalse(RunNotes.isWebpackRewrite("npm run dev -- --webpack-config x"))
        assertFalse(RunNotes.isWebpackRewrite("next dev --webpack"))
    }
}
