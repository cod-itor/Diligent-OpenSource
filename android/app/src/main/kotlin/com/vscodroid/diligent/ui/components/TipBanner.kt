package com.vscodroid.diligent.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.AppView
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.guide.Tip
import com.vscodroid.diligent.ui.screens.PreviewActions
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * Beginner mode's tip at the top of a native screen: one short line and a
 * close button. Draws nothing outside beginner mode or once closed.
 */
@Composable
fun TipBanner(tip: Tip?, shell: MobileShell, actions: MobileActions, modifier: Modifier = Modifier) {
    if (tip == null || !Tip.shows(tip, shell.beginnerMode, shell.dismissedTips)) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DiligentTheme.colors.scheme.secondaryContainer,
        contentColor = DiligentTheme.colors.scheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(start = DiligentTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(DiligentTheme.sizes.iconSmall))
            Text(
                stringResource(tip.text),
                modifier = Modifier.weight(1f).padding(vertical = DiligentTheme.spacing.xs),
                style = DiligentTheme.type.material.bodyMedium,
            )
            IconButton(onClick = { actions.dismissTip(tip) }, modifier = Modifier.size(DiligentTheme.sizes.touchTarget)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.diligent_tip_dismiss),
                    modifier = Modifier.size(DiligentTheme.sizes.iconSmall),
                )
            }
        }
    }
}

@get:StringRes
private val Tip.text: Int
    get() = when (this) {
        Tip.Projects -> R.string.diligent_tip_projects
        Tip.Files -> R.string.diligent_tip_files
        Tip.More -> R.string.diligent_tip_more
        Tip.Run -> R.string.diligent_tip_run
        Tip.Setup -> R.string.diligent_tip_setup
        Tip.Laptop -> R.string.diligent_tip_laptop
        Tip.AI -> R.string.diligent_tip_ai
        Tip.Devices -> R.string.diligent_tip_devices
        Tip.Share -> R.string.diligent_tip_share
        Tip.Health -> R.string.diligent_tip_health
        Tip.Settings -> R.string.diligent_tip_settings
        Tip.Advanced -> R.string.diligent_tip_advanced
    }

@Preview(name = "Tip, light", widthDp = 360)
@Composable
private fun TipLightPreview() = DiligentTheme(darkTheme = false) {
    TipBanner(Tip.Files, MobileShell(AppView.Mobile).apply { beginnerMode = true }, PreviewActions)
}

@Preview(name = "Tip, dark", widthDp = 360)
@Composable
private fun TipDarkPreview() = DiligentTheme(darkTheme = true) {
    TipBanner(Tip.Run, MobileShell(AppView.Mobile).apply { beginnerMode = true }, PreviewActions)
}
