package com.vscodroid.diligent.ui.screens

import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.kit.KitDone
import com.vscodroid.diligent.kit.KitInternet
import com.vscodroid.diligent.kit.KitRecipe
import com.vscodroid.diligent.kit.KitRecipes
import com.vscodroid.diligent.kit.KitRun
import com.vscodroid.diligent.kit.KitStepLine
import com.vscodroid.diligent.kit.KitStepState
import com.vscodroid.diligent.projects.NameProblem
import com.vscodroid.diligent.projects.ProjectNames
import com.vscodroid.diligent.ui.components.SheetButtons
import com.vscodroid.diligent.ui.components.messageRes
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.theme.DiligentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Setup (More, then Setup; and Projects, then New project, then Start from a
 * template): "What do you want to build?" A card per recipe of the kit
 * engine, a confirm sheet (what gets installed, the space it needs, the
 * project's name), live progress, and the result with Open project and Run.
 *
 * The run itself is [MobileActions.kit], kept by the controller, so leaving
 * this screen while packages install loses nothing: coming back shows it.
 * The recipes are read from the kit extension's assets; a build without the
 * extension shows a note instead of cards.
 */
@Composable
fun SetupScreen(shell: MobileShell, actions: MobileActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val kit = actions.kit
    var recipes by remember { mutableStateOf<List<KitRecipe>?>(null) }
    LaunchedEffect(Unit) { recipes = withContext(Dispatchers.IO) { KitRecipes.load(context.assets) } }
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastName by rememberSaveable { mutableStateOf<String?>(null) }
    val run = kit.run

    val open: (KitRun) -> String = { finished ->
        val path = finished.projectPath ?: File(actions.projectsDir, finished.projectName).path
        kit.dismiss()
        // The project already in the editor: reloading it would close its tabs for nothing.
        if (path == shell.folder) actions.openTab(MobileTab.Files) else actions.openFolder(path)
        path
    }

    SetupContent(
        recipes = recipes,
        run = run,
        onPick = { chosenId = it.id },
        onCancel = kit::cancel,
        onOpen = { open(it) },
        onRun = {
            open(it)
            // More opens its Run screen, which offers the profile the recipe wrote.
            shell.runRequested = true
            actions.openTab(MobileTab.More)
        },
        onTryAgain = { failed ->
            lastName = failed.projectName
            kit.dismiss()
            chosenId = failed.recipe
        },
        onBack = kit::dismiss,
        modifier = modifier,
    )

    val chosen = recipes?.firstOrNull { it.id == chosenId }
    if (chosen != null && run == null) {
        ConfirmSheet(
            recipe = chosen,
            projectsDir = actions.projectsDir,
            initialName = lastName,
            onDismiss = { chosenId = null },
            onStart = { name ->
                chosenId = null
                lastName = name
                kit.start(chosen.id, name)
            },
        )
    }
}

/** The screen for a given state, held by the caller, so previews show each one. */
@Composable
internal fun SetupContent(
    recipes: List<KitRecipe>?,
    run: KitRun?,
    onPick: (KitRecipe) -> Unit,
    onCancel: () -> Unit,
    onOpen: (KitRun) -> Unit,
    onRun: (KitRun) -> Unit,
    onTryAgain: (KitRun) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
        ) {
            when {
                run == null -> RecipeList(recipes, onPick)
                !run.finished -> Progress(run, onCancel)
                run.succeeded -> Ready(run, onOpen, onRun, onBack)
                else -> Failed(run, onTryAgain, onBack)
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        modifier = Modifier.semantics { heading() },
        style = DiligentTheme.type.material.headlineSmall,
        color = DiligentTheme.colors.onBackground,
    )
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.onSurfaceVariant)
}

// -- The recipes --

