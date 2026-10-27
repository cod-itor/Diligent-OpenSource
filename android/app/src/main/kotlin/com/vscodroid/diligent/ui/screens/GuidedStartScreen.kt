package com.vscodroid.diligent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.AppView
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.guide.GuideGoal
import com.vscodroid.diligent.guide.GuideStep
import com.vscodroid.diligent.guide.suggestedName
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
 * The guided start: what to make, a name, a last look, then the kit recipe
 * runs and the project opens in the editor with a "Next step" card. Shown
 * once after the first view choice, and from New project.
 *
 * The run is the Setup screen's ([MobileActions.kit]), and its progress and
 * failure are drawn by [SetupContent], so the two never disagree. Only the
 * end differs: a guided run opens the project by itself, since a beginner
 * should not have to find the next button. Leaving mid-run is safe: Setup
 * shows the same run and its Open project.
 */
@Composable
fun GuidedStartScreen(shell: MobileShell, actions: MobileActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val kit = actions.kit
    val parent = remember(actions.projectsDir) { File(actions.projectsDir) }
    var step by rememberSaveable { mutableStateOf(GuideStep.Goal) }
    var goal by rememberSaveable { mutableStateOf<GuideGoal?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    // This screen started the run on screen, so its end is this screen's to handle.
    var started by rememberSaveable { mutableStateOf(false) }
    val run = kit.run?.takeIf { started }

    val back: () -> Unit = {
        val previous = step.previous
        if (run != null || previous == null) onClose() else step = previous
    }
    BackHandler(onBack = back)

    LaunchedEffect(run?.succeeded) {
        val done = run?.takeIf { it.succeeded } ?: return@LaunchedEffect
        val path = done.projectPath ?: File(actions.projectsDir, done.projectName).path
        kit.dismiss()
        started = false
        shell.nextStep = goal?.nextStep
        actions.openFolder(path)
        // The Run button is on the Editor tab's action bar, where the card points.
        actions.openTab(MobileTab.Editor)
        onClose()
    }

    Column(modifier.fillMaxSize()) {
        DetailHeader(stringResource(R.string.diligent_guide_header), onBack = back)
        Box(Modifier.weight(1f)) {
            when {
                run != null -> SetupContent(
                    recipes = null,
                    run = run,
                    onPick = {},
                    onCancel = kit::cancel,
                    // A finished run opens by itself (above); these are never reached for it.
                    onOpen = {},
                    onRun = {},
                    onTryAgain = {
                        kit.dismiss()
                        started = false
                        step = GuideStep.Confirm
                    },
                    onBack = {
                        kit.dismiss()
                        started = false
                    },
                )
                else -> GuideContent(
                    step = step,
                    goal = goal,
                    name = name,
                    parent = parent,
                    kitBusy = kit.busy,
                    onGoal = { picked ->
                        if (picked != goal || name.isEmpty()) {
                            name = suggestedName(picked) { File(parent, it).exists() }
                        }
                        goal = picked
                        step = GuideStep.Name
                    },
                    onName = { name = it },
                    onNext = { step = GuideStep.Confirm },
                    onBack = back,
                    onStart = {
                        goal?.let { chosen ->
                            started = true
                            kit.start(chosen.recipe, ProjectNames.clean(name))
                        }
                    },
                )
            }
        }
    }
}

/** One step of the guide, before the run, with the state held by the caller so previews show each one. */
@Composable
private fun GuideContent(
    step: GuideStep,
    goal: GuideGoal?,
    name: String,
    parent: File?,
    kitBusy: Boolean,
    onGoal: (GuideGoal) -> Unit,
    onName: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
        ) {
            when (step) {
                GuideStep.Goal -> GoalStep(goal, onGoal)
                GuideStep.Name -> NameStep(name, parent, onName, onNext, onBack)
                GuideStep.Confirm -> ConfirmStep(goal ?: GuideGoal.Website, name, kitBusy, onStart, onBack)
            }
        }
    }
}

@Composable
private fun StepHeading(text: String, @StringRes stepOf: Int) {
    Text(
        stringResource(stepOf),
        style = DiligentTheme.type.material.labelLarge,
        color = DiligentTheme.colors.primary,
    )
    Text(
        text,
        modifier = Modifier.semantics { heading() },
        style = DiligentTheme.type.material.headlineSmall,
        color = DiligentTheme.colors.onBackground,
    )
}

