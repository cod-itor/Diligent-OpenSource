package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.files.FileEntry
import com.vscodroid.diligent.files.FileProblem
import com.vscodroid.diligent.ui.theme.DiligentTheme
import java.io.File

/** The sentence the Files tab shows for [problem]. */
@Composable
fun problemMessage(problem: FileProblem): String = stringResource(
    when (problem) {
        FileProblem.EmptyName -> R.string.diligent_files_problem_empty_name
        FileProblem.BadName -> R.string.diligent_files_problem_bad_name
        FileProblem.NameTaken -> R.string.diligent_files_problem_name_taken
        FileProblem.OutsideProject -> R.string.diligent_files_problem_outside
        FileProblem.Missing -> R.string.diligent_files_problem_missing
        FileProblem.NotAFolder -> R.string.diligent_files_problem_not_a_folder
        FileProblem.ProjectRoot -> R.string.diligent_files_problem_project_root
        FileProblem.IntoItself -> R.string.diligent_files_problem_into_itself
        FileProblem.AlreadyThere -> R.string.diligent_files_problem_already_there
        FileProblem.Failed -> R.string.diligent_files_problem_failed
    },
)

/**
 * Asks for a name: for a new file or folder, or a new name for one.
 *
 * [check] answers what is wrong with a name as it is typed, so a clash or a
 * slash shows before the button is pressed rather than after; [problem] is
 * what the file system said when [problemName] was tried anyway, shown until
 * the name is changed. A rename starts with
 * the part before the extension selected, which is the part usually changed.
 */
@Composable
fun NameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    check: (String) -> FileProblem? = { null },
    problem: FileProblem? = null,
    problemName: String? = null,
) {
    var value by remember {
        val stem = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
        mutableStateOf(TextFieldValue(initial, TextRange(0, stem)))
    }
    val typed = value.text.trim()
    // An empty field is not an error until something has been typed in it.
    val live = check(typed)?.takeUnless { it == FileProblem.EmptyName && value.text.isEmpty() }
    val shown = live ?: problem.takeIf { typed == problemName }
    val canConfirm = typed.isNotEmpty() && live == null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text(stringResource(R.string.diligent_files_name)) },
                singleLine = true,
                isError = shown != null,
                supportingText = shown?.let { { Text(problemMessage(it)) } },
                textStyle = DiligentTheme.type.code,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(typed) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(typed) }, enabled = canConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.diligent_files_cancel)) }
        },
        containerColor = DiligentTheme.colors.surfaceContainerHigh,
    )
}

/** Asks before deleting [name], and says so when a folder's contents go with it. */
@Composable
fun DeleteDialog(name: String, isFolder: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diligent_files_delete_title, name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Text(
                stringResource(
                    if (isFolder) R.string.diligent_files_delete_folder_body else R.string.diligent_files_delete_file_body,
                    name,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = DiligentTheme.colors.error),
            ) { Text(stringResource(R.string.diligent_files_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.diligent_files_cancel)) }
        },
        containerColor = DiligentTheme.colors.surfaceContainerHigh,
    )
}

/**
 * Picks the folder to move [item] into: a folder browser over the project,
 * one level at a time, starting where the item is.
 *
 * The item itself is left out of every listing, so a folder can never be
 * picked as its own destination or reached through to one of its own
 * folders; [com.vscodroid.diligent.files.ProjectFiles.move] refuses both
 * anyway. "Move here" is off in the folder the item is already in.
 */
@Composable
fun MoveDialog(
    item: FileEntry,
    root: String,
    rootLabel: String,
    list: suspend (String) -> List<FileEntry>?,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val from = File(item.path).parent ?: root
    var current by remember { mutableStateOf(from) }
    val folders by produceState<List<FileEntry>?>(null, current) {
        value = list(current)?.filter { it.isDirectory && !it.leavesProject && it.path != item.path }
    }
    val location = if (current == root) rootLabel
    else rootLabel + File.separator + current.removePrefix(root + File.separator)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diligent_files_move_title, item.name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(
                    location,
                    style = DiligentTheme.type.codeSmall,
                    color = DiligentTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
                HorizontalDivider(color = DiligentTheme.colors.outlineVariant)
                LazyColumn {
                    if (current != root) {
                        item(key = "..") {
                            ListRow(
                                stringResource(R.string.diligent_files_move_up),
                                Icons.Outlined.ArrowUpward,
                                onClick = { current = File(current).parent ?: root },
                            )
                        }
                    }
                    val shown = folders
                    if (shown != null && shown.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.diligent_files_move_no_folders),
                                style = DiligentTheme.type.material.bodyMedium,
                                color = DiligentTheme.colors.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(shown.orEmpty(), key = { it.path }) { folder ->
                        ListRow(folder.name, Icons.Outlined.Folder, onClick = { current = folder.path })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(current) }, enabled = current != from) {
                Text(stringResource(R.string.diligent_files_move_here))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.diligent_files_cancel)) }
        },
        containerColor = DiligentTheme.colors.surfaceContainerHigh,
    )
}

// -- Previews --
// A dialog draws in its own window, which a preview shows as the dialog alone.

@Preview(name = "Delete folder, light", widthDp = 360, heightDp = 400)
@Composable
private fun DeleteLightPreview() = DiligentTheme(darkTheme = false) {
    Surface { DeleteDialog("components", isFolder = true, onConfirm = {}, onDismiss = {}) }
}

@Preview(name = "Delete file, dark", widthDp = 360, heightDp = 400)
@Composable
private fun DeleteDarkPreview() = DiligentTheme(darkTheme = true) {
    Surface { DeleteDialog("page.tsx", isFolder = false, onConfirm = {}, onDismiss = {}) }
}

@Preview(name = "Rename, light", widthDp = 360, heightDp = 400)
@Composable
private fun RenameLightPreview() = DiligentTheme(darkTheme = false) {
    Surface {
        NameDialog("Rename page.tsx", "Rename", onConfirm = {}, onDismiss = {}, initial = "page.tsx")
    }
}

@Preview(name = "New file, name taken, dark", widthDp = 360, heightDp = 400)
@Composable
private fun NewFileDarkPreview() = DiligentTheme(darkTheme = true) {
    Surface {
        NameDialog("New file", "Create", onConfirm = {}, onDismiss = {}, initial = "index.ts", check = { FileProblem.NameTaken })
    }
}

private val previewFolders = listOf(
    FileEntry("/p/src/app", "app", isDirectory = true),
    FileEntry("/p/src/lib", "lib", isDirectory = true),
    FileEntry("/p/src/styles", "styles", isDirectory = true),
)

@Preview(name = "Move, light", widthDp = 360, heightDp = 500)
@Composable
private fun MoveLightPreview() = DiligentTheme(darkTheme = false) {
    Surface {
        MoveDialog(
            item = FileEntry("/p/src/index.ts", "index.ts", isDirectory = false),
            root = "/p", rootLabel = "hello-next", list = { previewFolders }, onMove = {}, onDismiss = {},
        )
    }
}

@Preview(name = "Move, dark", widthDp = 360, heightDp = 500)
@Composable
private fun MoveDarkPreview() = DiligentTheme(darkTheme = true) {
    Surface {
        MoveDialog(
            item = FileEntry("/p/src/index.ts", "index.ts", isDirectory = false),
            root = "/p", rootLabel = "hello-next", list = { previewFolders }, onMove = {}, onDismiss = {},
        )
    }
}
