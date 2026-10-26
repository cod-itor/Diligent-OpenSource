package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * Mobile view's bottom bar: the five tabs, in thumb reach. Insets are not
 * handled here; the activity's root already keeps everything above the
 * system bars.
 */
@Composable
fun DiligentTabBar(
    current: MobileTab,
    onSelect: (MobileTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeFont = LocalDensity.current.fontScale > LARGE_FONT_SCALE
    NavigationBar(
        modifier = modifier.height(DiligentTheme.sizes.tabBar),
        containerColor = DiligentTheme.colors.surfaceContainer,
        windowInsets = WindowInsets(0),
    ) {
        MobileTab.entries.forEach { tab ->
            val label = stringResource(tab.label)
            NavigationBarItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(label, style = DiligentTheme.type.material.labelMedium, maxLines = 1) },
                // At a large font size five labels no longer fit beside each
                // other; then only the open tab keeps its label.
                alwaysShowLabel = !largeFont,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = DiligentTheme.colors.onPrimaryContainer,
                    indicatorColor = DiligentTheme.colors.primaryContainer,
                    selectedTextColor = DiligentTheme.colors.onSurface,
                    unselectedIconColor = DiligentTheme.colors.onSurfaceVariant,
                    unselectedTextColor = DiligentTheme.colors.onSurfaceVariant,
                ),
            )
        }
    }
}

@Preview(name = "Tab bar, light")
@Composable
private fun TabBarLightPreview() = DiligentTheme(darkTheme = false) {
    DiligentTabBar(current = MobileTab.Files, onSelect = {})
}

@Preview(name = "Tab bar, dark")
@Composable
private fun TabBarDarkPreview() = DiligentTheme(darkTheme = true) {
    DiligentTabBar(current = MobileTab.Editor, onSelect = {})
}

/** Above this font scale the bars drop labels they cannot fit (the tab bar's, the action bar's). */
internal const val LARGE_FONT_SCALE = 1.3f

@Preview(name = "Tab bar, 200% font", widthDp = 360, fontScale = 2f)
@Composable
private fun TabBarLargeFontPreview() = DiligentTheme(darkTheme = false) {
    DiligentTabBar(current = MobileTab.Files, onSelect = {})
}
