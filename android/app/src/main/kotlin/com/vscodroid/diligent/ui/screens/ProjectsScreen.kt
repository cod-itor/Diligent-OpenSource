package com.vscodroid.diligent.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.projects.CreateResult
import com.vscodroid.diligent.projects.NameProblem
import com.vscodroid.diligent.projects.ProjectFramework
import com.vscodroid.diligent.projects.ProjectKind
import com.vscodroid.diligent.projects.ProjectLanguage
import com.vscodroid.diligent.projects.ProjectNames
import com.vscodroid.diligent.projects.ProjectsFolder
import com.vscodroid.diligent.projects.RecentProject
import com.vscodroid.diligent.projects.RecentProjects
import com.vscodroid.diligent.ui.components.NewProjectForm
import com.vscodroid.diligent.ui.components.OpenFolderList
import com.vscodroid.diligent.ui.components.ProjectCard
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.theme.DiligentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which sheet the Projects tab has up. Saveable, so it survives what Compose re-creates. */
private enum class ProjectsSheet { New, Clone, Open }

/**
 * Mobile view's Projects tab, the first thing a user sees: the projects
 * opened recently, and the three ways to start (New project, Clone from Git,
 * Open folder) in a bar at the bottom, where a thumb is.
 *
 * The recent list comes from [RecentProjects], which the controller feeds
 * every time the editor opens a folder. Opening the tab prunes entries whose
 * folder has gone, so a card never leads nowhere.
 */
@Composable
fun ProjectsScreen(shell: MobileShell, actions: MobileActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recent = remember(context) { RecentProjects.of(context) }
    val projects by recent.projects.collectAsState()
    val scope = rememberCoroutineScope()
    var sheet by rememberSaveable { mutableStateOf<ProjectsSheet?>(null) }
    // Read once per change of the list: "2 hours ago" does not need to tick
    // while someone is looking at it.
    val now = remember(projects) { System.currentTimeMillis() }

    LaunchedEffect(recent) { recent.refresh() }

    ProjectsContent(
        projects = projects,
        currentPath = shell.folder,
        now = now,
        onOpen = { project ->
            // The open project is already in the editor: reloading it would
            // close its tabs for nothing.
            if (project.path == shell.folder) actions.openTab(MobileTab.Files) else actions.openFolder(project.path)
        },
        onRemove = { project -> scope.launch { recent.remove(project.path) } },
        onNew = { sheet = ProjectsSheet.New },
        onClone = { sheet = ProjectsSheet.Clone },
        onOpenFolder = { sheet = ProjectsSheet.Open },
        modifier = modifier,
    )

    val close = { sheet = null }
    when (sheet) {
        ProjectsSheet.New -> NewProjectSheet(
            projectsDir = actions.projectsDir,
            onDismiss = close,
            onCreated = { path -> actions.openFolder(path) },
        )
        ProjectsSheet.Open -> OpenFolderSheet(actions, now, onDismiss = close)
        ProjectsSheet.Clone -> Unit
        null -> Unit
    }
}

/**
 * The tab's content without its sources, so previews can show every state.
 * [projects] is null while the stored list is first read; the list area then
 * stays blank rather than greeting a returning user as new.
 */
@Composable
internal fun ProjectsContent(
    projects: List<RecentProject>?,
    currentPath: String?,
    now: Long,
    onOpen: (RecentProject) -> Unit,
    onRemove: (RecentProject) -> Unit,
    onNew: () -> Unit,
    onClone: () -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    projects == null -> Unit
                    projects.isEmpty() -> WelcomeState(Modifier.fillMaxSize())
                    else -> RecentGrid(projects, currentPath, now, onOpen, onRemove)
                }
            }
            ProjectActions(onNew = onNew, onClone = onClone, onOpenFolder = onOpenFolder)
        }
    }
}

/** One column of cards on a phone, two or more side by side when there is room (landscape, a tablet). */
@Composable
private fun RecentGrid(
    projects: List<RecentProject>,
    currentPath: String?,
    now: Long,
    onOpen: (RecentProject) -> Unit,
    onRemove: (RecentProject) -> Unit,
) {
    val spacing = DiligentTheme.spacing
    LazyVerticalGrid(
        // Half a sheet's width plus a margin: one column on any phone held
        // upright, two once it is turned.
        columns = GridCells.Adaptive(minSize = DiligentTheme.sizes.sheetMaxWidth / 2 + spacing.xl),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.m),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.diligent_screen_projects),
                modifier = Modifier.padding(top = spacing.s, bottom = spacing.xs),
                style = DiligentTheme.type.material.headlineSmall,
                color = DiligentTheme.colors.onBackground,
            )
        }
        items(projects, key = { it.path }) { project ->
            ProjectCard(
                project = project,
                now = now,
                isCurrent = project.path == currentPath,
                onOpen = { onOpen(project) },
                onRemove = { onRemove(project) },
            )
        }
    }
}

