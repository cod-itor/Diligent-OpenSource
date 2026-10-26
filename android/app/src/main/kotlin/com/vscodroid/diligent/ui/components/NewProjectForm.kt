package com.vscodroid.diligent.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.projects.NameProblem
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * Asks for a new project's name. The name is checked as it is typed, but an
 * empty field only complains once Create is pressed: a red "give it a name"
 * on a field nobody has touched yet reads as a telling-off.
 */
@Composable
fun NewProjectForm(
    name: String,
    onNameChange: (String) -> Unit,
    problem: NameProblem?,
    showEmptyProblem: Boolean,
    createFailed: Boolean,
    busy: Boolean,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(focus) { focus.requestFocus() }
    val shown = problem?.takeIf { it != NameProblem.Empty || showEmptyProblem }
    val error = when {
        shown != null -> stringResource(shown.messageRes())
        createFailed -> stringResource(R.string.diligent_projects_create_failed)
        else -> null
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DiligentTheme.spacing.l)
            .padding(bottom = DiligentTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        Text(
            stringResource(R.string.diligent_projects_new_title),
            style = DiligentTheme.type.material.titleLarge,
            color = DiligentTheme.colors.onSurface,
        )
        Text(
            stringResource(R.string.diligent_projects_new_body),
            style = DiligentTheme.type.material.bodyMedium,
            color = DiligentTheme.colors.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            label = { Text(stringResource(R.string.diligent_projects_name)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCreate() }),
        )
        SheetButtons(
            confirm = stringResource(R.string.diligent_projects_create),
            confirmEnabled = !busy && (problem == null || problem == NameProblem.Empty),
            onConfirm = onCreate,
            onCancel = onCancel,
        )
    }
}

/** Cancel and a main action, at the bottom right of a sheet, both a full thumb's height. */
@Composable
fun SheetButtons(
    confirm: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancel: String = stringResource(R.string.diligent_projects_cancel),
    showCancel: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(cancel)
            }
        }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget),
        ) {
            Text(confirm)
        }
    }
}

@StringRes
fun NameProblem.messageRes(): Int = when (this) {
    NameProblem.Empty -> R.string.diligent_projects_name_empty
    NameProblem.Slash -> R.string.diligent_projects_name_slash
    NameProblem.Reserved -> R.string.diligent_projects_name_reserved
    NameProblem.TooLong -> R.string.diligent_projects_name_too_long
    NameProblem.Exists -> R.string.diligent_projects_name_exists
}

@Composable
private fun FormSample(name: String, problem: NameProblem?) {
    Surface(color = DiligentTheme.colors.scheme.surfaceContainerLow) {
        NewProjectForm(
            name = name, onNameChange = {}, problem = problem, showEmptyProblem = false,
            createFailed = false, busy = false, onCreate = {}, onCancel = {},
            modifier = Modifier.padding(top = DiligentTheme.spacing.l), autoFocus = false,
        )
    }
}

@Preview(name = "New project, light", widthDp = 360)
@Composable
private fun NewProjectLightPreview() = DiligentTheme(darkTheme = false) { FormSample("my-site", null) }

@Preview(name = "New project, name taken, dark", widthDp = 360)
@Composable
private fun NewProjectDarkPreview() = DiligentTheme(darkTheme = true) { FormSample("shop", NameProblem.Exists) }
