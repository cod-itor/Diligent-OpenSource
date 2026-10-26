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
                label = { Text(label, style = DiligentTheme.type.material.labelSmall) },
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
