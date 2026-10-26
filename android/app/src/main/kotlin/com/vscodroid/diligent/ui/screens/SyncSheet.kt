package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vscodroid.R
import com.vscodroid.diligent.devices.ConflictChoice
import com.vscodroid.diligent.devices.DeviceLink
import com.vscodroid.diligent.devices.LinkRoute
import com.vscodroid.diligent.devices.SyncConflict
import com.vscodroid.diligent.devices.SyncUi
import com.vscodroid.diligent.ui.components.SheetButtons
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * A sync from [com.vscodroid.diligent.devices.DevicesState.sync] (Phase 5,
 * Part D): comparing, the plan to confirm, the transfer, the files changed
 * on both, and how it ended. Drawn by Mobile view's native layer, so a sync
 * started from the top bar shows on the Editor tab too.
 *
 * Conflicts take the whole screen, for a side-by-side compare; everything
 * else is a sheet. Closing it before the transfer is done cancels the sync;
 * during the transfer only Stop sync does, so a stray swipe cannot.
 *
 * [onOpenTerminal] is the install hint's button: it only opens the Terminal
 * tab. Nothing is run for the user.
 */
@Composable
fun SyncSheet(sync: SyncUi, link: DeviceLink, onOpenTerminal: () -> Unit) {
    if (sync is SyncUi.Conflicts) {
        ConflictDialog(sync, onChoose = link::resolveConflict, onStop = link::cancelSync)
        return
    }
    val finished = sync is SyncUi.Done || sync is SyncUi.Failed
    DiligentSheet(
        onDismiss = { if (finished) link.dismiss() else link.cancelSync() },
        locked = sync is SyncUi.Transferring,
    ) { hide ->
        SyncContent(
            sync = sync,
            onConfirm = link::confirmSync,
            onClose = { hide {} },
            onStop = link::cancelSync,
            onOpenTerminal = { hide(onOpenTerminal) },
        )
    }
}

@Composable
internal fun SyncContent(
    sync: SyncUi,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    onStop: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DiligentTheme.spacing.l)
            .padding(bottom = DiligentTheme.spacing.l)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        when (sync) {
            is SyncUi.Planning -> {
                DevicesTitle(stringResource(R.string.diligent_sync_planning_title, sync.deviceName))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                DevicesBody(stringResource(R.string.diligent_sync_planning_body))
                WideButton(R.string.diligent_devices_cancel, onClose, outlined = true)
            }
            is SyncUi.Plan -> Plan(sync, onConfirm, onClose)
            is SyncUi.Transferring -> {
                DevicesTitle(stringResource(R.string.diligent_sync_transferring_title, sync.deviceName))
                LinearProgressIndicator(progress = { progressOf(sync.doneFiles, sync.totalFiles) }, modifier = Modifier.fillMaxWidth())
                DevicesBody(pluralStringResource(R.plurals.diligent_sync_transferring_count, sync.totalFiles, sync.doneFiles, sync.totalFiles))
                WideButton(R.string.diligent_sync_stop, onStop, outlined = true)
            }
            is SyncUi.Done -> {
                DevicesTitle(stringResource(R.string.diligent_sync_done_title))
                DevicesBody(pluralStringResource(R.plurals.diligent_sync_done_sent, sync.sent, sync.sent, sync.deviceName))
                DevicesBody(pluralStringResource(R.plurals.diligent_sync_done_received, sync.received, sync.received, sync.deviceName))
                sync.installHint?.let { InstallHint(it, onOpenTerminal) }
                WideButton(R.string.diligent_pair_done, onClose)
            }
            is SyncUi.Failed -> {
                DevicesTitle(stringResource(R.string.diligent_sync_failed_title))
                DevicesBody(sync.reason)
                WideButton(R.string.diligent_devices_close, onClose)
            }
            // Shown full screen by SyncSheet; kept here so the when stays exhaustive.
            is SyncUi.Conflicts -> Unit
        }
    }
}

/** "3 files newer here, 1 file newer on Laptop, 1 file changed on both", each part opening its file list. */
@Composable
private fun ColumnScope.Plan(plan: SyncUi.Plan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    DevicesTitle(stringResource(R.string.diligent_sync_plan_title, plan.deviceName))
    DevicesBody(stringResource(viaRes(plan.route)), color = DiligentTheme.colors.running)
    val groups = planGroups(plan)
    if (groups.isEmpty()) {
        DevicesBody(stringResource(R.string.diligent_sync_plan_same))
        WideButton(R.string.diligent_devices_close, onCancel)
        return
    }
    val parts = groups.map { planPartText(it, plan.deviceName) }
    Text(
        parts.joinToString(stringResource(R.string.diligent_sync_plan_separator)),
        style = DiligentTheme.type.material.bodyLarge,
        color = DiligentTheme.colors.onSurface,
    )
    Surface(color = DiligentTheme.colors.surfaceContainer, shape = DiligentTheme.shapes.medium) {
        Column {
            groups.forEachIndexed { i, group ->
                if (i > 0) HorizontalDivider(thickness = DiligentTheme.sizes.divider, color = DiligentTheme.colors.outlineVariant)
                PlanGroupRow(parts[i], group.paths)
            }
        }
    }
    DevicesBody(stringResource(R.string.diligent_sync_plan_safety))
    SheetButtons(
        confirm = stringResource(R.string.diligent_sync_start),
        confirmEnabled = true,
        onConfirm = onConfirm,
        onCancel = onCancel,
        cancel = stringResource(R.string.diligent_devices_cancel),
    )
}

