package com.vscodroid.diligent

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two ways to use Diligent. The user picks one; the app never switches on its own. */
enum class AppView(
    /** What is stored, and what the diligent-bridge extension is told. Never rename. */
    val key: String,
) {
    /** Diligent's own touch-first screens around the editor. */
    Mobile("mobile"),

    /** The full VS Code interface, as VSCodroid shows it. */
    Desktop("desktop");

    companion object {
        /** The view stored as [key], or null for anything else, including a value from a newer version. */
        fun fromKey(key: String?): AppView? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Which view this device uses, and whether to suggest Desktop view on a big screen.
 *
 * Per device on purpose: a phone and a tablet signed in to the same account are
 * used differently. Shared preferences are left out of cloud backup and device
 * transfer (`res/xml/data_extraction_rules.xml`), so a new phone asks again.
 *
 * [view] is null until the user has chosen, which is what makes the first-launch
 * question appear. A value this version does not know also reads as not chosen,
 * so the user is asked rather than dropped into a view at random.
 */
class ViewSettings(private val prefs: SharedPreferences) {

    private val _view = MutableStateFlow(AppView.fromKey(prefs.getString(KEY_VIEW, null)))

    /** The chosen view, or null before the first choice. */
    val view: StateFlow<AppView?> = _view.asStateFlow()

    /** Whether the user has answered the first-launch question. */
    val hasChosen: Boolean get() = _view.value != null

    /** Stores [view] as this device's choice. */
    fun choose(view: AppView) {
        prefs.edit { putString(KEY_VIEW, view.key) }
        _view.value = view
    }

    /**
     * Whether a big screen (a wide window, Samsung DeX, an external display)
     * may suggest Desktop view. A suggestion only: the user still decides.
     */
    var suggestDesktopOnBigScreens: Boolean
        get() = prefs.getBoolean(KEY_SUGGEST_DESKTOP, true)
        set(value) {
            prefs.edit { putBoolean(KEY_SUGGEST_DESKTOP, value) }
        }

    companion object {
        /** Diligent's own preferences file, apart from VSCodroid's. */
        const val PREFS_NAME = "diligent"

        internal const val KEY_VIEW = "view"
        internal const val KEY_SUGGEST_DESKTOP = "suggest_desktop_on_big_screens"
    }
}
