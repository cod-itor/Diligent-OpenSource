package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.AppView
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * First launch, after VSCodroid's setup: "How do you want to code?"
 *
 * Two large cards, Mobile view first and marked as the one for phones. Nothing
 * is preselected and there is no skip: the user picks one, and the line below
 * says the choice is not final. The cards sit in the lower part of the screen,
 * within a thumb's reach, and the column scrolls on a short landscape screen.
 */
@Composable
fun ChooseViewScreen(onChoose: (AppView) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            val sideBySide = maxWidth >= DiligentTheme.sizes.splitMinWidth
            val contentWidth = if (sideBySide) DiligentTheme.sizes.sheetMaxWidth * 2 else DiligentTheme.sizes.sheetMaxWidth
            // At least the screen's height, so the welcome sits at the top and
            // the choice at the bottom, in thumb reach; taller when the screen
            // is short, so it scrolls instead.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(DiligentTheme.spacing.l),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.widthIn(max = contentWidth).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = null,
                        tint = DiligentTheme.colors.primary,
                        modifier = Modifier.size(DiligentTheme.sizes.iconLarge),
                    )
                    Text(
                        stringResource(R.string.diligent_welcome),
                        style = DiligentTheme.type.material.titleLarge,
                        color = DiligentTheme.colors.onBackground,
                    )
                }
                Column(
                    modifier = Modifier.widthIn(max = contentWidth).fillMaxWidth().padding(top = DiligentTheme.spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
                ) {
                    Text(
                        stringResource(R.string.diligent_choose_view_title),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = DiligentTheme.spacing.s)
                            .semantics { heading() },
                        style = DiligentTheme.type.material.headlineMedium,
                        color = DiligentTheme.colors.onBackground,
                    )
                    val mobile = @Composable { cardModifier: Modifier ->
                        ViewCard(
                            title = stringResource(R.string.diligent_view_mobile),
                            badge = stringResource(R.string.diligent_view_mobile_badge),
                            body = stringResource(R.string.diligent_view_mobile_body),
                            icon = Icons.Outlined.PhoneAndroid,
                            emphasized = true,
                            onClick = { onChoose(AppView.Mobile) },
                            modifier = cardModifier,
                        )
                    }
                    val desktop = @Composable { cardModifier: Modifier ->
                        ViewCard(
                            title = stringResource(R.string.diligent_view_desktop),
                            badge = null,
                            body = stringResource(R.string.diligent_view_desktop_body),
                            icon = Icons.Outlined.DesktopWindows,
                            emphasized = false,
                            onClick = { onChoose(AppView.Desktop) },
                            modifier = cardModifier,
                        )
                    }
                    if (sideBySide) {
                        // Landscape and tablets: both choices in view at once.
                        Row(
                            modifier = Modifier.height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
                        ) {
                            mobile(Modifier.weight(1f).fillMaxHeight())
                            desktop(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        mobile(Modifier)
                        desktop(Modifier)
                    }
                    Text(
                        stringResource(R.string.diligent_choose_view_footer),
                        modifier = Modifier.fillMaxWidth().padding(top = DiligentTheme.spacing.s),
                        style = DiligentTheme.type.material.bodyMedium,
                        color = DiligentTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** One choice: a whole card is the button, so it is easy to hit with a thumb. */
@Composable
private fun ViewCard(
    title: String,
    badge: String?,
    body: String,
    icon: ImageVector,
    emphasized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (emphasized) DiligentTheme.colors.primaryContainer else DiligentTheme.colors.surfaceContainer
    val content = if (emphasized) DiligentTheme.colors.onPrimaryContainer else DiligentTheme.colors.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier
            .widthIn(max = DiligentTheme.sizes.sheetMaxWidth)
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget * 2),
        shape = DiligentTheme.shapes.large,
        color = container,
        contentColor = content,
        border = if (emphasized) null else BorderStroke(DiligentTheme.sizes.divider, DiligentTheme.colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(DiligentTheme.spacing.l),
            horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(DiligentTheme.sizes.iconLarge))
            Column(verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs)) {
                Text(title, style = DiligentTheme.type.material.titleLarge)
                if (badge != null) {
                    Text(badge, style = DiligentTheme.type.material.labelLarge, color = if (emphasized) content else DiligentTheme.colors.primary)
                }
                Text(body, style = DiligentTheme.type.material.bodyMedium)
            }
        }
    }
}

@Preview(name = "Choose view, light", widthDp = 360, heightDp = 760)
@Composable
private fun ChooseViewLightPreview() = DiligentTheme(darkTheme = false) { ChooseViewScreen(onChoose = {}) }

@Preview(name = "Choose view, dark", widthDp = 360, heightDp = 760)
@Composable
private fun ChooseViewDarkPreview() = DiligentTheme(darkTheme = true) { ChooseViewScreen(onChoose = {}) }

@Preview(name = "Choose view, landscape", widthDp = 760, heightDp = 360)
@Composable
private fun ChooseViewLandscapePreview() = DiligentTheme(darkTheme = false) { ChooseViewScreen(onChoose = {}) }
