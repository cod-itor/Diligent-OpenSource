package com.vscodroid.diligent

import android.content.res.Configuration
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ViewAdvisorTest {

    private val phone = ScreenState(null)
    private val wide = ScreenState(BigScreenReason.WideWindow)
    private val dex = ScreenState(BigScreenReason.DeX)
    private val advisor = ViewAdvisor()

    @Test
    fun `a big screen in Mobile view suggests Desktop view`() {
        assertNull(advisor.onScreen(phone, AppView.Mobile, true))
        assertEquals(ViewSuggestion.SwitchToDesktop, advisor.onScreen(dex, AppView.Mobile, true))
    }

    @Test
    fun `starting on a big screen suggests too`() {
        assertEquals(ViewSuggestion.SwitchToDesktop, advisor.onScreen(wide, AppView.Mobile, true))
    }

    @Test
    fun `the same big screen measured again does not ask twice`() {
        advisor.onScreen(wide, AppView.Mobile, true)
        assertNull(advisor.onScreen(wide, AppView.Mobile, true))
    }

    @Test
    fun `nothing is suggested when suggestions are off, in Desktop view, or before a view is chosen`() {
        assertNull(advisor.onScreen(dex, AppView.Mobile, suggestionsOn = false))
        assertNull(ViewAdvisor().onScreen(dex, AppView.Desktop, true))
        assertNull(ViewAdvisor().onScreen(dex, null, true))
    }

    @Test
    fun `not now silences that reason for the session`() {
        assertEquals(ViewSuggestion.SwitchToDesktop, advisor.onScreen(wide, AppView.Mobile, true))
        advisor.answered(ViewSuggestion.SwitchToDesktop, accepted = false)
        // A tablet rotated back and forth across 840 dp.
        repeat(3) {
            assertNull(advisor.onScreen(phone, AppView.Mobile, true))
            assertNull(advisor.onScreen(wide, AppView.Mobile, true))
        }
        // DeX is a stronger, new reason, and still asks.
        assertEquals(ViewSuggestion.SwitchToDesktop, advisor.onScreen(dex, AppView.Mobile, true))
    }

    @Test
    fun `leaving the big screen offers Mobile view back after the suggestion was taken`() {
        advisor.onScreen(dex, AppView.Mobile, true)
        advisor.answered(ViewSuggestion.SwitchToDesktop, accepted = true)
        assertEquals(ViewSuggestion.SwitchBackToMobile, advisor.onScreen(phone, AppView.Desktop, true))
        // Offered once.
        advisor.onScreen(dex, AppView.Desktop, true)
        assertNull(advisor.onScreen(phone, AppView.Desktop, true))
    }

    @Test
    fun `a user who chose Desktop view on their own is never asked to leave it`() {
        advisor.onScreen(dex, AppView.Desktop, true)
        assertNull(advisor.onScreen(phone, AppView.Desktop, true))
    }

    @Test
    fun `a configuration without Samsung's fields is not DeX`() {
        assertFalse(ViewAdvisor.isDeX(mockk<Configuration>(relaxed = true)))
    }
}