/** A fresh install: a welcome, pointing at the three buttons below it. */
@Composable
private fun WelcomeState(modifier: Modifier = Modifier) {
    val spacing = DiligentTheme.spacing
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.xl, vertical = spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.m, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .size(DiligentTheme.sizes.touchTarget + spacing.l)
                .background(DiligentTheme.colors.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.RocketLaunch,
                contentDescription = null,
                tint = DiligentTheme.colors.onPrimaryContainer,
                modifier = Modifier.size(DiligentTheme.sizes.iconLarge),
            )
        }
        Text(
            stringResource(R.string.diligent_projects_welcome_title),
            style = DiligentTheme.type.material.headlineSmall,
            color = DiligentTheme.colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.diligent_projects_welcome_body),
            style = DiligentTheme.type.material.bodyLarge,
            color = DiligentTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The three ways to start, always at the bottom. New project leads as the
 * filled button; the other two are tonal. A phone stacks New project over
 * the other two; a wide window puts all three in one row so they cost one
 * row of height, which landscape is short of.
 */
@Composable
private fun ProjectActions(onNew: () -> Unit, onClone: () -> Unit, onOpenFolder: () -> Unit) {
    val spacing = DiligentTheme.spacing
    Surface(color = DiligentTheme.colors.scheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(thickness = DiligentTheme.sizes.divider, color = DiligentTheme.colors.outlineVariant)
            BoxWithConstraints(Modifier.padding(spacing.m)) {
                val wide = maxWidth >= DiligentTheme.sizes.sheetMaxWidth
                val new = stringResource(R.string.diligent_projects_new)
                val clone = stringResource(R.string.diligent_projects_clone)
                val open = stringResource(R.string.diligent_projects_open)
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                        ActionButton(new, Icons.Outlined.CreateNewFolder, onNew, Modifier.weight(1f), filled = true)
                        ActionButton(clone, Icons.Outlined.Download, onClone, Modifier.weight(1f))
                        ActionButton(open, Icons.Outlined.FolderOpen, onOpenFolder, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
                        ActionButton(new, Icons.Outlined.CreateNewFolder, onNew, Modifier.fillMaxWidth(), filled = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                            ActionButton(clone, Icons.Outlined.Download, onClone, Modifier.weight(1f))
                            ActionButton(open, Icons.Outlined.FolderOpen, onOpenFolder, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier, filled: Boolean = false) {
    val sized = modifier.heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(DiligentTheme.sizes.iconSmall))
        Text(
            label,
            modifier = Modifier.padding(start = DiligentTheme.spacing.s),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (filled) {
        Button(onClick = onClick, modifier = sized) { content() }
    } else {
        FilledTonalButton(onClick = onClick, modifier = sized) { content() }
    }
}

/**
 * A bottom sheet in Diligent's colours. [content] gets a `hide` that slides
 * the sheet away, removes it, then runs what it is given, so a button closes
 * the sheet the way a swipe does. While [locked] (a clone running), swipes,
 * Back and taps outside do not close it; the sheet's own Cancel does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiligentSheet(
    onDismiss: () -> Unit,
    locked: Boolean = false,
    content: @Composable ColumnScope.(hide: (then: () -> Unit) -> Unit) -> Unit,
) {
    val lockedNow by rememberUpdatedState(locked)
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !lockedNow },
    )
    val scope = rememberCoroutineScope()
    val hide: (() -> Unit) -> Unit = { then ->
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!lockedNow) onDismiss() },
        sheetState = state,
        sheetMaxWidth = DiligentTheme.sizes.sheetMaxWidth,
        containerColor = DiligentTheme.colors.scheme.surfaceContainerLow,
    ) {
        content(hide)
    }
}

/** New project: a name, then an empty folder in the projects folder, then the editor opens it. */
@Composable
private fun NewProjectSheet(projectsDir: String, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<NameProblem?>(NameProblem.Empty) }
    var showEmpty by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parent = remember(projectsDir) { File(projectsDir) }

    // "Already exists" needs a look at the disk, so the check runs off the
    // main thread on every edit; it is one stat.
    LaunchedEffect(name) {
        problem = withContext(Dispatchers.IO) { ProjectNames.problemIn(parent, name) }
    }

    DiligentSheet(onDismiss = onDismiss) { hide ->
        NewProjectForm(
            name = name,
            onNameChange = {
                name = it
                failed = false
            },
            problem = problem,
            showEmptyProblem = showEmpty,
            createFailed = failed,
            busy = busy,
            onCreate = create@{
                if (busy) return@create
                if (ProjectNames.clean(name).isEmpty()) {
                    showEmpty = true
                    return@create
                }
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { ProjectsFolder.create(parent, name) }
                    busy = false
                    when (result) {
                        is CreateResult.Created -> hide { onCreated(result.folder.path) }
                        is CreateResult.Refused -> problem = result.problem
                        CreateResult.Failed -> failed = true
                    }
                }
            },
            onCancel = { hide {} },
        )
    }
}

/** Open folder: the system picker, device folders picked before, and the projects folder's own folders. */
@Composable
private fun OpenFolderSheet(actions: MobileActions, now: Long, onDismiss: () -> Unit) {
    // On the main thread, as the activity host promises its callers; it is
    // one read of the persisted grants.
    val deviceFolders = remember(actions) { actions.recentDeviceFolders() }
    var projectFolders by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(actions.projectsDir) {
        projectFolders = withContext(Dispatchers.IO) { ProjectsFolder.list(File(actions.projectsDir)) }
    }
    DiligentSheet(onDismiss = onDismiss) { hide ->
        OpenFolderList(
            deviceFolders = deviceFolders,
            projectFolders = projectFolders,
            now = now,
            onPicker = { hide { actions.openFolderPicker() } },
            onDeviceFolder = { folder -> hide { actions.openDeviceFolder(folder) } },
            onProjectFolder = { path -> hide { actions.openFolder(path) } },
        )
    }
}

// -- Previews --

private const val PREVIEW_NOW = 1_800_000_000_000L
private const val PREVIEW_HOUR = 3_600_000L

private val previewProjects = listOf(
    RecentProject("/home/projects/shop", "shop", PREVIEW_NOW - 2 * PREVIEW_HOUR, ProjectKind(ProjectLanguage.TypeScript, ProjectFramework.NextJs)),
    RecentProject("/home/projects/weather-api", "weather-api", PREVIEW_NOW - 26 * PREVIEW_HOUR, ProjectKind(ProjectLanguage.Python, ProjectFramework.Flask)),
    RecentProject("/home/projects/portfolio", "portfolio", PREVIEW_NOW - 100 * PREVIEW_HOUR, ProjectKind(ProjectLanguage.Web)),
    RecentProject("/home/projects/scratch", "scratch", PREVIEW_NOW - 900 * PREVIEW_HOUR),
)

@Composable
private fun PreviewContent(projects: List<RecentProject>) = ProjectsContent(
    projects = projects,
    currentPath = projects.firstOrNull()?.path,
    now = PREVIEW_NOW,
    onOpen = {}, onRemove = {}, onNew = {}, onClone = {}, onOpenFolder = {},
)

@Preview(name = "Projects, light", widthDp = 360, heightDp = 640)
@Composable
private fun ProjectsLightPreview() = DiligentTheme(darkTheme = false) { PreviewContent(previewProjects) }

@Preview(name = "Projects, dark", widthDp = 360, heightDp = 640)
@Composable
private fun ProjectsDarkPreview() = DiligentTheme(darkTheme = true) { PreviewContent(previewProjects) }

@Preview(name = "Projects, empty, light", widthDp = 360, heightDp = 640)
@Composable
private fun ProjectsEmptyLightPreview() = DiligentTheme(darkTheme = false) { PreviewContent(emptyList()) }

@Preview(name = "Projects, empty, dark", widthDp = 360, heightDp = 640)
@Composable
private fun ProjectsEmptyDarkPreview() = DiligentTheme(darkTheme = true) { PreviewContent(emptyList()) }

@Preview(name = "Projects, landscape, dark", widthDp = 760, heightDp = 240, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProjectsLandscapePreview() = DiligentTheme(darkTheme = true) { PreviewContent(previewProjects) }

@Preview(name = "Projects, empty, landscape, light", widthDp = 760, heightDp = 240)
@Composable
private fun ProjectsEmptyLandscapePreview() = DiligentTheme(darkTheme = false) { PreviewContent(emptyList()) }
