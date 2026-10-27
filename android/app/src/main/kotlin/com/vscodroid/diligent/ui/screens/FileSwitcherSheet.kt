package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.ui.components.AllFilesContent
import com.vscodroid.diligent.ui.components.AllFilesList
import com.vscodroid.diligent.ui.components.IndexLoad
import com.vscodroid.diligent.ui.components.previewIndex
import com.vscodroid.diligent.ui.components.previewRecent
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * The file switcher: every file of the open project in a full-height sheet,
 * the search focused and the keyboard up, so a file is a few letters and one
 * tap away from wherever Mobile view is, the editor included.
 *
 * Opens while [MobileShell.fileSwitcherOpen] is true, and clears it however
 * it closes: a file tapped, the close button, Back, or a swipe down.
 */
@Composable
fun FileSwitcherSheet(shell: MobileShell, actions: MobileActions) {
    val folder = shell.folder
    DiligentSheet(onDismiss = { shell.fileSwitcherOpen = false }) { hide ->
        FileSwitcherContent(
            projectName = shell.projectName,
            onClose = { hide {} },
            onOpenProjects = { hide { actions.openTab(MobileTab.Projects) } },
            list = if (folder == null) null else { modifier ->
                AllFilesList(
                    root = folder,
                    onOpen = { file -> hide { actions.openFile(file.path) } },
                    modifier = modifier,
                    autoFocus = true,
                )
            },
        )
    }
}

/** The sheet's inside, with the list as a slot, so previews need no file system. Null [list]: no project. */
@Composable
internal fun FileSwitcherContent(
    projectName: String?,
    onClose: () -> Unit,
    onOpenProjects: () -> Unit,
    list: (@Composable (Modifier) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = DiligentTheme.spacing.m, end = DiligentTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.diligent_files_switcher_title),
                    modifier = Modifier.semantics { heading() },
                    style = DiligentTheme.type.material.titleLarge,
                    color = DiligentTheme.colors.onSurface,
                )
                if (projectName != null) {
                    Text(
                        projectName,
                        style = DiligentTheme.type.material.bodyMedium,
                        color = DiligentTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.diligent_files_switcher_close))
            }
        }
        if (list != null) {
            list(Modifier.weight(1f))
        } else {
            NoProjectNote(onOpenProjects, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NoProjectNote(onOpenProjects: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.diligent_files_no_project_title),
            style = DiligentTheme.type.material.titleMedium,
            color = DiligentTheme.colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.diligent_files_switcher_no_project_body),
            style = DiligentTheme.type.material.bodyMedium,
            color = DiligentTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onOpenProjects,
            modifier = Modifier
                .widthIn(max = DiligentTheme.sizes.sheetMaxWidth)
                .fillMaxWidth()
                .heightIn(min = DiligentTheme.sizes.touchTarget),
        ) { Text(stringResource(R.string.diligent_files_go_to_projects)) }
    }
}

// -- Previews --

@Composable
private fun SwitcherSample(withProject: Boolean = true) {
    Surface(color = DiligentTheme.colors.scheme.surfaceContainerLow) {
        FileSwitcherContent(
            projectName = if (withProject) "shop" else null,
            onClose = {},
            onOpenProjects = {},
            list = if (!withProject) null else { modifier ->
                AllFilesContent(
                    load = IndexLoad.Ready(previewIndex()),
                    query = "",
                    onQuery = {},
                    recent = previewRecent,
                    onOpen = {},
                    modifier = modifier,
                )
            },
        )
    }
}

@Preview(name = "File switcher, light", widthDp = 360, heightDp = 720)
@Composable
private fun FileSwitcherLightPreview() = DiligentTheme(darkTheme = false) { SwitcherSample() }

@Preview(name = "File switcher, dark", widthDp = 360, heightDp = 720)
@Composable
private fun FileSwitcherDarkPreview() = DiligentTheme(darkTheme = true) { SwitcherSample() }

@Preview(name = "File switcher, large font", widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun FileSwitcherLargeFontPreview() = DiligentTheme(darkTheme = false) { SwitcherSample() }

@Preview(name = "File switcher, no project, light", widthDp = 360, heightDp = 640)
@Composable
private fun FileSwitcherNoProjectLightPreview() = DiligentTheme(darkTheme = false) { SwitcherSample(withProject = false) }

@Preview(name = "File switcher, no project, dark, large font", widthDp = 360, heightDp = 640, fontScale = 2f)
@Composable
private fun FileSwitcherNoProjectDarkPreview() = DiligentTheme(darkTheme = true) { SwitcherSample(withProject = false) }