@Composable
private fun GoalStep(selected: GuideGoal?, onGoal: (GuideGoal) -> Unit) {
    StepHeading(stringResource(R.string.diligent_guide_goal_title), R.string.diligent_guide_step_1)
    for (goal in GuideGoal.entries) {
        Surface(
            onClick = { onGoal(goal) },
            modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget),
            color = if (goal == selected) DiligentTheme.colors.primaryContainer else DiligentTheme.colors.surfaceContainer,
            shape = DiligentTheme.shapes.medium,
        ) {
            Row(
                Modifier.padding(DiligentTheme.spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
            ) {
                Icon(goal.icon, contentDescription = null, tint = DiligentTheme.colors.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs)) {
                    Text(
                        stringResource(goal.title),
                        style = DiligentTheme.type.material.titleMedium,
                        color = DiligentTheme.colors.onSurface,
                    )
                    Text(
                        stringResource(goal.body),
                        style = DiligentTheme.type.material.bodyMedium,
                        color = DiligentTheme.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The name, checked like New project's; Next waits for the check of the name shown. */
@Composable
private fun NameStep(name: String, parent: File?, onName: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    var problem by remember { mutableStateOf<NameProblem?>(null) }
    var checked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name, parent) {
        problem = if (parent == null) ProjectNames.problem(name) { false } else withContext(Dispatchers.IO) { ProjectNames.problemIn(parent, name) }
        checked = name
    }
    val ready = checked == name && problem == null
    StepHeading(stringResource(R.string.diligent_guide_name_title), R.string.diligent_guide_step_2)
    Text(
        stringResource(R.string.diligent_guide_name_body),
        style = DiligentTheme.type.material.bodyMedium,
        color = DiligentTheme.colors.onSurfaceVariant,
    )
    val error = problem?.let { stringResource(it.messageRes()) }
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.diligent_projects_name)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onNext = { if (ready) onNext() }),
    )
    SheetButtons(
        confirm = stringResource(R.string.diligent_guide_next),
        confirmEnabled = ready,
        onConfirm = onNext,
        onCancel = onBack,
        cancel = stringResource(R.string.diligent_more_back),
    )
}

@Composable
private fun ConfirmStep(goal: GuideGoal, name: String, kitBusy: Boolean, onStart: () -> Unit, onBack: () -> Unit) {
    StepHeading(stringResource(R.string.diligent_guide_confirm_title), R.string.diligent_guide_step_3)
    Text(
        stringResource(R.string.diligent_guide_confirm_body, stringResource(goal.title), ProjectNames.clean(name)),
        style = DiligentTheme.type.material.bodyLarge,
        color = DiligentTheme.colors.onSurface,
    )
    Text(
        stringResource(goal.wait),
        style = DiligentTheme.type.material.bodyMedium,
        color = DiligentTheme.colors.onSurfaceVariant,
    )
    if (goal.beginner) {
        Text(
            stringResource(R.string.diligent_guide_confirm_beginner),
            style = DiligentTheme.type.material.bodyMedium,
            color = DiligentTheme.colors.onSurfaceVariant,
        )
    }
    if (kitBusy) {
        Text(
            stringResource(R.string.diligent_guide_kit_busy),
            style = DiligentTheme.type.material.bodyMedium,
            color = DiligentTheme.colors.failed,
        )
    }
    SheetButtons(
        confirm = stringResource(R.string.diligent_guide_start),
        confirmEnabled = !kitBusy,
        onConfirm = onStart,
        onCancel = onBack,
        cancel = stringResource(R.string.diligent_more_back),
    )
}

// The words and pictures of each goal, kept here so GuideGoal stays plain Kotlin.

@get:StringRes
private val GuideGoal.title: Int
    get() = when (this) {
        GuideGoal.Website -> R.string.diligent_guide_goal_website
        GuideGoal.React -> R.string.diligent_guide_goal_react
        GuideGoal.Python -> R.string.diligent_guide_goal_python
        GuideGoal.NewToCoding -> R.string.diligent_guide_goal_new
    }

@get:StringRes
private val GuideGoal.body: Int
    get() = when (this) {
        GuideGoal.Website -> R.string.diligent_guide_goal_website_body
        GuideGoal.React -> R.string.diligent_guide_goal_react_body
        GuideGoal.Python -> R.string.diligent_guide_goal_python_body
        GuideGoal.NewToCoding -> R.string.diligent_guide_goal_new_body
    }

/** How long it takes and whether it needs the internet, in a sentence. */
@get:StringRes
private val GuideGoal.wait: Int
    get() = when (this) {
        GuideGoal.React -> R.string.diligent_guide_wait_online
        else -> R.string.diligent_guide_wait_offline
    }

private val GuideGoal.icon: ImageVector
    get() = when (this) {
        GuideGoal.Website -> Icons.Outlined.Web
        GuideGoal.React -> Icons.Outlined.Language
        GuideGoal.Python -> Icons.Outlined.Code
        GuideGoal.NewToCoding -> Icons.Outlined.School
    }

// -- Previews --

@Composable
private fun PreviewGuide(step: GuideStep, goal: GuideGoal? = null, name: String = "my-site") = GuideContent(
    step, goal, name, parent = null, kitBusy = false,
    onGoal = {}, onName = {}, onNext = {}, onBack = {}, onStart = {},
)

@Preview(name = "Guide, goal, light", widthDp = 360, heightDp = 720)
@Composable
private fun GuideGoalLightPreview() = DiligentTheme(darkTheme = false) { PreviewGuide(GuideStep.Goal) }

@Preview(name = "Guide, goal, dark", widthDp = 360, heightDp = 720)
@Composable
private fun GuideGoalDarkPreview() = DiligentTheme(darkTheme = true) { PreviewGuide(GuideStep.Goal, GuideGoal.NewToCoding) }

@Preview(name = "Guide, name, light", widthDp = 360, heightDp = 480)
@Composable
private fun GuideNameLightPreview() = DiligentTheme(darkTheme = false) { PreviewGuide(GuideStep.Name, GuideGoal.Website) }

@Preview(name = "Guide, confirm, dark", widthDp = 360, heightDp = 480)
@Composable
private fun GuideConfirmDarkPreview() = DiligentTheme(darkTheme = true) { PreviewGuide(GuideStep.Confirm, GuideGoal.NewToCoding, "my-first-site") }

@Preview(name = "Guide, whole screen, light", widthDp = 360, heightDp = 720)
@Composable
private fun GuideScreenPreview() = DiligentTheme(darkTheme = false) {
    GuidedStartScreen(MobileShell(AppView.Mobile), PreviewActions, onClose = {})
}
