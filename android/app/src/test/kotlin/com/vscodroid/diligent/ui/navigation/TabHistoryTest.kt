package com.vscodroid.diligent.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TabHistoryTest {

    @Test
    fun `starts on Projects with nowhere to go back to`() {
        val history = TabHistory()
        assertEquals(MobileTab.Projects, history.current)
        assertFalse(history.canGoBack)
        assertFalse(history.back())
        assertEquals(MobileTab.Projects, history.current)
    }

    @Test
    fun `back returns to the tab the user came from`() {
        val history = TabHistory()
        history.open(MobileTab.Files)
        history.open(MobileTab.Editor)
        assertTrue(history.back())
        assertEquals(MobileTab.Files, history.current)
        assertTrue(history.back())
        assertEquals(MobileTab.Projects, history.current)
        assertFalse(history.back())
    }

    @Test
    fun `a tab is in the history once`() {
        val history = TabHistory()
        history.open(MobileTab.Files)
        history.open(MobileTab.Projects)
        history.open(MobileTab.Files)
        assertTrue(history.back())
        assertEquals(MobileTab.Projects, history.current)
        assertFalse(history.canGoBack)
    }

    @Test
    fun `opening the current tab changes nothing`() {
        val history = TabHistory()
        history.open(MobileTab.Files)
        history.open(MobileTab.Files)
        assertTrue(history.back())
        assertEquals(MobileTab.Projects, history.current)
        assertFalse(history.canGoBack)
    }

    @Test
    fun `can start on another tab`() {
        val history = TabHistory(MobileTab.Editor)
        assertEquals(MobileTab.Editor, history.current)
        assertFalse(history.canGoBack)
    }
}
