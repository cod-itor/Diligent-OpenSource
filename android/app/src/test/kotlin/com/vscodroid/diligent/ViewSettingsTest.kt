package com.vscodroid.diligent

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewSettingsTest {

    /** Stands in for the preferences file, so a second ViewSettings reads what the first stored. */
    private val stored = mutableMapOf<String, Any>()

    private val prefs: SharedPreferences = mockk {
        every { getString(any(), any()) } answers { stored[firstArg()] as String? ?: secondArg() }
        every { getBoolean(any(), any()) } answers { stored[firstArg()] as Boolean? ?: secondArg() }
        every { edit() } returns mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } answers { stored[firstArg()] = secondArg<String>(); self as SharedPreferences.Editor }
            every { putBoolean(any(), any()) } answers { stored[firstArg()] = secondArg<Boolean>(); self as SharedPreferences.Editor }
        }
    }

    @Test
    fun `nothing is chosen on a new device`() {
        val settings = ViewSettings(prefs)
        assertNull(settings.view.value)
        assertFalse(settings.hasChosen)
    }

    @Test
    fun `a choice is kept for the next start`() {
        ViewSettings(prefs).choose(AppView.Desktop)
        val next = ViewSettings(prefs)
        assertEquals(AppView.Desktop, next.view.value)
        assertTrue(next.hasChosen)
    }

    @Test
    fun `switching updates what is observed`() {
        val settings = ViewSettings(prefs)
        settings.choose(AppView.Mobile)
        assertEquals(AppView.Mobile, settings.view.value)
        settings.choose(AppView.Desktop)
        assertEquals(AppView.Desktop, settings.view.value)
    }

    @Test
    fun `a stored value this version does not know asks again`() {
        stored[ViewSettings.KEY_VIEW] = "tablet"
        assertNull(ViewSettings(prefs).view.value)
    }

    @Test
    fun `the big screen suggestion is on until turned off`() {
        assertTrue(ViewSettings(prefs).suggestDesktopOnBigScreens)
        ViewSettings(prefs).suggestDesktopOnBigScreens = false
        assertFalse(ViewSettings(prefs).suggestDesktopOnBigScreens)
    }

    @Test
    fun `the stored keys never change`() {
        // Renaming one strands every user's choice, so the spelling is pinned.
        assertEquals("mobile", AppView.Mobile.key)
        assertEquals("desktop", AppView.Desktop.key)
        assertEquals(AppView.Mobile, AppView.fromKey("mobile"))
        assertNull(AppView.fromKey(null))
    }
}