@Composable
private fun planPartText(group: PlanGroup, deviceName: String): String {
    val count = group.paths.size
    return if (group.part == PlanPart.NewerThere) {
        pluralStringResource(group.part.pluralRes(), count, count, deviceName)
    } else {
        pluralStringResource(group.part.pluralRes(), count, count)
    }
}

/** One kind of file: its count, and its paths once opened. */
@Composable
private fun PlanGroupRow(title: String, paths: List<String>) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.diligent_sync_hide_files else R.string.diligent_sync_show_files)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DiligentTheme.sizes.touchTarget)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = state }
                .padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.onSurface)
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = DiligentTheme.colors.onSurfaceVariant,
            )
        }
        if (open) {
            Column(Modifier.padding(start = DiligentTheme.spacing.m, end = DiligentTheme.spacing.m, bottom = DiligentTheme.spacing.s)) {
                for (path in paths) {
                    Text(path, style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** "Packages changed": the command, and a button that opens the Terminal for it. */
@Composable
private fun InstallHint(command: String, onOpenTerminal: () -> Unit) {
    DevicesCard {
        Text(
            stringResource(R.string.diligent_sync_install_title),
            modifier = Modifier.semantics { heading() },
            style = DiligentTheme.type.material.titleSmall,
            color = DiligentTheme.colors.onSurface,
        )
        DevicesBody(stringResource(R.string.diligent_sync_install_body, command))
        Text(command, style = DiligentTheme.type.code, color = DiligentTheme.colors.onSurface)
        FilledTonalButton(
            onClick = onOpenTerminal,
            modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget),
        ) { Text(stringResource(R.string.diligent_sync_install_button, command)) }
        DevicesBody(stringResource(R.string.diligent_sync_install_how))
    }
}

@Composable
private fun WideButton(label: Int, onClick: () -> Unit, outlined: Boolean = false) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(stringResource(label)) }
    } else {
        Button(onClick = onClick, modifier = modifier) { Text(stringResource(label)) }
    }
}

/** The compare screen, full screen, for the first file still to choose. Back stops the sync. */
@Composable
private fun ConflictDialog(sync: SyncUi.Conflicts, onChoose: (String, ConflictChoice) -> Unit, onStop: () -> Unit) {
    Dialog(onDismissRequest = onStop, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ConflictScreen(sync, onChoose, onStop)
    }
}

@Composable
internal fun ConflictScreen(sync: SyncUi.Conflicts, onChoose: (String, ConflictChoice) -> Unit, onStop: () -> Unit) {
    val conflict = sync.conflicts.firstOrNull()
    Surface(Modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.topBar).padding(start = DiligentTheme.spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    DevicesTitle(stringResource(R.string.diligent_sync_conflict_title))
                    DevicesBody(pluralStringResource(R.plurals.diligent_sync_conflicts_left, sync.conflicts.size, sync.conflicts.size))
                }
                IconButton(onClick = onStop, modifier = Modifier.size(DiligentTheme.sizes.touchTarget)) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.diligent_sync_stop), tint = DiligentTheme.colors.onSurface)
                }
            }
            if (conflict == null) return@Column
            Column(
                Modifier.padding(horizontal = DiligentTheme.spacing.m),
                verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs),
            ) {
                Text(conflict.path, style = DiligentTheme.type.code, color = DiligentTheme.colors.onSurface)
                DevicesBody(stringResource(R.string.diligent_sync_conflict_body, sync.deviceName))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(DiligentTheme.spacing.m)) {
                val mine = stringResource(R.string.diligent_sync_conflict_mine)
                val theirs = stringResource(R.string.diligent_sync_conflict_theirs, sync.deviceName)
                if (maxWidth >= DiligentTheme.sizes.splitMinWidth) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
                        ComparePane(mine, conflict.mine, Modifier.weight(1f))
                        ComparePane(theirs, conflict.theirs, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
                        ComparePane(mine, conflict.mine, Modifier.weight(1f))
                        ComparePane(theirs, conflict.theirs, Modifier.weight(1f))
                    }
                }
            }
            ChoiceButtons(onChoose = { onChoose(conflict.path, it) })
        }
    }
}

