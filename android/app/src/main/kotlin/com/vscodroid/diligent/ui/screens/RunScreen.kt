package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.run.DetectedRun
import com.vscodroid.diligent.run.ProjectKind
import com.vscodroid.diligent.run.RunConfig
import com.vscodroid.diligent.run.RunConfigProblem
import com.vscodroid.diligent.run.RunConfigState
import com.vscodroid.diligent.run.RunControl
import com.vscodroid.diligent.run.RunPending
import com.vscodroid.diligent.run.RunProfile
import com.vscodroid.diligent.run.RunStatus
import com.vscodroid.diligent.run.RunUiState
import com.vscodroid.diligent.run.RunWhere
import com.vscodroid.diligent.run.WatchRun
import com.vscodroid.diligent.ui.components.StatusDot
import com.vscodroid.diligent.ui.components.StatusLabel
import com.vscodroid.diligent.ui.components.StatusTone
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * The Run screen (More, then Run): the open project's run profiles, Start or
 * Stop in thumb reach, the status, Preview while it runs on a port, and the
 * last 200 lines it printed.
 *
 * The More tab draws the header and the way back above this, so the screen
 * starts with its content. [onClose] is kept for a flow that should leave
 * when it is done.
 *
 * A project without `run.json` gets what was detected ("We found `npm run
 * dev` on port 3000. Use it?"), and nothing is written until the user says
 * yes or fills in the form.
 */
@Composable
fun RunScreen(shell: MobileShell, actions: MobileActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val run = actions.run
    WatchRun(run, withLog = true)
    val state = run.state
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        if (state.projectDir == null) {
            NoProject(onOpenProjects = { actions.openTab(MobileTab.Projects) })
        } else when (val config = state.config) {
            RunConfigState.Loading -> Loading()
            is RunConfigState.Invalid -> Invalid(config.problem, run::openConfig)
            is RunConfigState.Missing -> Proposal(config.proposal, state.saveFailed, run::save)
            is RunConfigState.Loaded -> Running(state, run)
        }
    }
}

// -- Profiles loaded: the main screen --

@Composable
private fun Running(state: RunUiState, run: RunControl) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            // Landscape: controls on the left with the buttons at the bottom, the log beside them.
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.42f).fillMaxHeight()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Details(state, run) }
                    Actions(state, run)
                }
                LogView(state.log, Modifier.weight(0.58f).fillMaxHeight().padding(DiligentTheme.spacing.s))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Details(state, run)
                LogView(
                    state.log,
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = DiligentTheme.spacing.m),
                )
                Actions(state, run)
            }
        }
    }
}

@Composable
private fun Details(state: RunUiState, run: RunControl) {
    Column(
        Modifier.padding(DiligentTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
    ) {
        if (state.profiles.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.profiles.forEach { p ->
                    FilterChip(
                        selected = p.name == state.active,
                        onClick = { run.select(p.name) },
                        label = { Text(p.name) },
                        leadingIcon = if (p.name in state.running) {
                            { StatusDot(StatusTone.Running) }
                        } else null,
                        modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget),
                    )
                }
            }
        }
        val (label, tone) = screenStatus(state)
        StatusLabel(label, tone)
        state.activeProfile?.let { p ->
            Text(
                p.command,
                style = DiligentTheme.type.code,
                color = DiligentTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        (state.where as? RunWhere.Laptop)?.let { laptop ->
            Text(
                stringResource(R.string.diligent_run_on_laptop, laptop.name),
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
        }
        if (state.status == RunStatus.NoTmux) {
            Text(
                stringResource(R.string.diligent_run_no_tmux_body),
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
        }
        if (state.startOutput.isNotEmpty()) {
            Text(
                stringResource(R.string.diligent_run_start_failed),
                style = DiligentTheme.type.material.labelLarge,
                color = DiligentTheme.colors.failed,
            )
            Text(state.startOutput.joinToString("\n"), style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurface)
        }
        if (state.webpackNote) WebpackNote(run::dismissWebpackNote)
        state.offerLaptop?.let { laptop -> OffloadNote(laptop, onRun = { run.runOnLaptop(laptop) }, onDismiss = run::dismissOffload) }
        TextButton(
            onClick = run::openConfig,
            modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget),
        ) { Text(stringResource(R.string.diligent_run_open_config)) }
    }
}

/** What the screen says about the run: more than the top bar, which stays quiet when nothing runs. */
@Composable
private fun screenStatus(state: RunUiState): Pair<String, StatusTone> {
    val port = state.shownPort
    return when {
        state.pending == RunPending.Starting -> stringResource(R.string.diligent_run_status_starting) to StatusTone.Warning
        state.pending == RunPending.Stopping -> stringResource(R.string.diligent_run_status_stopping) to StatusTone.Warning
        else -> when (val s = state.status) {
            RunStatus.Running ->
                if (port != null) stringResource(R.string.diligent_run_status_running_port, port) to StatusTone.Running
                else stringResource(R.string.diligent_run_status_running) to StatusTone.Running
            is RunStatus.Exited ->
                if (s.code == 0) stringResource(R.string.diligent_run_status_finished) to StatusTone.Neutral
                else stringResource(R.string.diligent_run_status_failed, s.code) to StatusTone.Failed
            RunStatus.Stopped -> stringResource(R.string.diligent_run_status_stopped) to StatusTone.Neutral
            RunStatus.NoTmux -> stringResource(R.string.diligent_run_status_no_tmux) to StatusTone.Failed
            RunStatus.Idle -> stringResource(R.string.diligent_run_status_idle) to StatusTone.Neutral
        }
    }
}

/** Start or Stop, big and at the bottom; Preview beside it while the app runs on a port. */
@Composable
private fun Actions(state: RunUiState, run: RunControl) {
    Row(
        Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
    ) {
        val bigButton = Modifier.weight(1f).heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
        val busy = state.pending != null
        if (state.isRunning || state.pending == RunPending.Stopping) {
            Button(
                onClick = run::stop,
                enabled = !busy,
                modifier = bigButton,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DiligentTheme.colors.error,
                    contentColor = DiligentTheme.colors.onError,
                ),
            ) {
                Icon(Icons.Outlined.Stop, contentDescription = null)
                Text(stringResource(R.string.diligent_run_stop), Modifier.padding(start = DiligentTheme.spacing.s))
            }
        } else {
            Button(onClick = run::start, enabled = !busy && state.activeProfile != null, modifier = bigButton) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(DiligentTheme.sizes.iconSmall), strokeWidth = DiligentTheme.sizes.divider * 2)
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                }
                Text(stringResource(R.string.diligent_run_start), Modifier.padding(start = DiligentTheme.spacing.s))
            }
        }
        if (state.previewPort != null) {
            OutlinedButton(onClick = run::openPreview, modifier = bigButton) {
                Icon(Icons.Outlined.Visibility, contentDescription = null)
                Text(stringResource(R.string.diligent_action_preview), Modifier.padding(start = DiligentTheme.spacing.s))
            }
        }
    }
}

@Composable
private fun WebpackNote(onDismiss: () -> Unit) {
    Surface(color = DiligentTheme.colors.primaryContainer, shape = DiligentTheme.shapes.medium) {
        Column(Modifier.padding(DiligentTheme.spacing.m)) {
            Text(
                stringResource(R.string.diligent_run_webpack_note),
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.onPrimaryContainer,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).heightIn(min = DiligentTheme.sizes.touchTarget),
            ) { Text(stringResource(R.string.diligent_run_note_ok)) }
        }
    }
}

/** The offload hint: the phone is struggling with this run, and a laptop is set up. */
@Composable
private fun OffloadNote(laptop: String, onRun: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = DiligentTheme.colors.primaryContainer, shape = DiligentTheme.shapes.medium) {
        Column(Modifier.padding(DiligentTheme.spacing.m)) {
            Text(
                stringResource(R.string.diligent_run_offload_body),
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.onPrimaryContainer,
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                    Text(stringResource(R.string.diligent_run_offload_not_now))
                }
                Button(onClick = onRun, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                    Text(stringResource(R.string.diligent_run_offload_action, laptop))
                }
            }
        }
    }
}

/**
 * The log, in monospace. It follows new lines while the user is at the
 * bottom; scrolling up to read stops that until they come back down or tap
 * the jump button.
 */
@Composable
private fun LogView(log: List<String>, modifier: Modifier) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(log, follow) {
        if (follow && log.isNotEmpty()) listState.scrollToItem(log.lastIndex)
    }
    Box(
        modifier
            .background(DiligentTheme.colors.surfaceContainer, DiligentTheme.shapes.medium)
            .padding(DiligentTheme.spacing.s),
    ) {
        if (log.isEmpty()) {
            Text(
                stringResource(R.string.diligent_run_log_empty),
                style = DiligentTheme.type.codeSmall,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(log) { line ->
                    Text(line, style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurface)
                }
            }
        }
        if (!follow && log.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.BottomEnd).size(DiligentTheme.sizes.touchTarget),
                containerColor = DiligentTheme.colors.surfaceContainerHigh,
            ) {
                Icon(Icons.Outlined.KeyboardDoubleArrowDown, stringResource(R.string.diligent_run_jump_to_end))
            }
        }
    }
}

// -- No profiles yet: the detected proposal, or the form --

@Composable
private fun Proposal(proposal: DetectedRun?, saveFailed: Boolean, onSave: (RunProfile) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (proposal == null || editing) {
        ProfileForm(
            initial = proposal?.profile,
            noneFound = proposal == null,
            saveFailed = saveFailed,
            onSave = onSave,
            onCancel = if (proposal != null) ({ editing = false }) else null,
        )
        return
    }
    BottomWeighted(
        content = {
            Text(
                stringResource(R.string.diligent_run_proposal_title),
                style = DiligentTheme.type.material.headlineSmall,
                color = DiligentTheme.colors.onSurface,
            )
            val p = proposal.profile
            Text(
                if (p.port != null) stringResource(R.string.diligent_run_proposal_port, p.command, p.port)
                else stringResource(R.string.diligent_run_proposal_no_port, p.command),
                style = DiligentTheme.type.material.bodyLarge,
                color = DiligentTheme.colors.onSurface,
            )
            if (proposal.webpackRewrite) {
                Text(
                    stringResource(R.string.diligent_run_webpack_note),
                    style = DiligentTheme.type.material.bodyMedium,
                    color = DiligentTheme.colors.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.diligent_run_proposal_saved_in),
                style = DiligentTheme.type.material.bodySmall,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
            if (saveFailed) SaveFailed()
        },
        actions = {
            val big = Modifier.weight(1f).heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
            OutlinedButton(onClick = { editing = true }, modifier = big) {
                Text(stringResource(R.string.diligent_run_edit))
            }
            Button(onClick = { onSave(proposal.profile) }, modifier = big) {
                Text(stringResource(R.string.diligent_run_accept))
            }
        },
    )
}

@Composable
private fun ProfileForm(
    initial: RunProfile?,
    noneFound: Boolean,
    saveFailed: Boolean,
    onSave: (RunProfile) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "dev") }
    var command by rememberSaveable { mutableStateOf(initial?.command.orEmpty()) }
    var port by rememberSaveable { mutableStateOf(initial?.port?.toString().orEmpty()) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val portValue = RunConfig.parsePortField(port)
    val valid = name.isNotBlank() && command.isNotBlank() && portValue.isSuccess
    val required = stringResource(R.string.diligent_run_form_required)

    BottomWeighted(
        content = {
            Text(
                stringResource(R.string.diligent_run_form_title),
                style = DiligentTheme.type.material.headlineSmall,
                color = DiligentTheme.colors.onSurface,
            )
            if (noneFound) {
                Text(
                    stringResource(R.string.diligent_run_form_none_found),
                    style = DiligentTheme.type.material.bodyMedium,
                    color = DiligentTheme.colors.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.diligent_run_form_name)) },
                singleLine = true,
                isError = tried && name.isBlank(),
                supportingText = if (tried && name.isBlank()) ({ Text(required) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text(stringResource(R.string.diligent_run_form_command)) },
                singleLine = true,
                textStyle = DiligentTheme.type.code,
                isError = tried && command.isBlank(),
                supportingText = if (tried && command.isBlank()) ({ Text(required) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.diligent_run_form_port)) },
                singleLine = true,
                isError = portValue.isFailure,
                supportingText = if (portValue.isFailure) ({ Text(stringResource(R.string.diligent_run_form_bad_port)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (saveFailed) SaveFailed()
        },
        actions = {
            val big = Modifier.weight(1f).heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
            if (onCancel != null) {
                OutlinedButton(onClick = onCancel, modifier = big) { Text(stringResource(R.string.diligent_run_form_cancel)) }
            }
            Button(
                onClick = {
                    tried = true
                    if (valid) {
                        onSave(
                            (initial ?: RunProfile(name = "", command = "")).copy(
                                name = name.trim(),
                                command = command.trim(),
                                port = portValue.getOrNull(),
                            ),
                        )
                    }
                },
                modifier = big,
            ) { Text(stringResource(R.string.diligent_run_form_save)) }
        },
    )
}

@Composable
private fun SaveFailed() {
    Text(
        stringResource(R.string.diligent_run_save_failed),
        style = DiligentTheme.type.material.bodyMedium,
        color = DiligentTheme.colors.failed,
    )
}

// -- Other states --

@Composable
private fun NoProject(onOpenProjects: () -> Unit) {
    BottomWeighted(
        content = {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = DiligentTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(DiligentTheme.sizes.iconLarge),
            )
            Text(
                stringResource(R.string.diligent_run_no_project_title),
                style = DiligentTheme.type.material.headlineSmall,
                color = DiligentTheme.colors.onSurface,
            )
            Text(
                stringResource(R.string.diligent_run_no_project_body),
                style = DiligentTheme.type.material.bodyLarge,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
        },
        actions = {
            Button(
                onClick = onOpenProjects,
                modifier = Modifier.weight(1f).heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s),
            ) { Text(stringResource(R.string.diligent_run_open_projects)) }
        },
    )
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.diligent_run_loading),
            style = DiligentTheme.type.material.bodyMedium,
            color = DiligentTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun Invalid(problem: RunConfigProblem, onOpenConfig: () -> Unit) {
    BottomWeighted(
        content = {
            Text(
                stringResource(R.string.diligent_run_invalid_title),
                style = DiligentTheme.type.material.headlineSmall,
                color = DiligentTheme.colors.onSurface,
            )
            Text(problemText(problem), style = DiligentTheme.type.material.bodyLarge, color = DiligentTheme.colors.failed)
            if (problem is RunConfigProblem.NotJson && problem.detail.isNotBlank()) {
                Text(problem.detail, style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurfaceVariant)
            }
        },
        actions = {
            Button(
                onClick = onOpenConfig,
                modifier = Modifier.weight(1f).heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s),
            ) { Text(stringResource(R.string.diligent_run_open_config)) }
        },
    )
}

@Composable
private fun problemText(problem: RunConfigProblem): String = when (problem) {
    is RunConfigProblem.NotJson -> stringResource(R.string.diligent_run_invalid_not_json)
    RunConfigProblem.NoProfiles -> stringResource(R.string.diligent_run_invalid_no_profiles)
    is RunConfigProblem.ProfileNotObject -> stringResource(R.string.diligent_run_invalid_profile_not_object, problem.index + 1)
    is RunConfigProblem.MissingName -> stringResource(R.string.diligent_run_invalid_missing_name, problem.index + 1)
    is RunConfigProblem.MissingCommand -> stringResource(R.string.diligent_run_invalid_missing_command, problem.index + 1)
    is RunConfigProblem.BadPort -> stringResource(R.string.diligent_run_invalid_bad_port, problem.index + 1)
    is RunConfigProblem.BadWhere -> stringResource(R.string.diligent_run_invalid_bad_where, problem.index + 1)
    is RunConfigProblem.BadEnv ->
        if (problem.key == null) stringResource(R.string.diligent_run_invalid_env_shape, problem.index + 1)
        else stringResource(R.string.diligent_run_invalid_bad_env, problem.index + 1, problem.key)
    is RunConfigProblem.DuplicateName -> stringResource(R.string.diligent_run_invalid_duplicate, problem.name)
}

/**
 * Text that scrolls at the top, buttons pinned at the bottom in thumb reach.
 * Width is capped so a landscape phone or a tablet does not stretch a line
 * of text across the whole screen.
 */
@Composable
private fun BottomWeighted(
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxHeight().widthIn(max = DiligentTheme.sizes.sheetMaxWidth)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(DiligentTheme.spacing.m),
                verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m, Alignment.Bottom),
                content = content,
            )
            Row(
                Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
                horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
                content = actions,
            )
        }
    }
}

// -- Previews --

private val sampleLog = listOf(
    "[diligent] \$ npm run dev -- --webpack",
    "",
    "> hello-next@0.1.0 dev",
    "> next dev --webpack",
    "",
    "   Next.js 16.0.0 (webpack)",
    "   - Local:        http://localhost:3000",
    " Ready in 4.2s",
    " GET / 200 in 812ms",
)

private val sampleConfig = RunConfigState.Loaded(
    RunConfig(listOf(RunProfile("dev", "npm run dev -- --webpack", 3000), RunProfile("api", "python3 main.py", 8000))),
)

private val sampleProject = RunUiState(
    projectDir = "/home/projects/hello-next",
    projectName = "hello-next",
    config = sampleConfig,
    active = "dev",
)

private val sampleRunning = sampleProject.copy(status = RunStatus.Running, running = setOf("dev"), log = sampleLog)

private val sampleProposal = RunUiState(
    projectDir = "/home/projects/hello-next",
    projectName = "hello-next",
    config = RunConfigState.Missing(
        DetectedRun(RunProfile("dev", "npm run dev -- --webpack", 3000), ProjectKind.NextJs, webpackRewrite = true),
    ),
)

@Composable
private fun RunSample(state: RunUiState) {
    RunScreen(MobileShell(null), PreviewRunActions(state), onClose = {})
}

@Preview(name = "Run idle, light", widthDp = 360, heightDp = 640)
@Composable
private fun RunIdleLightPreview() = DiligentTheme(darkTheme = false) { RunSample(sampleProject.copy(webpackNote = true)) }

@Preview(name = "Run idle, dark", widthDp = 360, heightDp = 640)
@Composable
private fun RunIdleDarkPreview() = DiligentTheme(darkTheme = true) { RunSample(sampleProject) }

@Preview(name = "Run running, light", widthDp = 360, heightDp = 640)
@Composable
private fun RunRunningLightPreview() = DiligentTheme(darkTheme = false) { RunSample(sampleRunning) }

@Preview(name = "Run running, dark", widthDp = 360, heightDp = 640)
@Composable
private fun RunRunningDarkPreview() = DiligentTheme(darkTheme = true) { RunSample(sampleRunning) }

@Preview(name = "Run running, landscape", widthDp = 720, heightDp = 360)
@Composable
private fun RunLandscapePreview() = DiligentTheme(darkTheme = false) { RunSample(sampleRunning) }

@Preview(name = "Run running, landscape dark", widthDp = 720, heightDp = 360)
@Composable
private fun RunLandscapeDarkPreview() = DiligentTheme(darkTheme = true) { RunSample(sampleRunning) }

@Preview(name = "Run failed, light", widthDp = 360, heightDp = 640)
@Composable
private fun RunFailedLightPreview() = DiligentTheme(darkTheme = false) {
    RunSample(sampleProject.copy(status = RunStatus.Exited(127), log = sampleLog.take(4) + "sh: next: command not found"))
}

@Preview(name = "Run failed, dark", widthDp = 360, heightDp = 640)
@Composable
private fun RunFailedDarkPreview() = DiligentTheme(darkTheme = true) {
    RunSample(sampleProject.copy(status = RunStatus.Exited(1), log = sampleLog.take(4) + "Error: listen EADDRINUSE :3000"))
}

@Preview(name = "Run proposal, light", widthDp = 360, heightDp = 640)
@Composable
private fun RunProposalLightPreview() = DiligentTheme(darkTheme = false) { RunSample(sampleProposal) }

@Preview(name = "Run proposal, dark", widthDp = 360, heightDp = 640)
@Composable
private fun RunProposalDarkPreview() = DiligentTheme(darkTheme = true) { RunSample(sampleProposal) }

@Preview(name = "Run proposal, landscape", widthDp = 720, heightDp = 360)
@Composable
private fun RunProposalLandscapePreview() = DiligentTheme(darkTheme = false) { RunSample(sampleProposal) }

@Preview(name = "Run nothing found, light", widthDp = 360, heightDp = 640)
@Composable
private fun RunFormLightPreview() = DiligentTheme(darkTheme = false) {
    RunSample(sampleProposal.copy(config = RunConfigState.Missing(null)))
}

@Preview(name = "Run nothing found, dark", widthDp = 360, heightDp = 640)
@Composable
private fun RunFormDarkPreview() = DiligentTheme(darkTheme = true) {
    RunSample(sampleProposal.copy(config = RunConfigState.Missing(null)))
}

@Preview(name = "Run invalid, dark", widthDp = 360, heightDp = 640)
@Composable
private fun RunInvalidDarkPreview() = DiligentTheme(darkTheme = true) {
    RunSample(sampleProposal.copy(config = RunConfigState.Invalid(RunConfigProblem.BadPort(0))))
}

@Preview(name = "Run no project, light", widthDp = 360, heightDp = 640)
@Composable
private fun RunNoProjectLightPreview() = DiligentTheme(darkTheme = false) { RunSample(RunUiState()) }

@Preview(name = "Run no project, dark", widthDp = 360, heightDp = 640)
@Composable
private fun RunNoProjectDarkPreview() = DiligentTheme(darkTheme = true) { RunSample(RunUiState()) }
