package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.projects.GitProgress
import com.vscodroid.diligent.projects.NameProblem
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** Where Clone from Git is. */
sealed interface CloneStage {
    /** Typing the address. */
    data object Input : CloneStage

    /** Git is running; [lines] is the end of what it has said so far. */
    data class Running(val lines: List<String>) : CloneStage

    /** Git failed; [lines] is the end of what it said, the reason among them. */
    data class Failed(val lines: List<String>) : CloneStage
}

/**
 * Clone from Git's sheet: the address and folder name, then git's progress,
 * then, if it failed, why.
 *
 * The address field is built for pasting: a Paste button beside it, a URL
 * keyboard, no capitals and no autocorrect (both quietly break addresses).
 * A wrong-looking address is only flagged once something has been typed.
 */
@Composable
fun CloneForm(
    url: String,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    urlValid: Boolean,
    folderName: String,
    onFolderNameChange: (String) -> Unit,
    nameProblem: NameProblem?,
    stage: CloneStage,
    onClone: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val spacing = DiligentTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.l)
            .padding(bottom = spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Text(
            stringResource(R.string.diligent_projects_clone_title),
            style = DiligentTheme.type.material.titleLarge,
            color = DiligentTheme.colors.onSurface,
        )
        when (stage) {
            CloneStage.Input -> CloneInput(
                url, onUrlChange, onPaste, urlValid, folderName, onFolderNameChange, nameProblem,
                onClone, onCancel, autoFocus,
            )
            is CloneStage.Running -> CloneRunning(folderName, stage.lines, onStop)
            is CloneStage.Failed -> CloneFailed(stage.lines, onEdit = onEdit, onRetry = onClone)
        }
    }
}

@Composable
private fun CloneInput(
    url: String,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    urlValid: Boolean,
    folderName: String,
    onFolderNameChange: (String) -> Unit,
    nameProblem: NameProblem?,
    onClone: () -> Unit,
    onCancel: () -> Unit,
    autoFocus: Boolean,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(focus) { focus.requestFocus() }
    val urlError = url.isNotBlank() && !urlValid
    val nameError = nameProblem?.takeIf { it != NameProblem.Empty || url.isNotBlank() }
    val ready = urlValid && nameProblem == null

    Text(
        stringResource(R.string.diligent_projects_clone_body),
        style = DiligentTheme.type.material.bodyMedium,
        color = DiligentTheme.colors.onSurfaceVariant,
    )
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
        label = { Text(stringResource(R.string.diligent_projects_clone_url)) },
        placeholder = { Text(stringResource(R.string.diligent_projects_clone_url_example), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = urlError,
        supportingText = if (urlError) {
            { Text(stringResource(R.string.diligent_projects_clone_url_invalid)) }
        } else null,
        trailingIcon = {
            IconButton(onClick = onPaste) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.diligent_projects_clone_paste))
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
    )
    OutlinedTextField(
        value = folderName,
        onValueChange = onFolderNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.diligent_projects_clone_folder)) },
        singleLine = true,
        isError = nameError != null,
        supportingText = nameError?.let { { Text(stringResource(it.messageRes())) } },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { if (ready) onClone() }),
    )
    SheetButtons(
        confirm = stringResource(R.string.diligent_projects_clone_start),
        confirmEnabled = ready,
        onConfirm = onClone,
        onCancel = onCancel,
    )
}

