package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.kit.LanguagePack
import com.vscodroid.diligent.kit.Languages
import com.vscodroid.diligent.kit.PackFailure
import com.vscodroid.diligent.kit.PackQueue
import com.vscodroid.diligent.kit.PackRowState
import com.vscodroid.diligent.ui.theme.DiligentTheme
import com.vscodroid.util.StorageManager

/**
 * What the Languages list shows: every pack the app offers ([packs], from
 * ToolchainRegistry), which are [installed], the queue installing them, and
 * the free space where they go (null when the phone will not say).
 */
internal data class LanguagesUi(
    val packs: List<LanguagePack>,
    val installed: Set<String>,
    val queue: PackQueue?,
    val freeBytes: Long?,
) {
    val missing: List<String> get() = Languages.missing(packs, installed)
    val busy: Boolean get() = queue?.finished == false
    fun label(pack: String): String = packs.firstOrNull { it.pack == pack }?.label ?: pack.removePrefix("toolchain_")
}

/** The languages the app ships with, always installed: name and what it is for. */
private val builtIns = listOf(
    R.string.diligent_languages_node to R.string.diligent_languages_node_body,
    R.string.diligent_languages_python to R.string.diligent_languages_python_body,
    R.string.diligent_languages_git to R.string.diligent_languages_git_body,
)

/** The card at the top of the recipe list that opens the Languages list. */
@Composable
internal fun LanguagesCard(ui: LanguagesUi, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget),
        color = DiligentTheme.colors.surfaceContainerHigh,
        shape = DiligentTheme.shapes.medium,
    ) {
        Column(Modifier.padding(DiligentTheme.spacing.m), verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
            Text(
                stringResource(R.string.diligent_languages_title),
                modifier = Modifier.semantics { heading() },
                style = DiligentTheme.type.material.titleMedium,
                color = DiligentTheme.colors.onSurface,
            )
            val total = builtIns.size + ui.packs.size
            val installed = builtIns.size + ui.packs.count { it.pack in ui.installed }
            Text(
                ui.queue?.current?.let {
                    stringResource(R.string.diligent_languages_progress, ui.label(it), ui.queue.index + 1, ui.queue.packs.size)
                } ?: stringResource(R.string.diligent_languages_card_body, installed, total),
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(stringResource(R.string.diligent_languages_open))
            }
        }
    }
}

/**
 * Every language: the built-ins as installed, then each pack with its sizes,
 * where it stands and Install, and Set up all, which asks first with the
 * total download and the free space.
 */
