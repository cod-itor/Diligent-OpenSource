package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.files.FileIndex
import com.vscodroid.diligent.files.FileMatches
import com.vscodroid.diligent.files.FileSearch
import com.vscodroid.diligent.files.IndexedFile
import com.vscodroid.diligent.files.RecentFileList
import com.vscodroid.diligent.files.RecentFiles
import com.vscodroid.diligent.files.fileKind
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** Where the list of every file stands. */
sealed interface IndexLoad {
    data object Loading : IndexLoad
    /** The project is not a folder that can be read (a workspace file, or no permission). */
    data object Unavailable : IndexLoad
    data class Ready(val index: FileIndex) : IndexLoad
}

/**
 * Every file of the project at [root], read off the main thread each time
 * this enters the screen, so files made since are there.
 */
@Composable
fun rememberFileIndex(root: String): IndexLoad {
    val load by produceState<IndexLoad>(IndexLoad.Loading, root) {
        value = FileIndex.build(root)?.let { IndexLoad.Ready(it) } ?: IndexLoad.Unavailable
    }
    return load
}

/**
 * Every file of the project in one list, with a search on top: the way to
 * reach any file without opening folders one at a time.
 *
 * The query lives here and survives a rotation. With no query, the files
 * opened recently in this project come first (see [RecentFiles]). [autoFocus] puts the cursor
 * in the search and brings up the keyboard, for the file switcher.
 */
@Composable
fun AllFilesList(
    root: String,
    onOpen: (IndexedFile) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val load = rememberFileIndex(root)
    val recent = rememberRecentFiles(root)
    var query by rememberSaveable(root) { mutableStateOf("") }
    AllFilesContent(
        load = load,
        query = query,
        onQuery = { query = it },
        recent = recent,
        onOpen = onOpen,
        modifier = modifier,
        autoFocus = autoFocus,
    )
}

/** The files opened recently in [project], newest first, as paths. */
@Composable
fun rememberRecentFiles(project: String): List<String> {
    val context = LocalContext.current
    val store = remember { RecentFiles.of(context) }
    LaunchedEffect(store) { store.load() }
    val all by store.files.collectAsState()
    return remember(all, project) { all?.let { RecentFileList.of(it, project) }.orEmpty() }
}