@Composable
private fun CloneRunning(folderName: String, lines: List<String>, onStop: () -> Unit) {
    val progress = lines.asReversed().firstNotNullOfOrNull { GitProgress.of(it) }
    Text(
        stringResource(R.string.diligent_projects_cloning, folderName),
        style = DiligentTheme.type.material.bodyLarge,
        color = DiligentTheme.colors.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (progress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
    }
    Text(
        progress?.let { stringResource(R.string.diligent_projects_clone_progress, it.step, it.percent) } ?: stringResource(R.string.diligent_projects_clone_starting),
        style = DiligentTheme.type.material.labelLarge,
        color = DiligentTheme.colors.onSurfaceVariant,
    )
    OutputLines(lines.takeLast(VISIBLE_LINES), error = false)
    SheetButtons(
        confirm = stringResource(R.string.diligent_projects_clone_stop),
        confirmEnabled = true,
        onConfirm = onStop,
        onCancel = onStop,
        showCancel = false,
    )
}

@Composable
private fun CloneFailed(lines: List<String>, onEdit: () -> Unit, onRetry: () -> Unit) {
    Text(
        stringResource(R.string.diligent_projects_clone_failed),
        style = DiligentTheme.type.material.titleMedium,
        color = DiligentTheme.colors.error,
    )
    if (lines.isNotEmpty()) OutputLines(lines.takeLast(VISIBLE_LINES), error = true)
    Text(
        stringResource(R.string.diligent_projects_clone_failed_hint),
        style = DiligentTheme.type.material.bodyMedium,
        color = DiligentTheme.colors.onSurfaceVariant,
    )
    SheetButtons(
        confirm = stringResource(R.string.diligent_projects_clone_retry),
        confirmEnabled = true,
        onConfirm = onRetry,
        onCancel = onEdit,
        cancel = stringResource(R.string.diligent_projects_clone_edit),
    )
}

/** Git's own words, in the code font, as they would read in a terminal. */
@Composable
private fun OutputLines(lines: List<String>, error: Boolean) {
    if (lines.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DiligentTheme.shapes.small,
        color = if (error) DiligentTheme.colors.scheme.errorContainer else DiligentTheme.colors.surfaceContainerHigh,
        contentColor = if (error) DiligentTheme.colors.scheme.onErrorContainer else DiligentTheme.colors.onSurfaceVariant,
    ) {
        Text(
            lines.joinToString("\n"),
            modifier = Modifier.padding(DiligentTheme.spacing.s),
            style = DiligentTheme.type.codeSmall,
        )
    }
}

/** Enough of git's output to see what it is doing, or why it stopped, without a wall of text. */
private const val VISIBLE_LINES = 6

// -- Previews --

@Composable
private fun CloneSample(stage: CloneStage, url: String = "https://github.com/user/shop.git", valid: Boolean = true) {
    Surface(color = DiligentTheme.colors.scheme.surfaceContainerLow) {
        CloneForm(
            url = url, onUrlChange = {}, onPaste = {}, urlValid = valid,
            folderName = "shop", onFolderNameChange = {}, nameProblem = null,
            stage = stage, onClone = {}, onStop = {}, onEdit = {}, onCancel = {},
            modifier = Modifier.padding(top = DiligentTheme.spacing.l), autoFocus = false,
        )
    }
}

private val runningLines = listOf(
    "Cloning into 'shop'...",
    "remote: Enumerating objects: 1042, done.",
    "remote: Counting objects: 100% (1042/1042), done.",
    "Receiving objects:  45% (469/1042), 1.20 MiB | 600.00 KiB/s",
)

@Preview(name = "Clone, input, light", widthDp = 360)
@Composable
private fun CloneInputLightPreview() = DiligentTheme(darkTheme = false) { CloneSample(CloneStage.Input) }

@Preview(name = "Clone, bad address, dark", widthDp = 360)
@Composable
private fun CloneInvalidDarkPreview() = DiligentTheme(darkTheme = true) {
    CloneSample(CloneStage.Input, url = "github.com", valid = false)
}

@Preview(name = "Clone, running, light", widthDp = 360)
@Composable
private fun CloneRunningLightPreview() = DiligentTheme(darkTheme = false) { CloneSample(CloneStage.Running(runningLines)) }

@Preview(name = "Clone, running, dark", widthDp = 360)
@Composable
private fun CloneRunningDarkPreview() = DiligentTheme(darkTheme = true) { CloneSample(CloneStage.Running(runningLines)) }

@Preview(name = "Clone, failed, light", widthDp = 360)
@Composable
private fun CloneFailedLightPreview() = DiligentTheme(darkTheme = false) {
    CloneSample(CloneStage.Failed(listOf("Cloning into 'shop'...", "fatal: repository 'https://github.com/user/shop.git/' not found")))
}

@Preview(name = "Clone, failed, dark", widthDp = 360)
@Composable
private fun CloneFailedDarkPreview() = DiligentTheme(darkTheme = true) {
    CloneSample(CloneStage.Failed(listOf("fatal: could not read Username for 'https://github.com': terminal prompts disabled")))
}
