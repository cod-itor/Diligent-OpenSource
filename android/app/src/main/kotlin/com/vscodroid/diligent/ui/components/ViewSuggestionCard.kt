package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.ViewSuggestion
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * A small card at the bottom of the screen that suggests a view: "Switch to
 * Desktop view?" on a big screen, "Switch back to Mobile view?" after it.
 * Not a dialog: the editor stays usable around it, and nothing switches
 * unless the user taps Switch. TalkBack announces it when it appears.
 */
@Composable
fun ViewSuggestionCard(
    suggestion: ViewSuggestion,
    onSwitch: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toDesktop = suggestion == ViewSuggestion.SwitchToDesktop
    Surface(
        modifier = modifier
            .widthIn(max = DiligentTheme.sizes.sheetMaxWidth)
            .fillMaxWidth()
            .padding(DiligentTheme.spacing.m)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = DiligentTheme.shapes.large,
        color = DiligentTheme.colors.surfaceContainerHigh,
        contentColor = DiligentTheme.colors.onSurface,
        shadowElevation = DiligentTheme.spacing.s,
    ) {
        Column(
            modifier = Modifier.padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (toDesktop) Icons.Outlined.DesktopWindows else Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = DiligentTheme.colors.primary,
                    modifier = Modifier.size(DiligentTheme.sizes.icon),
                )
                Column(verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs)) {
                    Text(
                        stringResource(if (toDesktop) R.string.diligent_suggest_desktop_title else R.string.diligent_suggest_mobile_title),
                        style = DiligentTheme.type.material.titleMedium,
                    )
                    Text(
                        stringResource(if (toDesktop) R.string.diligent_suggest_desktop_body else R.string.diligent_suggest_mobile_body),
                        style = DiligentTheme.type.material.bodyMedium,
                        color = DiligentTheme.colors.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s, Alignment.End),
            ) {
                TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                    Text(stringResource(R.string.diligent_suggest_not_now))
                }
                Button(onClick = onSwitch, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                    Text(stringResource(R.string.diligent_suggest_switch))
                }
            }
        }
    }
}

@Preview(name = "Suggest Desktop, light", widthDp = 360)
@Composable
private fun SuggestDesktopPreview() = DiligentTheme(darkTheme = false) {
    ViewSuggestionCard(ViewSuggestion.SwitchToDesktop, {}, {})
}

@Preview(name = "Suggest Mobile, dark", widthDp = 360)
@Composable
private fun SuggestMobilePreview() = DiligentTheme(darkTheme = true) {
    ViewSuggestionCard(ViewSuggestion.SwitchBackToMobile, {}, {})
}
