package com.vscodroid.diligent.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import com.vscodroid.BuildConfig
import com.vscodroid.R
import com.vscodroid.diligent.AppView
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.ui.components.SectionHeader
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** The editor command that opens VS Code's own settings editor. */
private const val OPEN_EDITOR_SETTINGS = "workbench.action.openSettings"

/**
 * Settings (More, then Settings). Diligent's own settings only: the view, the
 * big screen suggestion, beginner mode, and About. Everything about the editor itself (font,
 * theme, keys) stays in VS Code's settings, which one row opens, so there is
 * never a second copy of a setting that could disagree with the first.
 *
 * Drawn under MoreScreen's header, which owns the title and the back arrow.
 * Rows wrap their explanation rather than cut it, since this is where a
 * beginner reads what a setting does before touching it.
 */
@Composable
fun SettingsScreen(shell: MobileShell, actions: MobileActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.diligent_settings_section_view))
            // This screen only shows in Mobile view; a null view (previews,
            // or a race with the first-launch choice) reads as Mobile too.
            val current = shell.view ?: AppView.Mobile
            Column(Modifier.selectableGroup()) {
                ViewOption(
                    title = stringResource(R.string.diligent_view_mobile),
                    body = stringResource(R.string.diligent_settings_view_mobile_body),
                    icon = Icons.Outlined.PhoneAndroid,
                    selected = current == AppView.Mobile,
                    onSelect = { if (current != AppView.Mobile) actions.switchView(AppView.Mobile) },
                )
                ViewOption(
                    title = stringResource(R.string.diligent_view_desktop),
                    body = stringResource(R.string.diligent_settings_view_desktop_body),
                    icon = Icons.Outlined.DesktopWindows,
                    selected = current == AppView.Desktop,
                    onSelect = { if (current != AppView.Desktop) actions.switchView(AppView.Desktop) },
                )
            }
            Note(stringResource(R.string.diligent_settings_view_note))
            SettingRow(
                title = stringResource(R.string.diligent_settings_suggest_desktop),
                body = stringResource(R.string.diligent_settings_suggest_desktop_body),
                icon = Icons.Outlined.Tv,
                modifier = Modifier.toggleable(
                    value = shell.suggestDesktop,
                    role = Role.Switch,
                    onValueChange = actions::setSuggestDesktop,
                ),
            ) {
                // The whole row toggles; the switch only shows the state.
                Switch(checked = shell.suggestDesktop, onCheckedChange = null)
            }

            SectionDivider()
            SectionHeader(stringResource(R.string.diligent_settings_section_help))
            SettingRow(
                title = stringResource(R.string.diligent_settings_beginner),
                body = stringResource(R.string.diligent_settings_beginner_body),
                icon = Icons.Outlined.School,
                modifier = Modifier.toggleable(
                    value = shell.beginnerMode,
                    role = Role.Switch,
                    onValueChange = actions::setBeginnerMode,
                ),
            ) {
                Switch(checked = shell.beginnerMode, onCheckedChange = null)
            }

            SectionDivider()
            SectionHeader(stringResource(R.string.diligent_settings_section_editor))
            SettingRow(
                title = stringResource(R.string.diligent_settings_editor),
                body = stringResource(R.string.diligent_settings_editor_body),
                icon = Icons.Outlined.Tune,
                modifier = Modifier.clickable {
                    actions.editorCommand(OPEN_EDITOR_SETTINGS)
                    actions.openTab(MobileTab.Editor)
                },
            ) { TrailingIcon(Icons.AutoMirrored.Outlined.OpenInNew) }

            SectionDivider()
            SectionHeader(stringResource(R.string.diligent_settings_section_about))
            SettingRow(
                title = stringResource(R.string.diligent_settings_app),
                body = stringResource(R.string.diligent_settings_version, BuildConfig.VERSION_NAME),
                icon = Icons.Outlined.Info,
            )
            SettingRow(
                title = stringResource(R.string.diligent_settings_credit),
                body = stringResource(R.string.diligent_settings_credit_body),
                icon = Icons.Outlined.FavoriteBorder,
            )
            val sourceUrl = stringResource(R.string.diligent_settings_source_url)
            SettingRow(
                title = stringResource(R.string.diligent_settings_source),
                body = sourceUrl.removePrefix("https://"),
                icon = Icons.Outlined.Code,
                modifier = Modifier.clickable { openLink(context, sourceUrl) },
            ) { TrailingIcon(Icons.AutoMirrored.Outlined.OpenInNew) }
        }
    }
}

/**
 * Opens [url] in whatever handles web links. A device can have none (a
 * work profile, a stripped ROM), so that case says so instead of crashing.
 */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.diligent_settings_no_browser, Toast.LENGTH_SHORT).show()
    }
}

/** One of the two views, as a radio choice; the whole row selects it. */
@Composable
private fun ViewOption(
    title: String,
    body: String,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    SettingRow(
        title = title,
        body = body,
        icon = icon,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
    }
}

/**
 * A settings row: icon, title, an explanation that wraps, and a control.
 * The caller's [modifier] carries what tapping the row does, so the row is
 * one touch target whatever the control.
 */
@Composable
private fun SettingRow(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
            .padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = DiligentTheme.colors.onSurfaceVariant,
            modifier = Modifier.size(DiligentTheme.sizes.icon),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = DiligentTheme.type.material.bodyLarge, color = DiligentTheme.colors.onSurface)
            Text(body, style = DiligentTheme.type.material.bodySmall, color = DiligentTheme.colors.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun TrailingIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        tint = DiligentTheme.colors.onSurfaceVariant,
        modifier = Modifier.size(DiligentTheme.sizes.iconSmall),
    )
}

/** A line of explanation under a group, lined up with the row titles. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.padding(
            start = DiligentTheme.spacing.m + DiligentTheme.sizes.icon + DiligentTheme.spacing.m,
            end = DiligentTheme.spacing.m,
            bottom = DiligentTheme.spacing.s,
        ),
        style = DiligentTheme.type.material.bodySmall,
        color = DiligentTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = DiligentTheme.spacing.s),
        thickness = DiligentTheme.sizes.divider,
        color = DiligentTheme.colors.outlineVariant,
    )
}

@Preview(name = "Settings, light", widthDp = 360, heightDp = 760)
@Composable
private fun SettingsLightPreview() = DiligentTheme(darkTheme = false) {
    SettingsScreen(MobileShell(AppView.Mobile), PreviewActions)
}

@Preview(name = "Settings, dark", widthDp = 360, heightDp = 760)
@Composable
private fun SettingsDarkPreview() = DiligentTheme(darkTheme = true) {
    SettingsScreen(MobileShell(AppView.Mobile).apply { suggestDesktop = false }, PreviewActions)
}

@Preview(name = "Settings, landscape", widthDp = 760, heightDp = 360)
@Composable
private fun SettingsLandscapePreview() = DiligentTheme(darkTheme = false) {
    SettingsScreen(MobileShell(AppView.Mobile), PreviewActions)
}
