package com.vscodroid.diligent.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.projects.ProjectFramework
import com.vscodroid.diligent.projects.ProjectKind
import com.vscodroid.diligent.projects.ProjectLanguage
import com.vscodroid.diligent.projects.RecentProject
import com.vscodroid.diligent.projects.RelativeTime
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * A recent project on the Projects tab: its name, what it is written in, and
 * when it was last opened. Tap opens it. The menu (the trailing button, or a
 * long press) takes it off the list without touching its files.
 *
 * The open project is drawn in the primary container colour with an "Open
 * now" tag, so someone coming back to this tab sees at once where they are.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectCard(
    project: RecentProject,
    now: Long,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val removeLabel = stringResource(R.string.diligent_projects_remove_recent)
    val colors = DiligentTheme.colors
    val container = if (isCurrent) colors.primaryContainer else colors.surfaceContainer
    val content = if (isCurrent) colors.onPrimaryContainer else colors.onSurface
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DiligentTheme.shapes.medium,
        color = container,
        contentColor = content,
        border = if (isCurrent) BorderStroke(DiligentTheme.sizes.divider, colors.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onOpen, onLongClickLabel = removeLabel, onLongClick = { menuOpen = true })
                .heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.l)
                .padding(start = DiligentTheme.spacing.m, top = DiligentTheme.spacing.m, bottom = DiligentTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
        ) {
            Icon(
                if (isCurrent) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
                tint = if (isCurrent) colors.onPrimaryContainer else colors.primary,
                modifier = Modifier.size(DiligentTheme.sizes.iconLarge),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs),
            ) {
                Text(
                    project.name,
                    style = DiligentTheme.type.material.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs),
                ) {
                    if (isCurrent) LabelChip(stringResource(R.string.diligent_projects_open_now), emphasized = true)
                    project.kind.language.labelRes()?.let { LabelChip(stringResource(it)) }
                    project.kind.framework?.let { LabelChip(stringResource(it.labelRes())) }
                }
                Text(
                    stringResource(R.string.diligent_projects_last_opened, relativeTimeText(project.lastOpened, now)),
                    style = DiligentTheme.type.material.bodySmall,
                    color = if (isCurrent) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(DiligentTheme.sizes.touchTarget),
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.diligent_projects_card_more, project.name),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(removeLabel) },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

/** A small label in a pill, for a language, a framework or a state. */
@Composable
fun LabelChip(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = DiligentTheme.shapes.pill,
        color = if (emphasized) DiligentTheme.colors.primary else DiligentTheme.colors.surfaceContainerHigh,
        contentColor = if (emphasized) DiligentTheme.colors.onPrimary else DiligentTheme.colors.onSurfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = DiligentTheme.spacing.s, vertical = DiligentTheme.spacing.xs / 2),
            style = DiligentTheme.type.material.labelMedium,
            maxLines = 1,
        )
    }
}

/** The language's name, or null for [ProjectLanguage.Unknown], which shows no chip. */
@StringRes
fun ProjectLanguage.labelRes(): Int? = when (this) {
    ProjectLanguage.TypeScript -> R.string.diligent_projects_language_typescript
    ProjectLanguage.JavaScript -> R.string.diligent_projects_language_javascript
    ProjectLanguage.Python -> R.string.diligent_projects_language_python
    ProjectLanguage.Ruby -> R.string.diligent_projects_language_ruby
    ProjectLanguage.Java -> R.string.diligent_projects_language_java
    ProjectLanguage.Kotlin -> R.string.diligent_projects_language_kotlin
    ProjectLanguage.CFamily -> R.string.diligent_projects_language_c
    ProjectLanguage.Go -> R.string.diligent_projects_language_go
    ProjectLanguage.Rust -> R.string.diligent_projects_language_rust
    ProjectLanguage.Web -> R.string.diligent_projects_language_web
    ProjectLanguage.Unknown -> null
}

@StringRes
fun ProjectFramework.labelRes(): Int = when (this) {
    ProjectFramework.NextJs -> R.string.diligent_projects_framework_next
    ProjectFramework.Vite -> R.string.diligent_projects_framework_vite
    ProjectFramework.Express -> R.string.diligent_projects_framework_express
    ProjectFramework.Django -> R.string.diligent_projects_framework_django
    ProjectFramework.Flask -> R.string.diligent_projects_framework_flask
    ProjectFramework.FastApi -> R.string.diligent_projects_framework_fastapi
    ProjectFramework.Rails -> R.string.diligent_projects_framework_rails
}

/** "2 hours ago", "yesterday", for a time [then] seen from [now]. */
@Composable
fun relativeTimeText(then: Long, now: Long): String = when (val ago = RelativeTime.between(then, now)) {
    RelativeTime.JustNow -> stringResource(R.string.diligent_projects_just_now)
    RelativeTime.Yesterday -> stringResource(R.string.diligent_projects_yesterday)
    is RelativeTime.Minutes -> pluralStringResource(R.plurals.diligent_projects_minutes_ago, ago.count, ago.count)
    is RelativeTime.Hours -> pluralStringResource(R.plurals.diligent_projects_hours_ago, ago.count, ago.count)
    is RelativeTime.Days -> pluralStringResource(R.plurals.diligent_projects_days_ago, ago.count, ago.count)
    is RelativeTime.Weeks -> pluralStringResource(R.plurals.diligent_projects_weeks_ago, ago.count, ago.count)
    is RelativeTime.Months -> pluralStringResource(R.plurals.diligent_projects_months_ago, ago.count, ago.count)
    is RelativeTime.Years -> pluralStringResource(R.plurals.diligent_projects_years_ago, ago.count, ago.count)
}

// -- Previews --

private const val PREVIEW_NOW = 1_800_000_000_000L
private const val PREVIEW_HOUR = 3_600_000L

@Composable
private fun CardSamples() {
    Surface(color = DiligentTheme.colors.background) {
        Column(
            modifier = Modifier.padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            ProjectCard(
                RecentProject("/p/shop", "shop", PREVIEW_NOW - 2 * PREVIEW_HOUR, ProjectKind(ProjectLanguage.TypeScript, ProjectFramework.NextJs)),
                PREVIEW_NOW, isCurrent = true, onOpen = {}, onRemove = {},
            )
            ProjectCard(
                RecentProject("/p/api", "weather-api", PREVIEW_NOW - 30 * PREVIEW_HOUR, ProjectKind(ProjectLanguage.Python, ProjectFramework.Flask)),
                PREVIEW_NOW, isCurrent = false, onOpen = {}, onRemove = {},
            )
            ProjectCard(
                RecentProject("/p/notes", "a project with a very long name that does not fit", PREVIEW_NOW - 900 * PREVIEW_HOUR),
                PREVIEW_NOW, isCurrent = false, onOpen = {}, onRemove = {},
            )
        }
    }
}

@Preview(name = "Project cards, light", widthDp = 360)
@Composable
private fun ProjectCardsLightPreview() = DiligentTheme(darkTheme = false) { CardSamples() }

@Preview(name = "Project cards, dark", widthDp = 360)
@Composable
private fun ProjectCardsDarkPreview() = DiligentTheme(darkTheme = true) { CardSamples() }