@Composable
private fun RecipeList(recipes: List<KitRecipe>?, onPick: (KitRecipe) -> Unit) {
    Heading(stringResource(R.string.diligent_setup_title))
    Body(stringResource(R.string.diligent_setup_intro))
    when {
        recipes == null -> Unit
        recipes.isEmpty() -> Body(stringResource(R.string.diligent_setup_none))
        else -> recipes.forEach { RecipeCard(it, onClick = { onPick(it) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeCard(recipe: KitRecipe, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget),
        color = DiligentTheme.colors.surfaceContainer,
        shape = DiligentTheme.shapes.medium,
    ) {
        Column(
            Modifier.padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            Text(
                recipe.title,
                modifier = Modifier.semantics { heading() },
                style = DiligentTheme.type.material.titleMedium,
                color = DiligentTheme.colors.onSurface,
            )
            if (recipe.summary.isNotEmpty()) Body(recipe.summary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs),
            ) {
                recipe.sizeMB?.let { Badge(stringResource(R.string.diligent_setup_size, it)) }
                Badge(stringResource(recipe.internet.label))
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = DiligentTheme.colors.surfaceContainerHigh, shape = DiligentTheme.shapes.small) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = DiligentTheme.spacing.s, vertical = DiligentTheme.spacing.xs),
            style = DiligentTheme.type.material.labelMedium,
            color = DiligentTheme.colors.onSurface,
        )
    }
}

private val KitInternet.label: Int
    get() = when (this) {
        KitInternet.No -> R.string.diligent_setup_internet_no
        KitInternet.Once -> R.string.diligent_setup_internet_once
        KitInternet.PackDownload -> R.string.diligent_setup_internet_pack
    }

private val KitInternet.body: Int
    get() = when (this) {
        KitInternet.No -> R.string.diligent_setup_internet_no_body
        KitInternet.Once -> R.string.diligent_setup_internet_once_body
        KitInternet.PackDownload -> R.string.diligent_setup_internet_pack_body
    }

// -- Confirm --

/**
 * What the recipe installs and needs, and the project's name, checked like
 * New project's. The free space is read once, off the main thread.
 */
@Composable
private fun ConfirmSheet(
    recipe: KitRecipe,
    projectsDir: String,
    initialName: String?,
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
) {
    val parent = remember(projectsDir) { File(projectsDir) }
    var name by rememberSaveable(recipe.id) { mutableStateOf(initialName ?: recipe.id) }
    var problem by remember { mutableStateOf<NameProblem?>(null) }
    // The name the problem above is about: Start waits for the check of the name shown.
    var checked by remember { mutableStateOf<String?>(null) }
    var freeBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(name) {
        problem = withContext(Dispatchers.IO) { ProjectNames.problemIn(parent, name) }
        checked = name
    }
    LaunchedEffect(parent) { freeBytes = withContext(Dispatchers.IO) { freeSpace(parent) } }

    DiligentSheet(onDismiss = onDismiss) { hide ->
        ConfirmForm(
            recipe = recipe,
            name = name,
            onNameChange = { name = it },
            problem = problem,
            canStart = checked == name && problem == null,
            freeBytes = freeBytes,
            onStart = {
                if (checked == name && problem == null) hide { onStart(ProjectNames.clean(name)) }
            },
            onCancel = { hide {} },
        )
    }
}

/** Free bytes where projects go, or null when the phone will not say. */
private fun freeSpace(dir: File): Long? = try {
    StatFs((generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() } ?: dir).path).availableBytes
} catch (_: IllegalArgumentException) {
    null
}