@Composable
internal fun LanguagesList(
    ui: LanguagesUi,
    onInstall: (List<String>) -> Unit,
    onStop: () -> Unit,
    onDismissResult: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    val known = ui.packs.map { it.pack }

    Text(
        stringResource(R.string.diligent_languages_title),
        modifier = Modifier.semantics { heading() },
        style = DiligentTheme.type.material.headlineSmall,
        color = DiligentTheme.colors.onBackground,
    )
    LangBody(stringResource(R.string.diligent_languages_intro))

    val queue = ui.queue
    if (queue != null && !queue.finished) QueueProgress(ui, queue, onStop)
    if (queue != null && queue.finished) QueueResult(ui, queue, onDismissResult)

    when {
        ui.missing.isEmpty() -> LangBody(stringResource(R.string.diligent_languages_all_installed))
        !ui.busy -> Button(
            onClick = { confirmAll = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget),
        ) { Text(stringResource(R.string.diligent_languages_set_up_all)) }
    }

    LangSection(stringResource(R.string.diligent_languages_builtin_title))
    builtIns.forEach { (name, body) -> LanguageRow(stringResource(name), stringResource(body), null, builtIn = true) {} }

    LangSection(stringResource(R.string.diligent_languages_packs_title))
    ui.packs.forEach { pack ->
        val state = when {
            pack.pack in ui.installed -> PackRowState.Installed
            else -> queue?.stateOf(pack.pack)
        }
        val needs = Languages.toInstall(listOf(pack.pack), ui.installed, known)
        val details = buildList {
            add(stringResource(pack.descriptionRes))
            add(
                stringResource(
                    R.string.diligent_languages_sizes,
                    StorageManager.formatSize(pack.downloadBytes),
                    StorageManager.formatSize(pack.installBytes),
                ),
            )
            val others = needs.filter { it != pack.pack }
            if (others.isNotEmpty() && state != PackRowState.Installed) {
                add(stringResource(R.string.diligent_languages_also_installs, others.joinToString(", ") { ui.label(it) }))
            }
        }.joinToString("\n")
        LanguageRow(pack.label, details, state, percent = queue?.percent ?: 0, canInstall = !ui.busy) { onInstall(needs) }
    }

    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
        Text(stringResource(R.string.diligent_languages_back))
    }

    if (confirmAll) {
        val missing = ui.missing
        val totals = Languages.totals(ui.packs, missing)
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.diligent_languages_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
                    Text(
                        stringResource(
                            R.string.diligent_languages_confirm_body,
                            missing.joinToString(", ") { ui.label(it) },
                            StorageManager.formatSize(totals.downloadBytes),
                            StorageManager.formatSize(totals.spaceBytes),
                        ),
                    )
                    Text(
                        ui.freeBytes?.let { stringResource(R.string.diligent_languages_free, StorageManager.formatSize(it)) }
                            ?: stringResource(R.string.diligent_languages_free_unknown),
                    )
                    if (ui.freeBytes != null && ui.freeBytes < totals.spaceBytes) {
                        Text(stringResource(R.string.diligent_languages_low_space), color = DiligentTheme.colors.failed)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    onInstall(missing)
                }) { Text(stringResource(R.string.diligent_languages_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.diligent_languages_cancel)) }
            },
        )
    }
}

@Composable
private fun QueueProgress(ui: LanguagesUi, queue: PackQueue, onStop: () -> Unit) {
    val current = queue.current ?: return
    Surface(color = DiligentTheme.colors.surfaceContainer, shape = DiligentTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            Text(
                stringResource(R.string.diligent_languages_progress, ui.label(current), queue.index + 1, queue.packs.size),
                style = DiligentTheme.type.material.titleSmall,
                color = DiligentTheme.colors.onSurface,
            )
            LinearProgressIndicator(progress = { queue.percent / 100f }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(stringResource(R.string.diligent_languages_stop))
            }
        }
    }
}

