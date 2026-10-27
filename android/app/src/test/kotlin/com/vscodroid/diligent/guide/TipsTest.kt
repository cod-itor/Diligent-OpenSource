package com.vscodroid.diligent.guide

import android.content.SharedPreferences
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.navigation.MoreDetail
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TipsTest {

    @Test
    fun `a tip shows in beginner mode until it is closed`() {
        assertTrue(Tip.shows(Tip.Files, beginnerMode = true, dismissed = emptySet()))
        assertFalse(Tip.shows(Tip.Files, beginnerMode = true, dismissed = setOf(Tip.Files)))
        assertFalse(Tip.shows(Tip.Files, beginnerMode = false, dismissed = emptySet()))
        assertFalse(Tip.shows(null, beginnerMode = true, dismissed = emptySet()))
    }

    @Test
    fun `every native screen has a tip, the editor tabs none`() {
        assertNull(Tip.of(MobileTab.Editor))
        assertNull(Tip.of(MobileTab.Terminal))
        val tabs = MobileTab.entries.filterNot { it.showsEditor }.map { Tip.of(it) }
        val details = MoreDetail.entries.map { Tip.of(it) }
        val all = tabs + details
        assertEquals(all.size, all.toSet().size)
        assertEquals(Tip.entries.toSet(), all.toSet())
    }

    @Test
    fun `keys are distinct and read back`() {
        assertEquals(Tip.entries.size, Tip.entries.map { it.key }.toSet().size)
        for (tip in Tip.entries) assertEquals(tip, Tip.fromKey(tip.key))
        assertNull(Tip.fromKey("from-a-newer-version"))
    }

    @Test
    fun `settings keep beginner mode and closed tips`() {
        val stored = mutableMapOf<String, Any>()
        val prefs: SharedPreferences = mockk {
            every { getBoolean(any(), any()) } answers { stored[firstArg()] as Boolean? ?: secondArg() }
            @Suppress("UNCHECKED_CAST")
            every { getStringSet(any(), any()) } answers { stored[firstArg()] as Set<String>? ?: secondArg() }
            every { edit() } returns mockk<SharedPreferences.Editor>(relaxed = true) {
                every { putBoolean(any(), any()) } answers { stored[firstArg()] = secondArg<Boolean>(); self as SharedPreferences.Editor }
                every { putStringSet(any(), any()) } answers { stored[firstArg()] = secondArg<Set<String>>(); self as SharedPreferences.Editor }
            }
        }
        val settings = GuideSettings(prefs)
        assertFalse(settings.beginnerMode)
        assertEquals(emptySet<Tip>(), settings.dismissedTips)
        settings.beginnerMode = true
        settings.dismissedTips = setOf(Tip.Run, Tip.More)
        stored[GuideSettings.KEY_DISMISSED_TIPS] = setOf("run", "more", "gone")
        val next = GuideSettings(prefs)
        assertTrue(next.beginnerMode)
        assertEquals(setOf(Tip.Run, Tip.More), next.dismissedTips)
    }
}
