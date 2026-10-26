package com.vscodroid.diligent.ui.navigation

/** A screen the More tab opens in its place, with a back arrow to the list. */
enum class MoreDetail { Run, Setup, Laptop, AI, Share, Health, Settings }

/**
 * The entries of the More tab, in the order the list shows them.
 *
 * Each names the phase that builds it. An entry is [available] once it has a
 * [detail] screen to open; until then the list shows it greyed out with
 * "Coming soon", so a beginner sees what is on the way without tapping into
 * an empty screen. Building a later phase means giving its entry a detail.
 *
 * Kept free of resources and icons so the order and the rules are tested
 * without a device; MoreScreen adds the words and pictures.
 */
enum class MoreItem(val phase: Int, val detail: MoreDetail?) {
    Run(phase = 1, detail = MoreDetail.Run),
    Setup(phase = 3, detail = MoreDetail.Setup),
    Laptop(phase = 4, detail = MoreDetail.Laptop),
    AI(phase = 4, detail = MoreDetail.AI),
    Devices(phase = 5, detail = null),
    Share(phase = 4, detail = MoreDetail.Share),
    Health(phase = 2, detail = MoreDetail.Health),
    Settings(phase = 1, detail = MoreDetail.Settings),
    ;

    val available: Boolean get() = detail != null

    companion object {
        /** The More list, top to bottom. */
        val ordered: List<MoreItem> = entries.toList()

        /** The entry that opens [detail], for headings and the way back. */
        fun of(detail: MoreDetail): MoreItem = entries.first { it.detail == detail }
    }
}
