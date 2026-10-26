package com.vscodroid.diligent.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vscodroid.R
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.ai.AiAgent
import com.vscodroid.diligent.ai.AiAgentState
import com.vscodroid.diligent.ai.AiNotice
import com.vscodroid.diligent.ai.AiSessionRecord
import com.vscodroid.diligent.ai.AiSessions
import com.vscodroid.diligent.ai.AiToolStatus
import com.vscodroid.diligent.ai.AiUiState
import com.vscodroid.diligent.ui.components.StatusLabel
import com.vscodroid.diligent.ui.components.StatusTone
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** Opens VS Code's chat view, where GitHub Copilot Chat lives. */
internal const val COMMAND_OPEN_CHAT = "workbench.action.chat.open"

/** Opens the Extensions view with a search, for installing the Claude Code extension. */
internal const val COMMAND_SEARCH_EXTENSIONS = "workbench.extensions.search"
internal const val CLAUDE_EXTENSION_QUERY = "anthropic.claude-code"

/** A new terminal, and typing into the active one: how Attach runs `tmux attach`. */
internal const val COMMAND_NEW_TERMINAL = "workbench.action.terminal.new"
internal const val COMMAND_SEND_SEQUENCE = "workbench.action.terminal.sendSequence"

/**
 * AI (More, then AI): the terminal agents Claude Code (`claude`) and the
 * Antigravity CLI (`agy`), each run in tmux as `diligent-ai-<agent>-<project>`
 * after a snapshot, with Attach, Stop and "Undo changes since this session
 * started"; and Copilot Chat, which opens the editor's own chat view.
 *
 * Diligent installs neither agent: Claude Code comes from its VS Code
 * extension (VSCodroid's way, nothing redistributed), and for `agy` the screen
 * shows Google's install command to copy. Status is `<tool> --version` only;
 * no credential file is ever read.
 */
@Composable
fun AiScreen(shell: MobileShell, actions: MobileActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions = remember { AiSessions(context.applicationContext, scope) }
    LaunchedEffect(shell.folder, shell.projectName) { sessions.setProject(shell.folder, shell.projectName) }
    LifecycleResumeEffect(Unit) {
        sessions.refresh()
        onPauseOrDispose { }
    }
    AiContent(
        state = sessions.state,
        onStart = sessions::start,
        onStop = sessions::stop,
        onUndo = sessions::undo,
        onAttach = { agent ->
            sessions.attachCommand(agent)?.let { command ->
                actions.openTab(MobileTab.Terminal)
                actions.editorCommand(COMMAND_NEW_TERMINAL)
                actions.editorCommand(COMMAND_SEND_SEQUENCE, mapOf("text" to command + "\r"))
            }
        },
        onRefresh = sessions::refresh,
        onDismissNotice = sessions::dismissNotice,
        onOpenChat = {
            actions.openTab(MobileTab.Editor)
            actions.editorCommand(COMMAND_OPEN_CHAT)
        },
        onFindClaude = {
            actions.openTab(MobileTab.Editor)
            actions.editorCommand(COMMAND_SEARCH_EXTENSIONS, CLAUDE_EXTENSION_QUERY)
        },
        onCopy = { copyText(context, it) },
        modifier = modifier,
    )
}