@Composable
private fun ConfirmForm(
    recipe: KitRecipe,
    name: String,
    onNameChange: (String) -> Unit,
    problem: NameProblem?,
    canStart: Boolean,
    freeBytes: Long?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DiligentTheme.spacing.l)
            .padding(bottom = DiligentTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
    ) {
        Text(
            recipe.title,
            modifier = Modifier.semantics { heading() },
            style = DiligentTheme.type.material.titleLarge,
            color = DiligentTheme.colors.onSurface,
        )
        if (recipe.summary.isNotEmpty()) Body(recipe.summary)

        SectionTitle(stringResource(R.string.diligent_setup_installs))
        if (recipe.packs.isEmpty()) {
            Body(stringResource(R.string.diligent_setup_nothing_extra))
        } else {
            recipe.packs.forEach { Body(stringResource(R.string.diligent_setup_pack, it)) }
        }
        Body(stringResource(recipe.internet.body))

        SectionTitle(stringResource(R.string.diligent_setup_space))
        val needed = recipe.minFreeMB ?: recipe.sizeMB
        Body(needed?.let { stringResource(R.string.diligent_setup_space_body, it) } ?: stringResource(R.string.diligent_setup_space_unknown))
        if (needed != null && freeBytes != null && freeBytes < needed * BYTES_PER_MB) {
            Text(
                stringResource(R.string.diligent_setup_low_space, Formatter.formatShortFileSize(context, freeBytes)),
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.failed,
            )
        }

        if (recipe.steps.isNotEmpty()) {
            SectionTitle(stringResource(R.string.diligent_setup_steps))
            recipe.steps.forEachIndexed { i, step -> Body("${i + 1}. $step") }
        }

        val error = problem?.let { stringResource(it.messageRes()) }
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth().padding(top = DiligentTheme.spacing.s),
            label = { Text(stringResource(R.string.diligent_projects_name)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onStart() }),
        )
        SheetButtons(
            confirm = stringResource(R.string.diligent_setup_start),
            confirmEnabled = canStart,
            onConfirm = onStart,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = DiligentTheme.spacing.s).semantics { heading() },
        style = DiligentTheme.type.material.titleSmall,
        color = DiligentTheme.colors.onSurface,
    )
}

private const val BYTES_PER_MB = 1024L * 1024

// -- Progress --

@Composable
private fun Progress(run: KitRun, onCancel: () -> Unit) {
    Heading(stringResource(R.string.diligent_setup_running_title, run.projectName))
    if (run.steps.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m)) {
            CircularProgressIndicator(Modifier.size(DiligentTheme.sizes.icon))
            Body(stringResource(R.string.diligent_setup_waiting), Modifier.weight(1f))
        }
    } else {
        Column {
            run.steps.forEachIndexed { i, step -> StepRow(i, step) }
        }
    }
    run.line?.let { line ->
        SectionTitle(stringResource(R.string.diligent_setup_latest))
        Surface(color = DiligentTheme.colors.surfaceContainerHigh, shape = DiligentTheme.shapes.small) {
            Text(
                line,
                modifier = Modifier.fillMaxWidth().padding(DiligentTheme.spacing.s),
                style = DiligentTheme.type.codeSmall,
                color = DiligentTheme.colors.onSurface,
                maxLines = 4,
            )
        }
    }
    Body(stringResource(R.string.diligent_setup_leave_note))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
        Text(stringResource(R.string.diligent_setup_cancel))
    }
}

/** One step: what it does and where it stands, read by TalkBack as one item. */
@Composable
private fun StepRow(index: Int, step: KitStepLine) {
    val state = stringResource(step.state.label)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        Box(Modifier.size(DiligentTheme.sizes.icon), contentAlignment = Alignment.Center) {
            when (step.state) {
                KitStepState.Running -> CircularProgressIndicator(
                    Modifier.fillMaxSize().semantics { contentDescription = state },
                )
                KitStepState.Ok -> Icon(Icons.Outlined.CheckCircle, contentDescription = state, tint = DiligentTheme.colors.running)
                KitStepState.Fail -> Icon(Icons.Outlined.ErrorOutline, contentDescription = state, tint = DiligentTheme.colors.failed)
                KitStepState.Pending -> Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = state,
                    tint = DiligentTheme.colors.onSurfaceVariant,
                )
            }
        }
        Text(
            step.title.ifEmpty { stringResource(R.string.diligent_setup_step_untitled, index + 1) },
            modifier = Modifier.weight(1f),
            style = DiligentTheme.type.material.bodyLarge,
            color = if (step.state == KitStepState.Pending) DiligentTheme.colors.onSurfaceVariant else DiligentTheme.colors.onSurface,
        )
    }
}

