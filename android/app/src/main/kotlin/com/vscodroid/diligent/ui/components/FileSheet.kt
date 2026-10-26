package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** One choice in a [FileSheet]. */
data class FileSheetAction(
    val label: String,
    val icon: ImageVector,
    /** Drawn in the error colour: the choice removes something. */
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A bottom sheet of actions on one file or folder, or of what can be made in
 * a folder. A sheet rather than a menu because it opens at the bottom, under
 * the thumb, wherever the row that opened it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSheet(
    title: String,
    subtitle: String?,
    actions: List<FileSheetAction>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = DiligentTheme.sizes.sheetMaxWidth,
        containerColor = DiligentTheme.colors.surfaceContainer,
    ) {
        FileSheetContent(title, subtitle, actions, onPicked = onDismiss)
    }
}

/** What a [FileSheet] holds; separate so previews can draw it without a window. */
@Composable
fun FileSheetContent(
    title: String,
    subtitle: String?,
    actions: List<FileSheetAction>,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.widthIn(max = DiligentTheme.sizes.sheetMaxWidth).padding(bottom = DiligentTheme.spacing.m)) {
        Column(Modifier.padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s)) {
            Text(
                title,
                style = DiligentTheme.type.material.titleMedium,
                color = DiligentTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DiligentTheme.type.codeSmall,
                    color = DiligentTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        HorizontalDivider(color = DiligentTheme.colors.outlineVariant)
        actions.forEach { action ->
            if (action.destructive) {
                // ListRow has no error colour, and a destructive choice should
                // look like one, so it gets a row of the same shape in red.
                DestructiveRow(action) {
                    onPicked()
                    action.onClick()
                }
            } else {
                ListRow(action.label, action.icon, onClick = {
                    onPicked()
                    action.onClick()
                })
            }
        }
    }
}

@Composable
private fun DestructiveRow(action: FileSheetAction, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
            .clickable(onClick = onClick)
            .padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        Icon(action.icon, contentDescription = null, tint = DiligentTheme.colors.error, modifier = Modifier.size(DiligentTheme.sizes.icon))
        Text(action.label, style = DiligentTheme.type.material.bodyLarge, color = DiligentTheme.colors.error)
    }
}

/** The actions of a file, or of a folder, which can also have things made in it. */
@Composable
fun fileActions(
    isFolder: Boolean,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
): List<FileSheetAction> = buildList {
    if (isFolder) addAll(newActions(onNewFile, onNewFolder))
    add(FileSheetAction(stringResource(R.string.diligent_files_rename), Icons.Outlined.DriveFileRenameOutline, onClick = onRename))
    add(FileSheetAction(stringResource(R.string.diligent_files_move), Icons.AutoMirrored.Outlined.DriveFileMove, onClick = onMove))
    add(FileSheetAction(stringResource(R.string.diligent_files_delete), Icons.Outlined.Delete, destructive = true, onClick = onDelete))
}

/** New file and New folder. */
@Composable
fun newActions(onNewFile: () -> Unit, onNewFolder: () -> Unit): List<FileSheetAction> = listOf(
    FileSheetAction(stringResource(R.string.diligent_files_new_file), Icons.AutoMirrored.Outlined.NoteAdd, onClick = onNewFile),
    FileSheetAction(stringResource(R.string.diligent_files_new_folder), Icons.Outlined.CreateNewFolder, onClick = onNewFolder),
)

@Composable
private fun SheetSample() {
    Surface(color = DiligentTheme.colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        FileSheetContent(
            title = "components",
            subtitle = "src/app/components",
            actions = fileActions(isFolder = true, {}, {}, {}, {}, {}),
            onPicked = {},
        )
    }
}

@Preview(name = "File actions, light", widthDp = 360)
@Composable
private fun FileSheetLightPreview() = DiligentTheme(darkTheme = false) { SheetSample() }

@Preview(name = "File actions, dark", widthDp = 360)
@Composable
private fun FileSheetDarkPreview() = DiligentTheme(darkTheme = true) { SheetSample() }
