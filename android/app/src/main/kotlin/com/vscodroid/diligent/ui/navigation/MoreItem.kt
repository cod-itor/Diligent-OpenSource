package com.vscodroid.diligent.ui.navigation

/**
 * A screen the More tab opens in its place, with a back arrow to the list.
 * [Advanced] is the list of advanced entries that beginner mode moves there.
 */
enum class MoreDetail { Run, Setup, Laptop, AI, Devices, Share, Health, Advanced, Settings }

/**
 * The entries of the More tab, in the order the list shows them.
 *
 * Each names the phase that builds it. An entry is [available] once it has a
 * [detail] screen to open; until then the list shows it greyed out with
 * "Coming soon", so a beginner sees what is on the way without tapping into
 * an empty screen. Building a later phase means giving its entry a detail.
 *
 * In beginner mode the [advanced] entries leave the list for one Advanced
 * entry, which opens them as a list of their own: a beginner sees Run, Setup
 * and Settings, and the rest one tap further. Without beginner mode the
 * Advanced entry is not shown and everything is in one list.
 *
 * Kept free of resources and icons so the order and the rules are tested
 * without a device; MoreScreen adds the words and pictures.
 */
enum class MoreItem(val phase: Int, val detail: MoreDetail?, val advanced: Boolean = false) {
    Run(phase = 1, detail = MoreDetail.Run),
    Setup(phase = 3, detail = MoreDetail.Setup),
    Laptop(phase = 4, detail = MoreDetail.Laptop, advanced = true),
    AI(phase = 4, detail = MoreDetail.AI, advanced = true),
    Devices(phase = 5, detail = MoreDetail.Devices, advanced = true),
    Share(phase = 4, detail = MoreDetail.Share, advanced = true),
    Health(phase = 2, detail = MoreDetail.Health, advanced = true),
    Advanced(phase = 6, detail = MoreDetail.Advanced),
    Settings(phase = 1, detail = MoreDetail.Settings),
    ;

    val available: Boolean get() = detail != null

    companion object {
        /** Every entry, top to bottom, Advanced included. */
        val ordered: List<MoreItem> = entries.toList()

        /** The More list as shown, with or without [beginnerMode]. */
        fun listed(beginnerMode: Boolean): List<MoreItem> =
            if (beginnerMode) ordered.filterNot { it.advanced } else ordered.filter { it != Advanced }

        /** What the Advanced entry opens. */
        val advancedItems: List<MoreItem> = ordered.filter { it.advanced }

        /** The entry that opens [detail], for headings and the way back. */
        fun of(detail: MoreDetail): MoreItem = entries.first { it.detail == detail }
    }
}