@Composable
private fun QueueResult(ui: LanguagesUi, queue: PackQueue, onDismiss: () -> Unit) {
    Surface(color = DiligentTheme.colors.surfaceContainer, shape = DiligentTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            Text(
                stringResource(if (queue.cancelled) R.string.diligent_languages_cancelled_title else R.string.diligent_languages_done_title),
                modifier = Modifier.semantics { heading() },
                style = DiligentTheme.type.material.titleSmall,
                color = DiligentTheme.colors.onSurface,
            )
            LangBody(
                if (queue.installed.isEmpty()) stringResource(R.string.diligent_languages_done_none)
                else stringResource(R.string.diligent_languages_done_ok, queue.installed.joinToString(", ") { ui.label(it) }),
            )
            if (queue.failed.isNotEmpty()) {
                Text(
                    stringResource(R.string.diligent_languages_done_failed),
                    style = DiligentTheme.type.material.bodyMedium,
                    color = DiligentTheme.colors.failed,
                )
                queue.failed.forEach { failure: PackFailure ->
                    LangBody(
                        stringResource(
                            R.string.diligent_languages_failed_reason,
                            ui.label(failure.pack),
                            stringResource(failure.reason ?: R.string.diligent_languages_unknown_reason),
                        ),
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(stringResource(R.string.diligent_languages_ok))
            }
        }
    }
}

/** One language: name, details, and where it stands or an Install button. [state] null with no Install is a built-in. */
@Composable
private fun LanguageRow(
    name: String,
    details: String,
    state: PackRowState?,
    percent: Int = 0,
    canInstall: Boolean = false,
    builtIn: Boolean = false,
    onInstall: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DiligentTheme.colors.surfaceContainer,
        shape = DiligentTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(DiligentTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs)) {
                Text(name, style = DiligentTheme.type.material.titleMedium, color = DiligentTheme.colors.onSurface)
                LangBody(details)
            }
            when {
                builtIn || state == PackRowState.Installed -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs),
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = DiligentTheme.colors.running,
                        modifier = Modifier.size(DiligentTheme.sizes.icon),
                    )
                    Text(stringResource(R.string.diligent_languages_installed), style = DiligentTheme.type.material.labelMedium)
                }
                state == PackRowState.Installing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs),
                ) {
                    CircularProgressIndicator(Modifier.size(DiligentTheme.sizes.icon))
                    Text(stringResource(R.string.diligent_languages_installing, percent), style = DiligentTheme.type.material.labelMedium)
                }
                state == PackRowState.Waiting ->
                    Text(stringResource(R.string.diligent_languages_waiting), style = DiligentTheme.type.material.labelMedium)
                else -> Column(horizontalAlignment = Alignment.End) {
                    if (state == PackRowState.Failed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = DiligentTheme.colors.failed,
                                modifier = Modifier.size(DiligentTheme.sizes.icon),
                            )
                            Text(
                                stringResource(R.string.diligent_languages_failed),
                                style = DiligentTheme.type.material.labelMedium,
                                color = DiligentTheme.colors.failed,
                            )
                        }
                    }
                    val named = stringResource(R.string.diligent_languages_install_named, name)
                    FilledTonalButton(
                        onClick = onInstall,
                        enabled = canInstall,
                        modifier = Modifier
                            .heightIn(min = DiligentTheme.sizes.touchTarget)
                            .semantics { contentDescription = named },
                    ) { Text(stringResource(R.string.diligent_languages_install)) }
                }
            }
        }
    }
}

@Composable
private fun LangBody(text: String) {
    Text(text, style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.onSurfaceVariant)
}

@Composable
private fun LangSection(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = DiligentTheme.spacing.s).semantics { heading() },
        style = DiligentTheme.type.material.titleSmall,
        color = DiligentTheme.colors.onSurface,
    )
}

// -- Previews --

private val previewPacks = Languages.packs()

private val previewLanguages = LanguagesUi(
    packs = previewPacks,
    installed = setOf("toolchain_ruby"),
    queue = PackQueue(previewPacks.map { it.pack }.filter { it != "toolchain_ruby" }, percent = 40),
    freeBytes = 3_000_000_000L,
)

@Composable
private fun PreviewLanguages(ui: LanguagesUi) = Surface(color = DiligentTheme.colors.background) {
    Column(Modifier.padding(DiligentTheme.spacing.m), verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m)) {
        LanguagesList(ui, onInstall = {}, onStop = {}, onDismissResult = {}, onBack = {})
    }
}

@Preview(name = "Languages, installing, light", widthDp = 360, heightDp = 1100)
@Composable
private fun LanguagesInstallingLightPreview() = DiligentTheme(darkTheme = false) { PreviewLanguages(previewLanguages) }

@Preview(name = "Languages, failed, dark", widthDp = 360, heightDp = 1100)
@Composable
private fun LanguagesFailedDarkPreview() = DiligentTheme(darkTheme = true) {
    PreviewLanguages(
        previewLanguages.copy(
            queue = PackQueue(
                listOf("toolchain_java", "toolchain_cpp"),
                index = 2,
                installed = listOf("toolchain_java"),
                failed = listOf(PackFailure("toolchain_cpp", null)),
            ),
        ),
    )
}
