package com.vscodroid.diligent

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LowMemoryTest {

    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    @Test
    fun `on by default on a 4 GB phone, off on a 6 GB one`() {
        assertTrue(LowMemoryPolicy.defaultOn(3 * gib))
        assertTrue(LowMemoryPolicy.defaultOn(3_700 * mib)) // a "4 GB" phone's totalMem
        assertTrue(LowMemoryPolicy.defaultOn(4 * gib))
        assertFalse(LowMemoryPolicy.defaultOn(4 * gib + 1))
        assertFalse(LowMemoryPolicy.defaultOn(5_600 * mib)) // a "6 GB" phone
        assertFalse(LowMemoryPolicy.defaultOn(0), "unreadable memory reads as a big phone")
    }

    @Test
    fun `warns before a run only in low-memory mode under 500 MB free`() {
        assertTrue(LowMemoryPolicy.warnBeforeRun(lowMemory = true, availMemBytes = 499 * mib))
        assertFalse(LowMemoryPolicy.warnBeforeRun(lowMemory = true, availMemBytes = 500 * mib))
        assertFalse(LowMemoryPolicy.warnBeforeRun(lowMemory = false, availMemBytes = 100 * mib))
        assertFalse(LowMemoryPolicy.warnBeforeRun(lowMemory = true, availMemBytes = 0), "unreadable never warns")
        assertEquals(499L, LowMemoryPolicy.megabytes(499 * mib + 5))
    }

    private val stored = mutableMapOf<String, Any>()
    private val prefs: SharedPreferences = mockk {
        every { contains(any()) } answers { firstArg<String>() in stored }
        every { getBoolean(any(), any()) } answers { stored[firstArg()] as Boolean? ?: secondArg() }
        every { edit() } returns mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putBoolean(any(), any()) } answers { stored[firstArg()] = secondArg<Boolean>(); self as SharedPreferences.Editor }
        }
    }

    @Test
    fun `follows the phone until the user chooses, then keeps the choice`() {
        assertTrue(LowMemorySettings(prefs) { 3 * gib }.enabled)
        assertFalse(LowMemorySettings(prefs) { 8 * gib }.enabled)
        LowMemorySettings(prefs) { 3 * gib }.enabled = false
        assertFalse(LowMemorySettings(prefs) { 3 * gib }.enabled)
        LowMemorySettings(prefs) { 8 * gib }.enabled = true
        assertTrue(LowMemorySettings(prefs) { 8 * gib }.enabled)
    }
}