/** One side of the compare: who has it, then the text, monospace, scrolling both ways. */
@Composable
private fun ComparePane(label: String, text: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs)) {
        Text(
            label,
            modifier = Modifier.semantics { heading() },
            style = DiligentTheme.type.material.labelLarge,
            color = DiligentTheme.colors.primary,
        )
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = DiligentTheme.colors.surfaceContainer,
            shape = DiligentTheme.shapes.small,
        ) {
            val shown = when {
                text == null -> stringResource(R.string.diligent_sync_conflict_binary)
                text.isEmpty() -> stringResource(R.string.diligent_sync_conflict_empty)
                else -> text
            }
            Text(
                shown,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(DiligentTheme.spacing.s),
                style = if (text.isNullOrEmpty()) DiligentTheme.type.material.bodyMedium else DiligentTheme.type.codeSmall,
                color = if (text.isNullOrEmpty()) DiligentTheme.colors.onSurfaceVariant else DiligentTheme.colors.onSurface,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ChoiceButtons(onChoose: (ConflictChoice) -> Unit) {
    val sized = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DiligentTheme.spacing.m).padding(bottom = DiligentTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
            OutlinedButton(onClick = { onChoose(ConflictChoice.KeepMine) }, modifier = sized.weight(1f)) {
                Text(stringResource(R.string.diligent_sync_keep_mine))
            }
            OutlinedButton(onClick = { onChoose(ConflictChoice.KeepTheirs) }, modifier = sized.weight(1f)) {
                Text(stringResource(R.string.diligent_sync_keep_theirs))
            }
        }
        Button(onClick = { onChoose(ConflictChoice.KeepBoth) }, modifier = sized.fillMaxWidth()) {
            Text(stringResource(R.string.diligent_sync_keep_both))
        }
    }
}

// -- Previews --

private val SyncSamples = listOf(
    SyncUi.Planning("Toto's Laptop"),
    SyncUi.Plan(
        "Toto's Laptop",
        newerHere = listOf("src/App.tsx", "src/index.css", "README.md"),
        newerThere = listOf("package.json"),
        changedOnBoth = listOf("src/api.ts"),
        route = LinkRoute.Wifi,
    ),
    SyncUi.Plan("Galaxy Tab", emptyList(), emptyList(), emptyList(), LinkRoute.Tailscale),
    SyncUi.Transferring("Toto's Laptop", doneFiles = 2, totalFiles = 5),
    SyncUi.Done("Toto's Laptop", sent = 3, received = 1, installHint = "npm install"),
    SyncUi.Done("Galaxy Tab", sent = 1, received = 0, installHint = null),
    SyncUi.Failed("Toto's Laptop", "Toto's Laptop stopped answering. Check that it is still on the same Wi-Fi."),
)

private val PreviewConflicts = SyncUi.Conflicts(
    "Toto's Laptop",
    listOf(
        SyncConflict(
            "src/api.ts",
            mine = "export const base = \"/api\"\nexport const timeoutMs = 5000\n",
            theirs = "export const base = \"https://example.com/api\"\nexport const timeoutMs = 8000\nexport const retries = 2\n",
        ),
        SyncConflict("assets/logo.png", mine = null, theirs = null),
    ),
)

@Composable
private fun SyncSamplesColumn() {
    Column(verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
        for (sync in SyncSamples) {
            Surface(color = DiligentTheme.colors.scheme.surfaceContainerLow) {
                SyncContent(sync, {}, {}, {}, {}, Modifier.padding(top = DiligentTheme.spacing.l))
            }
        }
    }
}

@Preview(name = "Sync, every state, light", widthDp = 360, heightDp = 2400)
@Composable
private fun SyncLightPreview() = DiligentTheme(darkTheme = false) { SyncSamplesColumn() }

@Preview(name = "Sync, every state, dark", widthDp = 360, heightDp = 2400)
@Composable
private fun SyncDarkPreview() = DiligentTheme(darkTheme = true) { SyncSamplesColumn() }

@Preview(name = "Sync, plan, large font", widthDp = 360, heightDp = 1200, fontScale = 2f)
@Composable
private fun SyncLargeFontPreview() = DiligentTheme(darkTheme = false) {
    Surface(color = DiligentTheme.colors.scheme.surfaceContainerLow) { SyncContent(SyncSamples[1], {}, {}, {}, {}) }
}

@Preview(name = "Conflict, phone, light", widthDp = 360, heightDp = 720)
@Composable
private fun ConflictPhonePreview() = DiligentTheme(darkTheme = false) { ConflictScreen(PreviewConflicts, { _, _ -> }, {}) }

@Preview(name = "Conflict, phone, dark", widthDp = 360, heightDp = 720)
@Composable
private fun ConflictPhoneDarkPreview() = DiligentTheme(darkTheme = true) { ConflictScreen(PreviewConflicts, { _, _ -> }, {}) }

@Preview(name = "Conflict, tablet, dark", widthDp = 900, heightDp = 600)
@Composable
private fun ConflictWidePreview() = DiligentTheme(darkTheme = true) { ConflictScreen(PreviewConflicts, { _, _ -> }, {}) }

@Preview(name = "Conflict, binary, large font", widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun ConflictBinaryPreview() = DiligentTheme(darkTheme = false) {
    ConflictScreen(PreviewConflicts.copy(conflicts = PreviewConflicts.conflicts.drop(1)), { _, _ -> }, {})
}
