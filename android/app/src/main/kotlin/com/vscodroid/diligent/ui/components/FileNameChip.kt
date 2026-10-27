package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.ActiveFile
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * The Editor tab's top strip: the active file's name in a small rounded box,
 * with a dot while it has unsaved changes, and nothing else, so the code
 * below gets the height. The strip is the editor's colour (the Diligent Mobile
 * themes use the same surface), so the chip floats on one surface with no seam.
 *
 * A tap asks for the all-files switcher ([onOpenSwitcher]). A long press opens
 * [menu], the project menu the other tabs' top bar has behind its overflow
 * button: kept here so nothing is lost on the Editor tab without adding a
 * visible button, and each of its actions is also reachable from More.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileNameChipBar(
    file: ActiveFile?,
    onOpenSwitcher: () -> Unit,
    menu: List<MenuAction>,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = file?.name ?: stringResource(R.string.diligent_file_none)
    val spoken = if (file?.dirty == true) stringResource(R.string.diligent_file_unsaved, name) else name
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget)
            .background(DiligentTheme.colors.surface)
            .padding(horizontal = DiligentTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .heightIn(min = DiligentTheme.sizes.touchTarget)
                    .combinedClickable(
                        onClickLabel = stringResource(R.string.diligent_file_show_all),
                        onLongClickLabel = stringResource(R.string.diligent_file_menu),
                        onLongClick = { menuOpen = true },
                        onClick = onOpenSwitcher,
                    )
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .height(DiligentTheme.sizes.chip)
                        .widthIn(max = DiligentTheme.sizes.sheetMaxWidth)
                        .clip(DiligentTheme.shapes.pill)
                        .background(DiligentTheme.colors.surfaceContainerHigh)
                        .padding(horizontal = DiligentTheme.spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
                ) {
                    if (file?.dirty == true) {
                        Box(
                            Modifier
                                .size(DiligentTheme.sizes.statusDot)
                                .background(DiligentTheme.colors.primary, CircleShape),
                        )
                    }
                    Text(
                        text = name,
                        style = DiligentTheme.type.codeSmall,
                        color = if (file != null) DiligentTheme.colors.onSurface else DiligentTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
private fun ChipSamples() {
    Column(Modifier.background(DiligentTheme.colors.surface)) {
        FileNameChipBar(ActiveFile("/p/src/App.tsx", "App.tsx", dirty = false), onOpenSwitcher = {}, menu = emptyList())
        FileNameChipBar(ActiveFile("/p/main.py", "main.py", dirty = true), onOpenSwitcher = {}, menu = emptyList())
        FileNameChipBar(
            ActiveFile("/p/a-very-long-file-name-that-does-not-fit-on-a-phone.component.spec.ts",
                "a-very-long-file-name-that-does-not-fit-on-a-phone.component.spec.ts", dirty = true),
            onOpenSwitcher = {}, menu = emptyList(),
        )
        FileNameChipBar(null, onOpenSwitcher = {}, menu = emptyList())
    }
}

@Preview(name = "File name chip, light", widthDp = 360)
@Composable
private fun FileNameChipLightPreview() = DiligentTheme(darkTheme = false) { ChipSamples() }

@Preview(name = "File name chip, dark", widthDp = 360)
@Composable
private fun FileNameChipDarkPreview() = DiligentTheme(darkTheme = true) { ChipSamples() }