@Composable
private fun AiContent(
    state: AiUiState,
    onStart: (AiAgent) -> Unit,
    onStop: (AiAgent) -> Unit,
    onUndo: (AiAgent) -> Unit,
    onAttach: (AiAgent) -> Unit,
    onRefresh: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenChat: () -> Unit,
    onFindClaude: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmStart by remember { mutableStateOf<AiAgent?>(null) }
    var confirmUndo by remember { mutableStateOf<AiAgent?>(null) }
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
        ) {
            Text(
                if (state.projectDir == null) {
                    stringResource(R.string.diligent_ai_no_project)
                } else {
                    stringResource(R.string.diligent_ai_intro, state.projectName.orEmpty())
                },
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
            state.notice?.let { NoticeCard(it, onDismissNotice) }
            for (agent in AiAgent.entries) {
                AgentCard(
                    agent = agent,
                    agentState = state.of(agent),
                    hasProject = state.projectDir != null,
                    busy = state.busy == agent,
                    anyBusy = state.busy != null,
                    onStart = { confirmStart = agent },
                    onStop = { onStop(agent) },
                    onAttach = { onAttach(agent) },
                    onUndo = { confirmUndo = agent },
                    onFindClaude = onFindClaude,
                    onCopy = onCopy,
                )
            }
            CopilotCard(onOpenChat)
            TextButton(onClick = onRefresh, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.diligent_ai_check_again))
            }
        }
    }
    confirmStart?.let { agent ->
        AlertDialog(
            onDismissRequest = { confirmStart = null },
            title = { Text(stringResource(R.string.diligent_ai_start_title, stringResource(agent.title))) },
            text = { Text(stringResource(R.string.diligent_ai_start_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = null
                    onStart(agent)
                }) { Text(stringResource(R.string.diligent_ai_start)) }
            },
            dismissButton = { TextButton(onClick = { confirmStart = null }) { Text(stringResource(R.string.diligent_ai_cancel)) } },
        )
    }
    confirmUndo?.let { agent ->
        AlertDialog(
            onDismissRequest = { confirmUndo = null },
            title = { Text(stringResource(R.string.diligent_ai_undo_title)) },
            text = { Text(stringResource(R.string.diligent_ai_undo_body, stringResource(agent.title))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUndo = null
                    onUndo(agent)
                }) { Text(stringResource(R.string.diligent_ai_undo_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = null }) { Text(stringResource(R.string.diligent_ai_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentCard(
    agent: AiAgent,
    agentState: AiAgentState,
    hasProject: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onUndo: () -> Unit,
    onFindClaude: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Card {
        Text(
            stringResource(agent.title),
            modifier = Modifier.semantics { heading() },
            style = DiligentTheme.type.material.titleMedium,
            color = DiligentTheme.colors.onSurface,
        )
        Text(
            stringResource(agent.body),
            style = DiligentTheme.type.material.bodyMedium,
            color = DiligentTheme.colors.onSurfaceVariant,
        )
        val (label, tone) = statusText(agentState)
        StatusLabel(label, tone)
        when (val status = agentState.status) {
            AiToolStatus.NotInstalled -> InstallHelp(agent, onFindClaude, onCopy)
            is AiToolStatus.Broken -> if (status.detail.isNotEmpty()) {
                Text(status.detail, style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurfaceVariant)
            }
            else -> Unit
        }
        val installed = agentState.status is AiToolStatus.Installed
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            if (agentState.running) {
                Button(onClick = onAttach, enabled = !busy) { Text(stringResource(R.string.diligent_ai_attach)) }
                OutlinedButton(onClick = onStop, enabled = !anyBusy) { Text(stringResource(R.string.diligent_ai_stop)) }
            } else {
                Button(onClick = onStart, enabled = hasProject && installed && !anyBusy) {
                    Text(stringResource(if (busy) R.string.diligent_ai_starting else R.string.diligent_ai_start))
                }
            }
        }
        agentState.record?.let { record ->
            UndoRow(record, enabled = !anyBusy, onUndo = onUndo)
        }
    }
}

@Composable
private fun UndoRow(record: AiSessionRecord, enabled: Boolean, onUndo: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs)) {
        Text(
            stringResource(R.string.diligent_ai_snapshot, record.commit.take(7)),
            style = DiligentTheme.type.material.bodySmall,
            color = DiligentTheme.colors.onSurfaceVariant,
        )
        OutlinedButton(onClick = onUndo, enabled = enabled) { Text(stringResource(R.string.diligent_ai_undo)) }
    }
}

@Composable
private fun InstallHelp(agent: AiAgent, onFindClaude: () -> Unit, onCopy: (String) -> Unit) {
    when (agent) {
        AiAgent.Claude -> {
            Text(stringResource(R.string.diligent_ai_claude_install), style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.onSurface)
            OutlinedButton(onClick = onFindClaude) { Text(stringResource(R.string.diligent_ai_claude_find)) }
        }
        AiAgent.Antigravity -> {
            Text(stringResource(R.string.diligent_ai_agy_install), style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.onSurface)
            val command = stringResource(R.string.diligent_ai_agy_install_command)
            Surface(color = DiligentTheme.colors.surfaceContainerHigh, shape = DiligentTheme.shapes.small) {
                Row(Modifier.padding(start = DiligentTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(command, style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurface)
                    }
                    IconButton(onClick = { onCopy(command) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.diligent_ai_copy))
                    }
                }
            }
            Text(stringResource(R.string.diligent_ai_agy_note), style = DiligentTheme.type.material.bodySmall, color = DiligentTheme.colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun CopilotCard(onOpenChat: () -> Unit) {
    Card {
        Text(
            stringResource(R.string.diligent_ai_copilot),
            modifier = Modifier.semantics { heading() },
            style = DiligentTheme.type.material.titleMedium,
            color = DiligentTheme.colors.onSurface,
        )
        Text(stringResource(R.string.diligent_ai_copilot_body), style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.onSurfaceVariant)
        Button(onClick = onOpenChat) { Text(stringResource(R.string.diligent_ai_copilot_open)) }
    }
}

@Composable
private fun NoticeCard(notice: AiNotice, onDismiss: () -> Unit) {
    val (text, tone) = when (notice) {
        AiNotice.NoTmux -> stringResource(R.string.diligent_ai_notice_no_tmux) to StatusTone.Failed
        is AiNotice.StartFailed -> (listOf(stringResource(R.string.diligent_ai_notice_start_failed)) + notice.lines).joinToString("\n") to StatusTone.Failed
        AiNotice.SnapshotFailed -> stringResource(R.string.diligent_ai_notice_snapshot_failed) to StatusTone.Failed
        AiNotice.Undone -> stringResource(R.string.diligent_ai_notice_undone) to StatusTone.Running
        AiNotice.UndoFailed -> stringResource(R.string.diligent_ai_notice_undo_failed) to StatusTone.Failed
    }
    Card {
        StatusLabel(text, tone)
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.diligent_ai_dismiss)) }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = DiligentTheme.colors.surfaceContainer, shape = DiligentTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) { content() }
    }
}

@Composable
private fun statusText(s: AiAgentState): Pair<String, StatusTone> = when (val status = s.status) {
    AiToolStatus.Unknown -> stringResource(R.string.diligent_ai_status_checking) to StatusTone.Neutral
    AiToolStatus.NotInstalled -> stringResource(R.string.diligent_ai_status_missing) to StatusTone.Warning
    is AiToolStatus.Broken -> stringResource(R.string.diligent_ai_status_broken, status.code) to StatusTone.Failed
    is AiToolStatus.Installed -> stringResource(
        if (s.running) R.string.diligent_ai_status_running else R.string.diligent_ai_status_installed,
        status.version,
    ) to (if (s.running) StatusTone.Running else StatusTone.Neutral)
}

private val AiAgent.title: Int
    get() = when (this) {
        AiAgent.Claude -> R.string.diligent_ai_claude
        AiAgent.Antigravity -> R.string.diligent_ai_agy
    }

private val AiAgent.body: Int
    get() = when (this) {
        AiAgent.Claude -> R.string.diligent_ai_claude_body
        AiAgent.Antigravity -> R.string.diligent_ai_agy_body
    }

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("command", text))
    // Android 13+ confirms a copy itself; earlier versions say nothing.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.diligent_ai_copied, Toast.LENGTH_SHORT).show()
    }
}