private val KitStepState.label: Int
    get() = when (this) {
        KitStepState.Pending -> R.string.diligent_setup_step_pending
        KitStepState.Running -> R.string.diligent_setup_step_running
        KitStepState.Ok -> R.string.diligent_setup_step_ok
        KitStepState.Fail -> R.string.diligent_setup_step_fail
    }

// -- The result --

@Composable
private fun Ready(run: KitRun, onOpen: (KitRun) -> Unit, onRun: (KitRun) -> Unit, onBack: () -> Unit) {
    Icon(
        Icons.Outlined.CheckCircle,
        contentDescription = null,
        tint = DiligentTheme.colors.running,
        modifier = Modifier.size(DiligentTheme.sizes.iconLarge),
    )
    Heading(stringResource(R.string.diligent_setup_done_title, run.projectName))
    Body(run.done?.message ?: stringResource(R.string.diligent_setup_done_body))
    Button(onClick = { onOpen(run) }, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
        Text(stringResource(R.string.diligent_setup_open))
    }
    FilledTonalButton(onClick = { onRun(run) }, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
        Text(stringResource(R.string.diligent_setup_run))
    }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
        Text(stringResource(R.string.diligent_setup_back))
    }
}

@Composable
private fun Failed(run: KitRun, onTryAgain: (KitRun) -> Unit, onBack: () -> Unit) {
    Icon(
        Icons.Outlined.ErrorOutline,
        contentDescription = null,
        tint = DiligentTheme.colors.failed,
        modifier = Modifier.size(DiligentTheme.sizes.iconLarge),
    )
    Heading(stringResource(R.string.diligent_setup_failed_title))
    val message = when {
        run.timedOut -> stringResource(R.string.diligent_setup_timeout)
        else -> run.done?.message ?: stringResource(R.string.diligent_setup_failed_body)
    }
    Text(message, style = DiligentTheme.type.material.bodyLarge, color = DiligentTheme.colors.onSurface)
    if (run.steps.isNotEmpty()) {
        Column { run.steps.forEachIndexed { i, step -> StepRow(i, step) } }
    }
    Button(onClick = { onTryAgain(run) }, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
        Text(stringResource(R.string.diligent_setup_try_again))
    }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
        Text(stringResource(R.string.diligent_setup_back))
    }
}

// -- Previews --

private val previewRecipes = listOf(
    KitRecipe("web-basic", "Web page", "HTML, CSS and JavaScript, served on the phone.", 1, emptyList(), 10, KitInternet.No, emptyList()),
    KitRecipe("react-vite", "React app", "A React app with Vite, ready to run.", 85, emptyList(), 400, KitInternet.Once, listOf("Create the app", "Install packages", "Check it starts")),
    KitRecipe("java", "Java", "Hello World with the Java pack.", 210, listOf("java"), 600, KitInternet.PackDownload, emptyList()),
)

private val previewSteps = listOf(
    KitStepLine("Check space", KitStepState.Ok),
    KitStepLine("Install packages", KitStepState.Running),
    KitStepLine("", KitStepState.Pending),
)

private val previewRunning = KitRun("react-vite", "my-app", runId = "r1", steps = previewSteps, line = "added 212 packages in 41s")
private val previewDone = KitRun(
    "react-vite", "my-app", runId = "r1",
    steps = previewSteps.map { it.copy(state = KitStepState.Ok) },
    done = KitDone(true, "/home/projects/my-app", null),
)
private val previewFailed = KitRun(
    "react-vite", "my-app", runId = "r1",
    steps = listOf(KitStepLine("Check space", KitStepState.Ok), KitStepLine("Install packages", KitStepState.Fail)),
    done = KitDone(false, null, "npm could not reach the internet. Connect to Wi-Fi and try again."),
)

