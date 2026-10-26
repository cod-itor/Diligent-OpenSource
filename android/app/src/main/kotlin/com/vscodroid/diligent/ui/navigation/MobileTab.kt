package com.vscodroid.diligent.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.vscodroid.R

/**
 * The five tabs of Mobile view's bottom bar, in the order they are shown.
 *
 * [Editor] and [Terminal] show the editor's WebView; the other three are
 * native screens drawn over it. [showsEditor] is what the activity reads to
 * decide which is in front.
 */
enum class MobileTab(
    @StringRes val label: Int,
    val icon: ImageVector,
    val showsEditor: Boolean,
) {
    Projects(R.string.diligent_tab_projects, Icons.Outlined.Home, showsEditor = false),
    Files(R.string.diligent_tab_files, Icons.Outlined.Folder, showsEditor = false),
    Editor(R.string.diligent_tab_editor, Icons.Outlined.Code, showsEditor = true),
    Terminal(R.string.diligent_tab_terminal, Icons.Outlined.Terminal, showsEditor = true),
    More(R.string.diligent_tab_more, Icons.Outlined.MoreHoriz, showsEditor = false),
}

/**
 * Which tab is open, and the way back through the tabs visited before it.
 *
 * Back in a native tab returns to the tab the user came from, the way a
 * browser's Back does, rather than walking the bar left to right. A tab
 * appears in the history once: going Projects, Files, Projects, Files leaves
 * one way back, to Projects, not three.
 *
 * Plain Kotlin so it is tested without a device; the activity holds one and
 * mirrors [current] into Compose state.
 */
class TabHistory(start: MobileTab = MobileTab.Projects) {
    private val visited = ArrayDeque<MobileTab>().apply { addLast(start) }

    val current: MobileTab get() = visited.last()

    /** Opens [tab]. Opening the tab already shown changes nothing. */
    fun open(tab: MobileTab) {
        if (tab == current) return
        visited.remove(tab)
        visited.addLast(tab)
        while (visited.size > MAX_DEPTH) visited.removeFirst()
    }

    /** Whether [back] has somewhere to go. */
    val canGoBack: Boolean get() = visited.size > 1

    /**
     * Returns to the previous tab and answers true, or answers false when
     * this is the first one, which leaves Back to the activity.
     */
    fun back(): Boolean {
        if (!canGoBack) return false
        visited.removeLast()
        return true
    }

    private companion object {
        /** Every tab once, which is the most the rule above can hold. */
        val MAX_DEPTH = MobileTab.entries.size
    }
}
