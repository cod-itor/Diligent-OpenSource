package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Css
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Html
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.files.FileEntry
import com.vscodroid.diligent.files.FileKind
import com.vscodroid.diligent.files.fileKind
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * One row of the Files tab's tree: indented by [depth], a chevron for a
 * folder, an icon for what the file is, its name, and a button for its actions.
 *
 * The whole row is the touch target and the indentation sits inside it, so a
 * deep file is as easy to hit as a top-level one. A long press and the trailing
 * button open the same actions; the button is there because a long press is
 * invisible until someone already knows about it.
 *
 * Dot files and `.git` or `node_modules` are dimmed rather than hidden: a
 * phone has no other way to reach `.env` or `.gitignore`, but they should not
 * compete with the source for attention.
 */
@Composable
fun FileRow(
    entry: FileEntry,
    depth: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clickLabel = when {
        !entry.isDirectory -> stringResource(R.string.diligent_files_open_in_editor)
        expanded -> stringResource(R.string.diligent_files_close_folder)
        else -> stringResource(R.string.diligent_files_open_folder)
    }
    val subtitle = when {
        entry.leavesProject -> stringResource(R.string.diligent_files_link_outside)
        entry.isHeavy && entry.name == ".git" -> stringResource(R.string.diligent_files_heavy_git)
        entry.isHeavy -> stringResource(R.string.diligent_files_heavy_modules)
        else -> null
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = clickLabel,
                onLongClickLabel = stringResource(R.string.diligent_files_show_actions),
                onLongClick = onActions,
                onClick = onClick,
            )
            .padding(start = DiligentTheme.spacing.s + DiligentTheme.spacing.m * depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(DiligentTheme.sizes.iconSmall), contentAlignment = Alignment.Center) {
            if (entry.isDirectory) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = DiligentTheme.colors.onSurfaceVariant,
                )
            }
        }
        ListRow(
            title = entry.name,
            icon = iconFor(entry, expanded),
            modifier = Modifier.weight(1f),
            subtitle = subtitle,
            // ListRow is given no click of its own, so this only dims it.
            enabled = !(entry.isHidden || entry.isHeavy || entry.leavesProject),
            trailing = {
                IconButton(onClick = onActions) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.diligent_files_more_actions, entry.name),
                        tint = DiligentTheme.colors.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/** The icon for [entry]: an open or closed folder, a link, or what its name says the file is. */
fun iconFor(entry: FileEntry, expanded: Boolean = false): ImageVector = when {
    entry.leavesProject -> Icons.Outlined.Link
    entry.isDirectory -> if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder
    else -> iconFor(fileKind(entry.name))
}

private fun iconFor(kind: FileKind): ImageVector = when (kind) {
    FileKind.Folder -> Icons.Outlined.Folder
    FileKind.Code -> Icons.Outlined.Code
    FileKind.Script -> Icons.Outlined.Terminal
    FileKind.Web -> Icons.Outlined.Html
    FileKind.Style -> Icons.Outlined.Css
    FileKind.Data -> Icons.Outlined.DataObject
    FileKind.Config -> Icons.Outlined.Settings
    FileKind.Text -> Icons.Outlined.Description
    FileKind.Image -> Icons.Outlined.Image
    FileKind.Archive -> Icons.Outlined.FolderZip
    FileKind.Lock -> Icons.Outlined.Lock
    FileKind.Git -> Icons.Outlined.AccountTree
    FileKind.Other -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun FileRowSamples() {
    Surface(color = DiligentTheme.colors.surface) {
        Column {
            FileRow(FileEntry("/p/.git", ".git", isDirectory = true), 0, expanded = false, onClick = {}, onActions = {})
            FileRow(FileEntry("/p/src", "src", isDirectory = true), 0, expanded = true, onClick = {}, onActions = {})
            FileRow(FileEntry("/p/src/app", "app", isDirectory = true), 1, expanded = false, onClick = {}, onActions = {})
            FileRow(FileEntry("/p/src/index.ts", "index.ts", isDirectory = false), 1, expanded = false, onClick = {}, onActions = {})
            FileRow(FileEntry("/p/.env", ".env", isDirectory = false), 0, expanded = false, onClick = {}, onActions = {})
            FileRow(
                FileEntry("/p/sdcard", "sdcard", isDirectory = false, isLink = true, leavesProject = true),
                0, expanded = false, onClick = {}, onActions = {},
            )
            FileRow(FileEntry("/p/README.md", "README.md", isDirectory = false), 0, expanded = false, onClick = {}, onActions = {})
        }
    }
}

@Preview(name = "File rows, light", widthDp = 360)
@Composable
private fun FileRowsLightPreview() = DiligentTheme(darkTheme = false) { FileRowSamples() }

@Preview(name = "File rows, dark", widthDp = 360)
@Composable
private fun FileRowsDarkPreview() = DiligentTheme(darkTheme = true) { FileRowSamples() }
