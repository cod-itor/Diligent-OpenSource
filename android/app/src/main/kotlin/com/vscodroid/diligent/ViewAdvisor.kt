package com.vscodroid.diligent

import android.app.Activity
import android.content.res.Configuration
import android.view.Display
import androidx.window.layout.WindowMetricsCalculator

/** What makes a screen big enough to suggest Desktop view, strongest first. */
enum class BigScreenReason { DeX, ExternalDisplay, WideWindow }

/** The screen the app is on now. [reason] is null on an ordinary phone screen. */
data class ScreenState(val reason: BigScreenReason?) {
    val big: Boolean get() = reason != null
}

/** A prompt to show. The user decides; nothing switches without them. */
enum class ViewSuggestion {
    /** "Switch to Desktop view?" on a big screen, in Mobile view. */
    SwitchToDesktop,

    /** "Switch back to Mobile view?" once the big screen has gone. */
    SwitchBackToMobile,
}

/**
 * Decides when to suggest a view, from changes of the screen (Phase 1, Part B,
 * item 7 of the guide). Plain Kotlin, so every rule is tested without a device.
 *
 * - The screen becoming big (DeX, an external display, a window at least
 *   840 dp wide) while in Mobile view suggests Desktop view, when the user
 *   allows suggestions.
 * - The big screen going away suggests Mobile view again, but only when the
 *   user took the Desktop suggestion: a user who chose Desktop view on their
 *   own is never nagged about it.
 * - "Not now" silences that reason for the rest of the session, so a tablet
 *   rotated across 840 dp is asked once, not on every turn. A stronger new
 *   reason (DeX turning on after a wide window was declined) still asks.
 * - Only changes ask. The same big screen measured twice asks once.
 */
class ViewAdvisor {
    private var last: ScreenState = ScreenState(null)
    private val declined = mutableSetOf<BigScreenReason>()
    private var tookDesktopSuggestion = false

    /** A new measurement of the screen. Returns the prompt to show, if any. */
    fun onScreen(now: ScreenState, view: AppView?, suggestionsOn: Boolean): ViewSuggestion? {
        val before = last
        last = now
        if (!suggestionsOn || view == null) return null
        if (now.big && now.reason != before.reason && view == AppView.Mobile && now.reason !in declined) {
            return ViewSuggestion.SwitchToDesktop
        }
        if (!now.big && before.big && view == AppView.Desktop && tookDesktopSuggestion) {
            tookDesktopSuggestion = false
            return ViewSuggestion.SwitchBackToMobile
        }
        return null
    }

    /** The user answered [suggestion]. */
    fun answered(suggestion: ViewSuggestion, accepted: Boolean) {
        when (suggestion) {
            ViewSuggestion.SwitchToDesktop ->
                if (accepted) tookDesktopSuggestion = true else last.reason?.let(declined::add)
            ViewSuggestion.SwitchBackToMobile -> Unit
        }
    }

    companion object {
        /** Material's "expanded" width class starts here. */
        const val WIDE_WINDOW_DP = 840

        /** Measures [activity]'s screen. */
        fun measure(activity: Activity): ScreenState {
            val reason = when {
                isDeX(activity.resources.configuration) -> BigScreenReason.DeX
                (activity.display?.displayId ?: Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY ->
                    BigScreenReason.ExternalDisplay
                windowWidthDp(activity) >= WIDE_WINDOW_DP -> BigScreenReason.WideWindow
                else -> null
            }
            return ScreenState(reason)
        }

        private fun windowWidthDp(activity: Activity): Float {
            val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
            return bounds.width() / activity.resources.displayMetrics.density
        }

        /**
         * Samsung DeX, read the way Samsung documents it: the configuration's
         * `semDesktopModeEnabled` equals the class constant
         * `SEM_DESKTOP_MODE_ENABLED`. Both exist only on Samsung builds, so any
         * failure to read them means "not DeX".
         */
        internal fun isDeX(config: Configuration): Boolean = try {
            val type = config.javaClass
            val enabled = type.getField("SEM_DESKTOP_MODE_ENABLED").getInt(type)
            val mode = type.getField("semDesktopModeEnabled").getInt(config)
            mode == enabled
        } catch (_: Exception) {
            false
        }
    }
}