private val sampleCommit = "0123456789abcdef0123456789abcdef01234567"

private val sampleState = AiUiState(
    projectDir = "/home/projects/hello-next",
    projectName = "hello-next",
    agents = mapOf(
        AiAgent.Claude to AiAgentState(
            status = AiToolStatus.Installed("2.1.3"),
            running = true,
            record = AiSessionRecord("/home/projects/hello-next", sampleCommit, 0L),
        ),
        AiAgent.Antigravity to AiAgentState(status = AiToolStatus.NotInstalled),
    ),
)

@Composable
private fun AiSample(state: AiUiState) = AiContent(
    state = state,
    onStart = {}, onStop = {}, onUndo = {}, onAttach = {}, onRefresh = {}, onDismissNotice = {},
    onOpenChat = {}, onFindClaude = {}, onCopy = {},
)

@Preview(name = "AI, light", widthDp = 360, heightDp = 900)
@Composable
private fun AiLightPreview() = DiligentTheme(darkTheme = false) { AiSample(sampleState) }

@Preview(name = "AI, dark, Claude missing", widthDp = 360, heightDp = 900)
@Composable
private fun AiDarkPreview() = DiligentTheme(darkTheme = true) {
    AiSample(sampleState.copy(agents = mapOf(AiAgent.Claude to AiAgentState(status = AiToolStatus.NotInstalled))))
}

@Preview(name = "AI, no project", widthDp = 360, heightDp = 640)
@Composable
private fun AiNoProjectPreview() = DiligentTheme(darkTheme = false) {
    AiSample(AiUiState())
}
