package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import com.vscodroid.R
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** One button of the action bar. */
data class EditorAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    /** Drawn in the running colour: the Stop button while a task runs. */
    val emphasized: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The row under the editor in Mobile view: the editor's most used actions,
 * in thumb reach, each an icon over a short label so a beginner can read
 * what it does. Every button is a full touch target; the row shares its
 * width equally, so six fit on a 360 dp phone.
 */
@Composable
fun EditorActionBar(actions: List<EditorAction>, modifier: Modifier = Modifier) {
    Surface(color = DiligentTheme.colors.surfaceContainer, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(DiligentTheme.sizes.actionBar),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            actions.forEach { ActionButton(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ActionButton(action: EditorAction, modifier: Modifier) {
    val tint: Color = when {
        !action.enabled -> DiligentTheme.colors.outline
        action.emphasized -> DiligentTheme.colors.running
        else -> DiligentTheme.colors.onSurface
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(DiligentTheme.sizes.icon))
        Text(
            action.label,
            style = DiligentTheme.type.material.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The standard set, in the order the guide gives: Run or Stop, Preview, Save, Search, Commands, Undo. */
@Composable
fun standardEditorActions(
    running: Boolean,
    canPreview: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onSearch: () -> Unit,
    onCommands: () -> Unit,
    onUndo: () -> Unit,
): List<EditorAction> = listOf(
    if (running) EditorAction(stringResource(R.string.diligent_action_stop), Icons.Outlined.Stop, emphasized = true, onClick = onStop)
    else EditorAction(stringResource(R.string.diligent_action_run), Icons.Outlined.PlayArrow, onClick = onRun),
    EditorAction(stringResource(R.string.diligent_action_preview), Icons.Outlined.Visibility, enabled = canPreview, onClick = onPreview),
    EditorAction(stringResource(R.string.diligent_action_save), Icons.Outlined.Save, onClick = onSave),
    EditorAction(stringResource(R.string.diligent_action_search), Icons.Outlined.Search, onClick = onSearch),
    EditorAction(stringResource(R.string.diligent_action_commands), Icons.Outlined.Terminal, onClick = onCommands),
    EditorAction(stringResource(R.string.diligent_action_undo), Icons.AutoMirrored.Outlined.Undo, onClick = onUndo),
)

@Composable
private fun ActionBarSample(running: Boolean) {
    EditorActionBar(standardEditorActions(running, canPreview = running, {}, {}, {}, {}, {}, {}, {}))
}

@Preview(name = "Action bar, light", widthDp = 360)
@Composable
private fun ActionBarLightPreview() = DiligentTheme(darkTheme = false) { ActionBarSample(running = false) }

@Preview(name = "Action bar running, dark", widthDp = 360)
@Composable
private fun ActionBarDarkPreview() = DiligentTheme(darkTheme = true) { ActionBarSample(running = true) }
