package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.files.FileEntry
import com.vscodroid.diligent.files.FileProblem
import com.vscodroid.diligent.files.FileResult
import com.vscodroid.diligent.files.FileTree
import com.vscodroid.diligent.files.ProjectFiles
import com.vscodroid.diligent.files.TreeRow
import com.vscodroid.diligent.ui.components.AllFilesList
import com.vscodroid.diligent.ui.components.DeleteDialog
import com.vscodroid.diligent.ui.components.FileRow
import com.vscodroid.diligent.ui.components.FileSheet
import com.vscodroid.diligent.ui.components.MoveDialog
import com.vscodroid.diligent.ui.components.NameDialog
import com.vscodroid.diligent.ui.components.fileActions
import com.vscodroid.diligent.ui.components.newActions
import com.vscodroid.diligent.ui.components.problemMessage
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.theme.DiligentTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Mobile view's Files tab: every file of the open project in one searchable
 * list ([FilesMode.AllFiles], see [AllFilesList]), or the project as an
 * expandable tree ([FilesMode.Folders]), which this comment is about.
 *
 * **A tree rather than a folder-at-a-time browser.** A browser gives each
 * level the whole width, but it hides where a file sits and turns "the other
 * file in the folder next door" into Back, tap, tap. Projects are shallow and
 * wide (`src/app/page.tsx`), so a tree keeps most of what is open on screen,
 * matches the editor's own Explorer, and one row is still one tap. Each level
 * costs one spacing step of indent, and the row stays a full-width target.
 *
 * **Fast for big folders.** Only open folders are read, each when it opens,
 * off the main thread, into a lazy list; `.git` and `node_modules` start
 * closed like everything else and are dimmed so nobody opens them by accident.
 *
 * **Fresh.** The tab reads the project again every time it is shown (it is
 * composed only while it is the tab in front), so files the editor or the
 * terminal made since are there; pull down to read it again on the spot.
 *
 * For a folder from device storage `shell.folder` is the mirror the editor
 * works on, and changes made here reach the device folder through the same
 * watcher as the editor's own saves: see [ProjectFiles].
 */
@Composable
fun FilesScreen(
    shell: MobileShell,
    actions: MobileActions,
    modifier: Modifier = Modifier,
    mode: FilesMode = FilesMode.AllFiles,
    onMode: (FilesMode) -> Unit = {},
) {
    val title = stringResource(R.string.diligent_screen_files)
    Surface(
        modifier = modifier.fillMaxSize().semantics { paneTitle = title },
        color = DiligentTheme.colors.background,
    ) {
        val folder = shell.folder
        if (folder == null) {
            NoProject(onOpenProjects = { actions.openTab(MobileTab.Projects) })
        } else {
            Column(Modifier.fillMaxSize()) {
                FilesModeSwitch(mode, onMode)
                // A different project is a different list and tree: nothing carries over.
                key(folder) {
                    when (mode) {
                        FilesMode.AllFiles -> AllFilesList(
                            root = folder,
                            recent = emptyList(),
                            onOpen = { actions.openFile(it.path) },
                            modifier = Modifier.weight(1f),
                        )
                        FilesMode.Folders -> Box(Modifier.weight(1f)) {
                            ProjectTree(folder, shell.projectName ?: File(folder).name, actions)
                        }
                    }
                }
            }
        }
    }
}

/**
 * How the Files tab shows the project: every file in one list with a search
 * (the default, so any file is one tap away), or the folder tree, where
 * files are made, renamed, moved and deleted.
 */
enum class FilesMode { AllFiles, Folders }

@Composable
private fun FilesModeSwitch(mode: FilesMode, onMode: (FilesMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DiligentTheme.spacing.m, end = DiligentTheme.spacing.m, top = DiligentTheme.spacing.s),
    ) {
        val modes = FilesMode.entries
        modes.forEachIndexed { index, each ->
            SegmentedButton(
                selected = mode == each,
                onClick = { onMode(each) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget),
            ) {
                Text(
                    stringResource(
                        when (each) {
                            FilesMode.AllFiles -> R.string.diligent_files_mode_all
                            FilesMode.Folders -> R.string.diligent_files_mode_folders
                        },
                    ),
                )
            }
        }
    }
}

/** Where the root stands. */
internal enum class RootStatus { Loading, Ready, NotAFolder, Unreadable }

/** The sheet on screen: one item's actions, or what can be made in a folder. */
private sealed interface FilesSheet {
    data class Actions(val entry: FileEntry) : FilesSheet
    data class New(val folder: String) : FilesSheet
}

