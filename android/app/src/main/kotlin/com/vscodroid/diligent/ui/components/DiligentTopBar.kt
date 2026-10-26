package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** One entry of the top bar's menu. */
data class MenuAction(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/** What the top bar says about the project's run, when anything runs. */
data class TopBarStatus(val label: String, val tone: StatusTone)

/**
 * Mobile view's top bar: the open project's name, its run status, and a
 * menu. The activity hides it while the keyboard is up, so the editor keeps
 * the height.
 */
@Composable
fun DiligentTopBar(
    projectName: String?,
    status: TopBarStatus?,
    menu: List<MenuAction>,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A minimum, not a height: at a large font size the name and the
            // status need more than 56 dp, and a fixed height cut the status off.
            .heightIn(min = DiligentTheme.sizes.topBar)
            .background(DiligentTheme.colors.surface)
            .padding(start = DiligentTheme.spacing.m, end = DiligentTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = projectName ?: stringResource(R.string.diligent_no_project),
                modifier = Modifier.semantics { heading() },
                style = DiligentTheme.type.material.titleMedium,
                color = if (projectName != null) DiligentTheme.colors.onSurface
                else DiligentTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) StatusLabel(status.label, status.tone)
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(DiligentTheme.sizes.touchTarget),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.diligent_menu),
                    tint = DiligentTheme.colors.onSurface,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menu.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        leadingIcon = action.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
                        onClick = {
                            menuOpen = false
                            action.onClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBarSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
        DiligentTopBar(
            projectName = "my-next-app",
            status = TopBarStatus("Running on :3000", StatusTone.Running),
            menu = emptyList(),
        )
        DiligentTopBar(projectName = null, status = null, menu = emptyList())
    }
}

@Preview(name = "Top bar, light")
@Composable
private fun TopBarLightPreview() = DiligentTheme(darkTheme = false) { TopBarSamples() }

@Preview(name = "Top bar, dark")
@Composable
private fun TopBarDarkPreview() = DiligentTheme(darkTheme = true) { TopBarSamples() }
