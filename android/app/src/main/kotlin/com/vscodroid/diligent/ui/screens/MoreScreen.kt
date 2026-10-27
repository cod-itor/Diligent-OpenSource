package com.vscodroid.diligent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.BuildConfig
import com.vscodroid.R
import com.vscodroid.diligent.AppView
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.ui.components.ComingSoonTag
import com.vscodroid.diligent.guide.Tip
import com.vscodroid.diligent.ui.components.ListRow
import com.vscodroid.diligent.ui.components.TipBanner
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.components.SectionHeader
import com.vscodroid.diligent.ui.navigation.MoreDetail
import com.vscodroid.diligent.ui.navigation.MoreItem
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * Mobile view's More tab: a list of everything that is not one of the four
 * main tabs, and the screens it opens (Run, Setup, Laptop, AI, Devices, Share, Health, Settings) in its own place.
 * In beginner mode the advanced ones sit one level down, under Advanced
 * ([MoreItem.listed]), and each screen starts with its tip.
 *
 * Which entry is open is the tab's own small back stack, one level deep:
 * the list, or one detail with a back arrow. It is saved across recreation
 * (rotation) but not across leaving the tab, so the More tab always opens on
 * its list, where a user expects a tab to start. System Back on a detail goes
 * to the list first; the [BackHandler] is registered after the activity's
 * own callback, so it is asked first, and it is off on the list so Back
 * there still goes to the previous tab.
 *
 * Entries of later phases are shown greyed out with "Coming soon" and do not
 * respond to a tap: the tag already says why, and a tap that opens an empty
 * screen would teach a beginner that the app is broken.
 *
 * The action bar's Run, pressed before the project has a run profile, sends
 * the user here with [MobileShell.runRequested] set: the tab opens on Run,
 * which offers the detected profile, and clears the request.
 */
@Composable
fun MoreScreen(shell: MobileShell, actions: MobileActions, modifier: Modifier = Modifier) {
    var detail by rememberSaveable { mutableStateOf<MoreDetail?>(null) }
    // Opened from the Advanced list (beginner mode): back returns there, not to the top.
    var fromAdvanced by rememberSaveable { mutableStateOf(false) }
    val back = {
        if (fromAdvanced && detail != MoreDetail.Advanced) {
            detail = MoreDetail.Advanced
        } else {
            detail = null
        }
        fromAdvanced = false
    }
    BackHandler(enabled = detail != null, onBack = back)
    LaunchedEffect(shell.runRequested) {
        if (shell.runRequested) {
            detail = MoreDetail.Run
            shell.runRequested = false
        }
    }
    LaunchedEffect(shell.shareRequested) {
        if (shell.shareRequested) {
            detail = MoreDetail.Share
            shell.shareRequested = false
        }
    }
    LaunchedEffect(shell.devicesRequested) {
        if (shell.devicesRequested) {
            detail = MoreDetail.Devices
            shell.devicesRequested = false
        }
    }
    LaunchedEffect(shell.healthRequested) {
        if (shell.healthRequested) {
            detail = MoreDetail.Health
            shell.healthRequested = false
        }
    }
    MoreContent(
        shell = shell,
        actions = actions,
        detail = detail,
        onOpen = {
            fromAdvanced = detail == MoreDetail.Advanced
            detail = it
        },
        onBack = back,
        modifier = modifier,
    )
}

/** The More tab for a given [detail], with the state held by the caller. */
@Composable
private fun MoreContent(
    shell: MobileShell,
    actions: MobileActions,
    detail: MoreDetail?,
    onOpen: (MoreDetail) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        if (detail == null) {
            Column(Modifier.fillMaxSize()) {
                TipBanner(Tip.of(MobileTab.More), shell, actions)
                MoreList(MoreItem.listed(shell.beginnerMode), onOpen, Modifier.weight(1f), footer = true)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                DetailHeader(stringResource(MoreItem.of(detail).title), onBack)
                TipBanner(Tip.of(detail), shell, actions)
                Box(Modifier.weight(1f)) {
                    when (detail) {
                        MoreDetail.Run -> RunScreen(shell, actions, onClose = onBack)
                        MoreDetail.Setup -> SetupScreen(shell, actions)
                        MoreDetail.Laptop -> LaptopScreen()
                        MoreDetail.AI -> AiScreen(shell, actions)
                        MoreDetail.Devices -> DevicesScreen(shell, actions)
                        MoreDetail.Share -> ShareScreen(shell, actions)
                        MoreDetail.Health -> HealthScreen(onOpenRun = { onOpen(MoreDetail.Run) })
                        MoreDetail.Advanced -> MoreList(MoreItem.advancedItems, onOpen, footer = false)
                        MoreDetail.Settings -> SettingsScreen(shell, actions)
                    }
                }
            }
        }
    }
}