@Composable
private fun PreviewSetup(run: KitRun?, recipes: List<KitRecipe>? = previewRecipes) = SetupContent(
    recipes, run, onPick = {}, onCancel = {}, onOpen = {}, onRun = {}, onTryAgain = {}, onBack = {},
)

@Composable
private fun PreviewConfirm() = Surface(color = DiligentTheme.colors.surfaceContainerLow) {
    ConfirmForm(previewRecipes[1], "my-app", {}, null, true, 200L * BYTES_PER_MB, onStart = {}, onCancel = {})
}

@Preview(name = "Setup, recipes, light", widthDp = 360, heightDp = 720)
@Composable
private fun SetupListLightPreview() = DiligentTheme(darkTheme = false) { PreviewSetup(null) }

@Preview(name = "Setup, recipes, dark", widthDp = 360, heightDp = 720)
@Composable
private fun SetupListDarkPreview() = DiligentTheme(darkTheme = true) { PreviewSetup(null) }

@Preview(name = "Setup, no recipes, light", widthDp = 360, heightDp = 400)
@Composable
private fun SetupEmptyLightPreview() = DiligentTheme(darkTheme = false) { PreviewSetup(null, emptyList()) }

@Preview(name = "Setup, no recipes, dark", widthDp = 360, heightDp = 400)
@Composable
private fun SetupEmptyDarkPreview() = DiligentTheme(darkTheme = true) { PreviewSetup(null, emptyList()) }

@Preview(name = "Setup, confirm, light", widthDp = 360, heightDp = 800)
@Composable
private fun SetupConfirmLightPreview() = DiligentTheme(darkTheme = false) { PreviewConfirm() }

@Preview(name = "Setup, confirm, dark", widthDp = 360, heightDp = 800)
@Composable
private fun SetupConfirmDarkPreview() = DiligentTheme(darkTheme = true) { PreviewConfirm() }

@Preview(name = "Setup, starting, light", widthDp = 360, heightDp = 400)
@Composable
private fun SetupStartingLightPreview() = DiligentTheme(darkTheme = false) { PreviewSetup(KitRun("react-vite", "my-app")) }

@Preview(name = "Setup, progress, light", widthDp = 360, heightDp = 640)
@Composable
private fun SetupProgressLightPreview() = DiligentTheme(darkTheme = false) { PreviewSetup(previewRunning) }

@Preview(name = "Setup, progress, dark", widthDp = 360, heightDp = 640)
@Composable
private fun SetupProgressDarkPreview() = DiligentTheme(darkTheme = true) { PreviewSetup(previewRunning) }

@Preview(name = "Setup, ready, light", widthDp = 360, heightDp = 640)
@Composable
private fun SetupReadyLightPreview() = DiligentTheme(darkTheme = false) { PreviewSetup(previewDone) }

@Preview(name = "Setup, ready, dark", widthDp = 360, heightDp = 640)
@Composable
private fun SetupReadyDarkPreview() = DiligentTheme(darkTheme = true) { PreviewSetup(previewDone) }

@Preview(name = "Setup, failed, light", widthDp = 360, heightDp = 640)
@Composable
private fun SetupFailedLightPreview() = DiligentTheme(darkTheme = false) { PreviewSetup(previewFailed) }

@Preview(name = "Setup, failed, dark", widthDp = 360, heightDp = 640)
@Composable
private fun SetupFailedDarkPreview() = DiligentTheme(darkTheme = true) { PreviewSetup(previewFailed) }

@Preview(name = "Setup, progress, large font", widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun SetupLargeFontPreview() = DiligentTheme(darkTheme = false) { PreviewSetup(previewRunning) }