/** What [AllFilesList] draws, from plain values, so previews can show every state. */
@Composable
fun AllFilesContent(
    load: IndexLoad,
    query: String,
    onQuery: (String) -> Unit,
    recent: List<String>,
    onOpen: (IndexedFile) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    if (autoFocus) {
        LaunchedEffect(Unit) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s)
                .focusRequester(focus),
            placeholder = { Text(stringResource(R.string.diligent_files_search)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) null else {
                {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.diligent_files_search_clear))
                    }
                }
            },
            singleLine = true,
            shape = DiligentTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        when (load) {
            IndexLoad.Loading -> Centered { CircularProgressIndicator() }
            IndexLoad.Unavailable -> Centered { Note(stringResource(R.string.diligent_files_list_unreadable)) }
            is IndexLoad.Ready -> {
                val matches = remember(load.index, query, recent) { FileSearch.matches(load.index, query, recent) }
                when {
                    load.index.files.isEmpty() -> Centered { Note(stringResource(R.string.diligent_files_no_files_body)) }
                    matches.isEmpty -> Centered {
                        Note(
                            title = stringResource(R.string.diligent_files_no_match_title, query.trim()),
                            text = stringResource(R.string.diligent_files_no_match_body),
                        )
                    }
                    else -> FileList(matches, capped = load.index.capped, searching = query.isNotBlank(), onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun FileList(matches: FileMatches, capped: Boolean, searching: Boolean, onOpen: (IndexedFile) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        // On top, where it is seen before someone scrolls looking for a missing file.
        if (capped) {
            item(key = "capped", contentType = "note") { CappedNote(searching) }
        }
        if (matches.recent.isNotEmpty()) {
            header("recent-header", R.string.diligent_files_recent)
            items(matches.recent, key = { "recent:" + it.path }, contentType = { "row" }) { AllFileRow(it, onClick = { onOpen(it) }) }
            header("all-header", R.string.diligent_files_mode_all)
        }
        items(matches.others, key = { it.path }, contentType = { "row" }) { AllFileRow(it, onClick = { onOpen(it) }) }
    }
}

private fun LazyListScope.header(key: String, text: Int) {
    item(key = key, contentType = "header") {
        Text(
            stringResource(text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DiligentTheme.spacing.m, end = DiligentTheme.spacing.m, top = DiligentTheme.spacing.m, bottom = DiligentTheme.spacing.xs)
                .semantics { heading() },
            style = DiligentTheme.type.material.labelLarge,
            color = DiligentTheme.colors.primary,
        )
    }
}

/**
 * One file: its icon, its name in full strength and its folder dimmed under
 * it. TalkBack reads it as one item, "page.tsx, in src/app".
 */
@Composable
fun AllFileRow(file: IndexedFile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val folder = file.folder.ifEmpty { stringResource(R.string.diligent_files_top_folder) }
    val description = stringResource(R.string.diligent_files_row_description, file.name, folder)
    val openLabel = stringResource(R.string.diligent_files_open_in_editor)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                onClick(label = openLabel) { onClick(); true }
            }
            .padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        Icon(
            iconFor(fileKind(file.name)),
            contentDescription = null,
            tint = DiligentTheme.colors.onSurfaceVariant,
            modifier = Modifier.size(DiligentTheme.sizes.icon),
        )
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = DiligentTheme.type.material.bodyLarge,
                color = DiligentTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                folder,
                style = DiligentTheme.type.material.bodySmall,
                color = DiligentTheme.colors.onSurfaceVariant,
                maxLines = 1,
                // The end of a path says the most: `.../app/components`.
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

@Composable
private fun CappedNote(searching: Boolean) {
    val text = pluralStringResource(R.plurals.diligent_files_capped, FileIndex.MAX_FILES, FileIndex.MAX_FILES)
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
        style = DiligentTheme.type.material.bodyMedium,
        color = if (searching) DiligentTheme.colors.onSurface else DiligentTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(DiligentTheme.spacing.l), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Note(text: String, title: String? = null) {
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

// -- Previews --

internal fun previewIndex(capped: Boolean = false): FileIndex = FileIndex(
    FileIndex.grouped(
        listOf(
            "README.md", "package.json", ".env.local", "src/middleware.ts", "src/app/page.tsx", "src/app/layout.tsx",
            "src/app/globals.css", "src/lib/db.ts", "src/components/navigation/Header.tsx", "public/logo.svg",
            "scripts/deploy.sh",
        ).map { IndexedFile("/p/$it", it) },
    ),
    capped = capped,
)

internal val previewRecent = listOf("/p/src/app/page.tsx", "/p/src/lib/db.ts")

@Composable
private fun Sample(query: String = "", load: IndexLoad = IndexLoad.Ready(previewIndex()), recent: List<String> = previewRecent) {
    Surface(color = DiligentTheme.colors.background) {
        AllFilesContent(load = load, query = query, onQuery = {}, recent = recent, onOpen = {})
    }
}

@Preview(name = "All files, light", widthDp = 360, heightDp = 720)
@Composable
private fun AllFilesLightPreview() = DiligentTheme(darkTheme = false) { Sample() }

@Preview(name = "All files, dark", widthDp = 360, heightDp = 720)
@Composable
private fun AllFilesDarkPreview() = DiligentTheme(darkTheme = true) { Sample() }

@Preview(name = "All files, large font", widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun AllFilesLargeFontPreview() = DiligentTheme(darkTheme = false) { Sample() }

@Preview(name = "All files, search", widthDp = 360, heightDp = 480)
@Composable
private fun AllFilesSearchPreview() = DiligentTheme(darkTheme = true) { Sample(query = "pa") }

@Preview(name = "All files, no match, light", widthDp = 360, heightDp = 480)
@Composable
private fun AllFilesNoMatchLightPreview() = DiligentTheme(darkTheme = false) { Sample(query = "zebra") }

@Preview(name = "All files, no match, dark, large font", widthDp = 360, heightDp = 480, fontScale = 2f)
@Composable
private fun AllFilesNoMatchDarkPreview() = DiligentTheme(darkTheme = true) { Sample(query = "zebra") }

@Preview(name = "All files, capped, light", widthDp = 360, heightDp = 720)
@Composable
private fun AllFilesCappedLightPreview() = DiligentTheme(darkTheme = false) {
    Sample(load = IndexLoad.Ready(previewIndex(capped = true)), recent = emptyList())
}

@Preview(name = "All files, capped, dark, large font", widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun AllFilesCappedDarkPreview() = DiligentTheme(darkTheme = true) {
    Sample(load = IndexLoad.Ready(previewIndex(capped = true)), recent = emptyList())
}