/** [items] under their section headings; the top list also ends with the version [footer]. */
@Composable
private fun MoreList(items: List<MoreItem>, onOpen: (MoreDetail) -> Unit, modifier: Modifier = Modifier, footer: Boolean) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        var section: Int? = null
        for (item in items) {
            if (item.section != section) {
                section = item.section
                SectionHeader(stringResource(item.section))
            }
            val opens = item.detail
            ListRow(
                title = stringResource(item.title),
                icon = item.icon,
                subtitle = stringResource(item.subtitle),
                enabled = opens != null,
                onClick = opens?.let { { onOpen(it) } },
                trailing = {
                    if (opens == null) {
                        ComingSoonTag()
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = DiligentTheme.colors.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        if (footer) {
            Text(
                stringResource(R.string.diligent_more_footer, BuildConfig.VERSION_NAME),
                modifier = Modifier.fillMaxWidth().padding(DiligentTheme.spacing.l),
                style = DiligentTheme.type.material.bodySmall,
                color = DiligentTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A detail's own small bar: back to the list, and what this screen is. Also Health's, over Desktop view. */
@Composable
internal fun DetailHeader(title: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DiligentTheme.sizes.topBar)
                .background(DiligentTheme.colors.surface)
                .padding(horizontal = DiligentTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(DiligentTheme.sizes.touchTarget)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.diligent_more_back),
                    tint = DiligentTheme.colors.onSurface,
                )
            }
            Text(
                title,
                modifier = Modifier.weight(1f).semantics { heading() },
                style = DiligentTheme.type.material.titleMedium,
                color = DiligentTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(thickness = DiligentTheme.sizes.divider, color = DiligentTheme.colors.outlineVariant)
    }
}

// The words and pictures of each entry, kept here so MoreItem stays plain Kotlin.

@get:StringRes
private val MoreItem.title: Int
    get() = when (this) {
        MoreItem.Run -> R.string.diligent_more_run
        MoreItem.Setup -> R.string.diligent_more_setup
        MoreItem.Laptop -> R.string.diligent_more_laptop
        MoreItem.AI -> R.string.diligent_more_ai
        MoreItem.Devices -> R.string.diligent_more_devices
        MoreItem.Share -> R.string.diligent_more_share
        MoreItem.Health -> R.string.diligent_more_health
        MoreItem.Advanced -> R.string.diligent_more_advanced
        MoreItem.Settings -> R.string.diligent_more_settings
    }

@get:StringRes
private val MoreItem.subtitle: Int
    get() = when (this) {
        MoreItem.Run -> R.string.diligent_more_run_body
        MoreItem.Setup -> R.string.diligent_more_setup_body
        MoreItem.Laptop -> R.string.diligent_more_laptop_body
        MoreItem.AI -> R.string.diligent_more_ai_body
        MoreItem.Devices -> R.string.diligent_more_devices_body
        MoreItem.Share -> R.string.diligent_more_share_body
        MoreItem.Health -> R.string.diligent_more_health_body
        MoreItem.Advanced -> R.string.diligent_more_advanced_body
        MoreItem.Settings -> R.string.diligent_more_settings_body
    }

/** The heading an entry sits under: the tools, then the app itself. */
@get:StringRes
private val MoreItem.section: Int
    get() = if (this == MoreItem.Settings) R.string.diligent_more_section_app else R.string.diligent_more_section_tools

private val MoreItem.icon: ImageVector
    get() = when (this) {
        MoreItem.Run -> Icons.Outlined.PlayArrow
        MoreItem.Setup -> Icons.Outlined.Build
        MoreItem.Laptop -> Icons.Outlined.Laptop
        MoreItem.AI -> Icons.Outlined.AutoAwesome
        MoreItem.Devices -> Icons.Outlined.Devices
        MoreItem.Share -> Icons.Outlined.Share
        MoreItem.Health -> Icons.Outlined.MonitorHeart
        MoreItem.Advanced -> Icons.Outlined.Tune
        MoreItem.Settings -> Icons.Outlined.Settings
    }

@Preview(name = "More, light", widthDp = 360, heightDp = 640)
@Composable
private fun MoreLightPreview() = DiligentTheme(darkTheme = false) {
    MoreScreen(MobileShell(AppView.Mobile), PreviewActions)
}

@Preview(name = "More, dark", widthDp = 360, heightDp = 640)
@Composable
private fun MoreDarkPreview() = DiligentTheme(darkTheme = true) {
    MoreScreen(MobileShell(AppView.Mobile), PreviewActions)
}

@Preview(name = "More, landscape", widthDp = 760, heightDp = 360)
@Composable
private fun MoreLandscapePreview() = DiligentTheme(darkTheme = false) {
    MoreScreen(MobileShell(AppView.Mobile), PreviewActions)
}

@Preview(name = "More, beginner mode, dark", widthDp = 360, heightDp = 640)
@Composable
private fun MoreBeginnerDarkPreview() = DiligentTheme(darkTheme = true) {
    MoreScreen(MobileShell(AppView.Mobile).apply { beginnerMode = true }, PreviewActions)
}

@Preview(name = "More, Advanced open, light", widthDp = 360, heightDp = 640)
@Composable
private fun MoreAdvancedLightPreview() = DiligentTheme(darkTheme = false) {
    MoreContent(MobileShell(AppView.Mobile).apply { beginnerMode = true }, PreviewActions, MoreDetail.Advanced, onOpen = {}, onBack = {})
}

@Preview(name = "More, Settings open, dark", widthDp = 360, heightDp = 640)
@Composable
private fun MoreSettingsDarkPreview() = DiligentTheme(darkTheme = true) {
    MoreContent(MobileShell(AppView.Mobile), PreviewActions, MoreDetail.Settings, onOpen = {}, onBack = {})
}

@Preview(name = "More, Run open, light", widthDp = 360, heightDp = 640)
@Composable
private fun MoreRunLightPreview() = DiligentTheme(darkTheme = false) {
    MoreContent(MobileShell(AppView.Mobile), PreviewActions, MoreDetail.Run, onOpen = {}, onBack = {})
}
