package com.vscodroid.diligent.guide

import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.navigation.MoreDetail

/**
 * Beginner mode's one-line tip for each native screen. Shown at the top of
 * its screen until closed; closing one closes it for good, and turning
 * beginner mode on again brings them all back.
 */
enum class Tip(
    /** What is stored for a closed tip. Never rename. */
    val key: String,
) {
    Projects("projects"),
    Files("files"),
    More("more"),
    Run("run"),
    Setup("setup"),
    Laptop("laptop"),
    AI("ai"),
    Devices("devices"),
    Share("share"),
    Health("health"),
    Settings("settings"),
    Advanced("advanced"),
    ;

    companion object {
        fun fromKey(key: String?): Tip? = entries.firstOrNull { it.key == key }

        /** The tip of a native tab, or null for the tabs that show the editor. */
        fun of(tab: MobileTab): Tip? = when (tab) {
            MobileTab.Projects -> Projects
            MobileTab.Files -> Files
            MobileTab.More -> More
            MobileTab.Editor, MobileTab.Terminal -> null
        }

        /** The tip of a More screen. */
        fun of(detail: MoreDetail): Tip = when (detail) {
            MoreDetail.Run -> Run
            MoreDetail.Setup -> Setup
            MoreDetail.Laptop -> Laptop
            MoreDetail.AI -> AI
            MoreDetail.Devices -> Devices
            MoreDetail.Share -> Share
            MoreDetail.Health -> Health
            MoreDetail.Settings -> Settings
            MoreDetail.Advanced -> Advanced
        }

        /** Whether [tip] shows: beginner mode is on and the user has not closed it. */
        fun shows(tip: Tip?, beginnerMode: Boolean, dismissed: Set<Tip>): Boolean =
            tip != null && beginnerMode && tip !in dismissed
    }
}
