package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * A full-width tappable row: an icon, a title, an optional subtitle and
 * whatever trails it. The base of the file row and the More list.
 */
@Composable
fun ListRow(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.tappable(enabled, onClick ?: {}, onLongClick)
    } else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
            .then(clickModifier)
            .padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        val tint = if (enabled) DiligentTheme.colors.onSurfaceVariant
        else DiligentTheme.colors.outline
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(DiligentTheme.sizes.icon))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = DiligentTheme.type.material.bodyLarge,
                color = if (enabled) DiligentTheme.colors.onSurface else DiligentTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DiligentTheme.type.material.bodySmall,
                    color = DiligentTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** A tap, and a long press when one is given. */
private fun Modifier.tappable(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = if (onLongClick == null) {
    clickable(enabled = enabled, onClick = onClick)
} else {
    combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
}

/** A small heading above a group of rows. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(
            start = DiligentTheme.spacing.m,
            end = DiligentTheme.spacing.m,
            top = DiligentTheme.spacing.l,
            bottom = DiligentTheme.spacing.s,
        ),
        style = DiligentTheme.type.material.titleSmall,
        color = DiligentTheme.colors.primary,
    )
}

/** The trailing tag on an item whose phase is not built yet. */
@Composable
fun ComingSoonTag() {
    Surface(
        color = DiligentTheme.colors.surfaceContainerHigh,
        shape = DiligentTheme.shapes.pill,
    ) {
        Text(
            stringResource(R.string.diligent_coming_soon),
            modifier = Modifier.padding(horizontal = DiligentTheme.spacing.s, vertical = DiligentTheme.spacing.xs),
            style = DiligentTheme.type.material.labelSmall,
            color = DiligentTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowSamples() {
    Surface(color = DiligentTheme.colors.surface) {
        Column {
            SectionHeader("Section")
            ListRow("src", Icons.Outlined.Folder, subtitle = "12 items", onClick = {})
            ListRow("Settings", Icons.Outlined.Settings, onClick = {})
            ListRow("Devices", Icons.Outlined.Settings, enabled = false, trailing = { ComingSoonTag() })
        }
    }
}

@Preview(name = "Rows, light")
@Composable
private fun RowsLightPreview() = DiligentTheme(darkTheme = false) { RowSamples() }

@Preview(name = "Rows, dark")
@Composable
private fun RowsDarkPreview() = DiligentTheme(darkTheme = true) { RowSamples() }
