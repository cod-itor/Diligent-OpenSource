package com.vscodroid.diligent

import com.vscodroid.diligent.ui.navigation.MobileTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MobileShellTest {

    private val none = Chrome(topBar = false, nativeScreen = false, actionBar = false, tabBar = false)

    @Test
    fun `desktop view and an unchosen view show nothing of Diligent`() {
        for (tab in MobileTab.entries) for (ime in listOf(true, false)) {
            assertEquals(none, chromeFor(AppView.Desktop, tab, ime), "desktop $tab ime=$ime")
            assertEquals(none, chromeFor(null, tab, ime), "unchosen $tab ime=$ime")
        }
    }

    @Test
    fun `the editor tab has the file name chip, the action bar and no native screen`() {
        assertEquals(
            Chrome(topBar = true, nativeScreen = false, actionBar = true, tabBar = true, fileChip = true),
            chromeFor(AppView.Mobile, MobileTab.Editor, imeVisible = false),
        )
    }

    @Test
    fun `the terminal tab shows the editor page without the action bar`() {
        assertEquals(
            Chrome(topBar = true, nativeScreen = false, actionBar = false, tabBar = true),
            chromeFor(AppView.Mobile, MobileTab.Terminal, imeVisible = false),
        )
    }

    @Test
    fun `native tabs cover the editor`() {
        for (tab in listOf(MobileTab.Projects, MobileTab.Files, MobileTab.More)) {
            assertEquals(
                Chrome(topBar = true, nativeScreen = true, actionBar = false, tabBar = true),
                chromeFor(AppView.Mobile, tab, imeVisible = false),
                tab.name,
            )
        }
    }

    @Test
    fun `the bars step aside for the keyboard and a native screen stays`() {
        val typing = chromeFor(AppView.Mobile, MobileTab.Editor, imeVisible = true)
        assertFalse(typing.topBar || typing.actionBar || typing.tabBar || typing.fileChip)
        assertTrue(chromeFor(AppView.Mobile, MobileTab.Files, imeVisible = true).nativeScreen)
    }

    @Test
    fun `back walks the tabs visited and stops at the first`() {
        val shell = MobileShell(AppView.Mobile)
        assertEquals(MobileTab.Projects, shell.tab)
        shell.open(MobileTab.Files)
        shell.open(MobileTab.Editor)
        assertTrue(shell.back())
        assertEquals(MobileTab.Files, shell.tab)
        assertTrue(shell.back())
        assertEquals(MobileTab.Projects, shell.tab)
        assertFalse(shell.back())
    }

    @Test
    fun `the project name is the folder's last segment`() {
        val shell = MobileShell(AppView.Mobile)
        assertNull(shell.projectName)
        shell.folder = "/data/user/0/com.ditarector.diligent/files/projects/hello-next"
        assertEquals("hello-next", shell.projectName)
        shell.folder = "/storage/emulated/0/work/team.code-workspace"
        assertEquals("team", shell.projectName)
    }

    @Test
    fun `only the editor tab trades the project bar for the file name chip`() {
        for (tab in MobileTab.entries) {
            assertEquals(tab == MobileTab.Editor, chromeFor(AppView.Mobile, tab, imeVisible = false).fileChip, tab.name)
        }
    }

    @Test
    fun `the file switcher starts closed`() {
        assertFalse(MobileShell(AppView.Mobile).fileSwitcherOpen)
    }
}