/** The dialog on screen. A refused name keeps its dialog open, with the [problem] for the name [tried]. */
private sealed interface FilesDialog {
    data class Create(val folder: String, val isFolder: Boolean, val problem: FileProblem? = null, val tried: String? = null) : FilesDialog
    data class Rename(val entry: FileEntry, val problem: FileProblem? = null, val tried: String? = null) : FilesDialog
    data class Move(val entry: FileEntry) : FilesDialog
    data class Delete(val entry: FileEntry) : FilesDialog
}

/** A short message at the bottom. */
private sealed interface FilesNotice {
    data class Problem(val problem: FileProblem) : FilesNotice
    data class Deleted(val name: String) : FilesNotice
    data object LinkOutside : FilesNotice
}

/** Problems that are about the name typed, which the name dialog shows under its field. */
private val NAME_PROBLEMS = setOf(FileProblem.EmptyName, FileProblem.BadName, FileProblem.NameTaken)

/**
 * The tab's state and what changes it. Everything here runs on the main
 * thread; [ProjectFiles] moves the disk work off it, and each result is
 * applied to the tree as it stands when the result arrives, so a folder
 * opened while a refresh is reading is not lost to it.
 */
@Stable
private class FilesState(val files: ProjectFiles, private val scope: CoroutineScope) {
    var tree by mutableStateOf(FileTree(files.root))
        private set
    var status by mutableStateOf(RootStatus.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** The folder opened last, where New makes things while it is on screen. */
    private var lastOpened: String? = null

    var sheet by mutableStateOf<FilesSheet?>(null)
    var dialog by mutableStateOf<FilesDialog?>(null)
    var notice by mutableStateOf<FilesNotice?>(null)

    /**
     * True while a create, rename, move or delete runs. A second tap in that
     * time (a double tap, or Done then the button) is ignored: run twice, the
     * second would find its own first's result and report it as a problem.
     */
    var busy by mutableStateOf(false)
        private set

    val root: String get() = files.root

    fun newFolder(): String = tree.folderOnScreen(lastOpened)

    suspend fun refresh() {
        for (folder in tree.foldersToRefresh()) {
            // Closed meanwhile, or inside a folder that has gone.
            if (folder != root && folder !in tree.expanded) continue
            when (val listed = files.list(folder)) {
                is FileResult.Done -> tree = tree.withChildren(folder, listed.value)
                is FileResult.Refused -> if (folder == root) {
                    status = if (listed.problem == FileProblem.NotAFolder) RootStatus.NotAFolder else RootStatus.Unreadable
                    return
                } else {
                    tree = tree.removed(folder)
                }
            }
        }
        status = RootStatus.Ready
    }

    fun pullToRefresh() {
        scope.launch {
            refreshing = true
            try {
                refresh()
            } finally {
                refreshing = false
            }
        }
    }

    fun toggle(folder: FileEntry) {
        if (folder.path in tree.expanded) {
            tree = tree.collapse(folder.path)
        } else {
            open(folder.path)
        }
    }

    private fun open(folder: String) {
        tree = tree.expand(folder)
        lastOpened = folder
        scope.launch { load(folder) }
    }

    private suspend fun load(folder: String) {
        when (val listed = files.list(folder)) {
            is FileResult.Done -> if (folder == root || folder in tree.expanded) tree = tree.withChildren(folder, listed.value)
            is FileResult.Refused -> {
                tree = tree.removed(folder)
                notice = FilesNotice.Problem(listed.problem)
            }
        }
    }

    /** Whether [folder]'s last listing holds [name]; the operation itself has the final word. */
    fun holds(folder: String, name: String): Boolean = tree.children[folder]?.any { it.name == name } == true

    /** Runs [block] unless an operation already runs; all calls are on the main thread. */
    private fun operate(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    fun create(folder: String, name: String, isFolder: Boolean, openFile: (String) -> Unit) {
        operate {
            val made = if (isFolder) files.createFolder(folder, name) else files.createFile(folder, name)
            when (made) {
                is FileResult.Refused -> refuse(made.problem) { FilesDialog.Create(folder, isFolder, it, name.trim()) }
                is FileResult.Done -> {
                    dialog = null
                    if (folder != root) tree = tree.expand(folder)
                    if (isFolder) open(made.value)
                    refresh()
                    // A new file is made to be written in.
                    if (!isFolder) openFile(made.value)
                }
            }
        }
    }

    fun rename(entry: FileEntry, name: String) {
        operate {
            when (val renamed = files.rename(entry.path, name)) {
                is FileResult.Refused -> refuse(renamed.problem) { FilesDialog.Rename(entry, it, name.trim()) }
                is FileResult.Done -> {
                    dialog = null
                    moved(entry.path, renamed.value)
                    refresh()
                }
            }
        }
    }

    fun move(entry: FileEntry, folder: String) {
        dialog = null
        operate {
            when (val result = files.move(entry.path, folder)) {
                is FileResult.Refused -> refuse(result.problem) { null }
                is FileResult.Done -> {
                    moved(entry.path, result.value)
                    if (folder != root) tree = tree.expand(folder)
                    refresh()
                }
            }
        }
    }

    fun delete(entry: FileEntry) {
        dialog = null
        operate {
            when (val deleted = files.delete(entry.path)) {
                is FileResult.Refused -> refuse(deleted.problem) { null }
                is FileResult.Done -> {
                    tree = tree.removed(entry.path)
                    refresh()
                    notice = FilesNotice.Deleted(entry.name)
                }
            }
        }
    }

    suspend fun folders(path: String): List<FileEntry>? = (files.list(path) as? FileResult.Done)?.value

    private fun moved(from: String, to: String) {
        tree = tree.moved(from, to)
        lastOpened = lastOpened?.let { if (FileTree.isSelfOrUnder(it, from)) to + it.substring(from.length) else it }
    }

    /**
     * A refusal: a problem with the typed name goes back into the dialog
     * [again] makes, anything else closes it and shows a message. Something
     * found missing means the tree is out of date, so it is read again.
     */
    private suspend fun refuse(problem: FileProblem, again: (FileProblem) -> FilesDialog?) {
        val reopened = if (problem in NAME_PROBLEMS) again(problem) else null
        dialog = reopened
        if (reopened == null) notice = FilesNotice.Problem(problem)
        if (problem == FileProblem.Missing) refresh()
    }
}

@Composable
private fun ProjectTree(folder: String, projectName: String, actions: MobileActions) {
    val scope = rememberCoroutineScope()
    val state = remember { FilesState(ProjectFiles(folder), scope) }
    val snackbar = remember { SnackbarHostState() }

    // Composed only while this tab is in front, so this runs each time it is shown.
    LaunchedEffect(Unit) { state.refresh() }

    val notice = state.notice
    val noticeText = notice?.let { noticeMessage(it) }
    LaunchedEffect(notice) {
        if (noticeText != null) {
            // Cleared after it is shown, not before: clearing restarts this
            // effect, which would cancel the message it is showing.
            snackbar.showSnackbar(noticeText)
            state.notice = null
        }
    }

    val rows = remember(state.tree) { state.tree.rows() }
    FilesContent(
        rows = rows,
        status = state.status,
        refreshing = state.refreshing,
        snackbar = snackbar,
        onRefresh = state::pullToRefresh,
        onRow = { row ->
            val entry = row.entry
            when {
                entry.leavesProject -> state.notice = FilesNotice.LinkOutside
                entry.isDirectory -> state.toggle(entry)
                else -> actions.openFile(entry.path)
            }
        },
        onRowActions = { row -> state.sheet = FilesSheet.Actions(row.entry) },
        onNew = { state.sheet = FilesSheet.New(state.newFolder()) },
    )

    fun label(path: String) = state.files.relative(path).ifEmpty { projectName }

    when (val sheet = state.sheet) {
        null -> Unit
        is FilesSheet.Actions -> FileSheet(
            title = sheet.entry.name,
            subtitle = state.files.relative(sheet.entry.path),
            actions = fileActions(
                isFolder = sheet.entry.isDirectory,
                onNewFile = { state.dialog = FilesDialog.Create(sheet.entry.path, isFolder = false) },
                onNewFolder = { state.dialog = FilesDialog.Create(sheet.entry.path, isFolder = true) },
                onRename = { state.dialog = FilesDialog.Rename(sheet.entry) },
                onMove = { state.dialog = FilesDialog.Move(sheet.entry) },
                onDelete = { state.dialog = FilesDialog.Delete(sheet.entry) },
            ),
            onDismiss = { state.sheet = null },
        )
        is FilesSheet.New -> FileSheet(
            title = stringResource(R.string.diligent_files_new_in, label(sheet.folder)),
            subtitle = null,
            actions = newActions(
                onNewFile = { state.dialog = FilesDialog.Create(sheet.folder, isFolder = false) },
                onNewFolder = { state.dialog = FilesDialog.Create(sheet.folder, isFolder = true) },
            ),
            onDismiss = { state.sheet = null },
        )
    }

    when (val dialog = state.dialog) {
        null -> Unit
        is FilesDialog.Create -> NameDialog(
            title = stringResource(if (dialog.isFolder) R.string.diligent_files_new_folder else R.string.diligent_files_new_file),
            confirmLabel = stringResource(R.string.diligent_files_create),
            onConfirm = { name -> state.create(dialog.folder, name, dialog.isFolder, actions::openFile) },
            onDismiss = { state.dialog = null },
            check = { name ->
                ProjectFiles.checkName(name) ?: FileProblem.NameTaken.takeIf { state.holds(dialog.folder, name) }
            },
            problem = dialog.problem,
            problemName = dialog.tried,
            busy = state.busy,
        )
        is FilesDialog.Rename -> {
            val parent = File(dialog.entry.path).parent ?: state.root
            NameDialog(
                title = stringResource(R.string.diligent_files_rename_title, dialog.entry.name),
                confirmLabel = stringResource(R.string.diligent_files_rename),
                onConfirm = { name -> state.rename(dialog.entry, name) },
                onDismiss = { state.dialog = null },
                initial = dialog.entry.name,
                check = { name ->
                    ProjectFiles.checkName(name)
                        ?: FileProblem.NameTaken.takeIf { name != dialog.entry.name && state.holds(parent, name) }
                },
                problem = dialog.problem,
                problemName = dialog.tried,
                busy = state.busy,
            )
        }
        is FilesDialog.Move -> MoveDialog(
            item = dialog.entry,
            root = state.root,
            rootLabel = projectName,
            list = state::folders,
            onMove = { destination -> state.move(dialog.entry, destination) },
            onDismiss = { state.dialog = null },
        )
        is FilesDialog.Delete -> DeleteDialog(
            name = dialog.entry.name,
            isFolder = dialog.entry.isDirectory,
            onConfirm = { state.delete(dialog.entry) },
            onDismiss = { state.dialog = null },
        )
    }
}

@Composable
private fun noticeMessage(notice: FilesNotice): String = when (notice) {
    is FilesNotice.Problem -> problemMessage(notice.problem)
    is FilesNotice.Deleted -> stringResource(R.string.diligent_files_deleted, notice.name)
    FilesNotice.LinkOutside -> stringResource(R.string.diligent_files_link_outside_message)
}

/**
 * What the tab draws for a project, from plain values, so previews can show
 * every state without a file system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilesContent(
    rows: List<TreeRow>,
    status: RootStatus,
    refreshing: Boolean,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onRow: (TreeRow) -> Unit,
    onRowActions: (TreeRow) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DiligentTheme.colors.background,
        // The root view has applied the system bar insets already.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (status == RootStatus.Ready) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.diligent_files_new)) },
                    containerColor = DiligentTheme.colors.primaryContainer,
                    contentColor = DiligentTheme.colors.onPrimaryContainer,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Room under the last row, so the New button never covers it.
                contentPadding = PaddingValues(bottom = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.xl),
            ) {
                when {
                    status == RootStatus.Loading -> fullScreen("loading") { CircularProgressIndicator() }
                    status == RootStatus.NotAFolder -> fullScreen("not-a-folder") {
                        Message(text = stringResource(R.string.diligent_files_not_a_folder))
                    }
                    status == RootStatus.Unreadable -> fullScreen("unreadable") {
                        Message(text = stringResource(R.string.diligent_files_unreadable))
                    }
                    rows.isEmpty() -> fullScreen("empty") {
                        Message(
                            title = stringResource(R.string.diligent_files_empty_title),
                            text = stringResource(R.string.diligent_files_empty_body),
                        )
                    }
                    else -> treeRows(rows, onRow, onRowActions)
                }
            }
        }
    }
}

private fun LazyListScope.treeRows(
    rows: List<TreeRow>,
    onRow: (TreeRow) -> Unit,
    onRowActions: (TreeRow) -> Unit,
) {
    rows.forEachIndexed { index, row ->
        item(key = row.entry.path, contentType = "row") {
            FileRow(
                entry = row.entry,
                depth = row.depth,
                expanded = row.expanded,
                onClick = { onRow(row) },
                onActions = { onRowActions(row) },
            )
        }
        val next = rows.getOrNull(index + 1)
        if (row.expanded && !row.loading && (next == null || next.depth <= row.depth)) {
            item(key = "empty:" + row.entry.path, contentType = "empty") { EmptyFolderRow(row.depth + 1) }
        }
    }
}

/** Under an open folder with nothing in it, so it does not look like it failed to open. */
@Composable
private fun EmptyFolderRow(depth: Int) {
    Text(
        stringResource(R.string.diligent_files_empty_subfolder),
        modifier = Modifier.padding(
            start = DiligentTheme.spacing.s + DiligentTheme.spacing.m * depth + DiligentTheme.sizes.iconSmall + DiligentTheme.spacing.m,
            top = DiligentTheme.spacing.s,
            bottom = DiligentTheme.spacing.s,
        ),
        style = DiligentTheme.type.material.bodySmall,
        color = DiligentTheme.colors.onSurfaceVariant,
    )
}

/** One item filling the list, so a message can still be pulled down to refresh. */
private fun LazyListScope.fullScreen(key: String, content: @Composable () -> Unit) {
    item(key = key) {
        Box(Modifier.fillParentMaxSize().padding(DiligentTheme.spacing.l), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun Message(text: String, title: String? = null) {
    Column(
        modifier = Modifier.widthIn(max = DiligentTheme.sizes.sheetMaxWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
    ) {
        if (title != null) {
            Text(title, style = DiligentTheme.type.material.titleMedium, color = DiligentTheme.colors.onSurface, textAlign = TextAlign.Center)
        }
        Text(text, style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** No folder is open: say so, with the way to open one under the thumb. */
@Composable
private fun NoProject(onOpenProjects: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(DiligentTheme.spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.FolderOff,
            contentDescription = null,
            tint = DiligentTheme.colors.onSurfaceVariant,
            modifier = Modifier.size(DiligentTheme.sizes.iconLarge),
        )
        Spacer(Modifier.heightIn(min = DiligentTheme.spacing.m))
        Message(
            title = stringResource(R.string.diligent_files_no_project_title),
            text = stringResource(R.string.diligent_files_no_project_body),
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onOpenProjects,
            modifier = Modifier
                .widthIn(max = DiligentTheme.sizes.sheetMaxWidth)
                .fillMaxWidth()
                .heightIn(min = DiligentTheme.sizes.touchTarget),
        ) { Text(stringResource(R.string.diligent_files_go_to_projects)) }
    }
}

// -- Previews --

private fun previewRows(): List<TreeRow> {
    fun dir(path: String) = FileEntry("/p/$path", path.substringAfterLast('/'), isDirectory = true)
    fun file(path: String) = FileEntry("/p/$path", path.substringAfterLast('/'), isDirectory = false)
    return FileTree("/p")
        .withChildren("/p", listOf(dir(".git"), dir("node_modules"), dir("public"), dir("src"), file(".env.local"), file("package.json"), file("README.md")))
        .withChildren("/p/src", listOf(dir("src/app"), dir("src/lib"), file("src/middleware.ts")))
        .withChildren("/p/src/app", listOf(file("src/app/globals.css"), file("src/app/layout.tsx"), file("src/app/page.tsx")))
        .withChildren("/p/src/lib", emptyList())
        .expand("/p/src").expand("/p/src/app").expand("/p/src/lib")
        .rows()
}

@Composable
private fun TreeSample(rows: List<TreeRow> = previewRows(), status: RootStatus = RootStatus.Ready) {
    FilesContent(
        rows = rows,
        status = status,
        refreshing = false,
        snackbar = remember { SnackbarHostState() },
        onRefresh = {}, onRow = {}, onRowActions = {}, onNew = {},
    )
}

@Preview(name = "Files tree, light", widthDp = 360, heightDp = 640)
@Composable
private fun FilesTreeLightPreview() = DiligentTheme(darkTheme = false) { TreeSample() }

@Preview(name = "Files tree, dark", widthDp = 360, heightDp = 640)
@Composable
private fun FilesTreeDarkPreview() = DiligentTheme(darkTheme = true) { TreeSample() }

@Preview(name = "Files tree, landscape", widthDp = 720, heightDp = 300)
@Composable
private fun FilesTreeLandscapePreview() = DiligentTheme(darkTheme = false) { TreeSample() }

@Preview(name = "Files, empty folder, dark", widthDp = 360, heightDp = 640)
@Composable
private fun FilesEmptyPreview() = DiligentTheme(darkTheme = true) { TreeSample(rows = emptyList()) }

@Preview(name = "Files, no project, light", widthDp = 360, heightDp = 640)
@Composable
private fun FilesLightPreview() = DiligentTheme(darkTheme = false) {
    FilesScreen(MobileShell(null), PreviewActions)
}

@Preview(name = "Files, no project, dark", widthDp = 360, heightDp = 640)
@Composable
private fun FilesDarkPreview() = DiligentTheme(darkTheme = true) {
    FilesScreen(MobileShell(null), PreviewActions)
}

@Preview(name = "Files, no project, landscape", widthDp = 720, heightDp = 300)
@Composable
private fun FilesNoProjectLandscapePreview() = DiligentTheme(darkTheme = true) {
    FilesScreen(MobileShell(null), PreviewActions)
}
