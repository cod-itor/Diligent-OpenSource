package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.run.ErrorCatalog
import com.vscodroid.diligent.run.ErrorExplanation
import com.vscodroid.diligent.run.ErrorFix
import com.vscodroid.diligent.run.RunControl
import com.vscodroid.diligent.run.RunStatus
import com.vscodroid.diligent.run.RunUiState
import com.vscodroid.diligent.ui.navigation.MobileTab
import com.vscodroid.diligent.ui.navigation.MoreDetail
import com.vscodroid.diligent.ui.theme.DiligentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Run screen's plain-language explanation: when the run ended with a
 * nonzero exit, or its log shows a known error, the kit's errors.json says
 * what it means, with a button for each fix the app can carry out.
 */
@Composable
internal fun RunErrorCard(state: RunUiState, run: RunControl, actions: MobileActions, onOpen: (MoreDetail) -> Unit) {
    val context = LocalContext.current
    val catalog by produceState<ErrorCatalog?>(null) { value = withContext(Dispatchers.IO) { ErrorCatalog.load(context.assets) } }
    val exitCode = (state.status as? RunStatus.Exited)?.code?.takeIf { it != 0 }
    val explanation = remember(catalog, state.log, exitCode) {
        catalog?.explain(state.log.joinToString("\n"), exitCode)?.firstOrNull()
    } ?: return
    ErrorCardContent(explanation) { fix -> fixAction(fix, state, run, actions, onOpen) }
}

/** What a fix's button does here, or null when the app has nothing for it (the button is left out). */
private fun fixAction(
    fix: ErrorFix,
    state: RunUiState,
    run: RunControl,
    actions: MobileActions,
    onOpen: (MoreDetail) -> Unit,
): (() -> Unit)? = when (fix.action) {
    "openHealth" -> { { onOpen(MoreDetail.Health) } }
    "openStorage" -> { { onOpen(MoreDetail.Storage) } }
    "stopTask" -> state.running.firstOrNull { it != state.active }?.let { other -> { run.select(other); run.stop() } }
    "openFileAtLine" -> fix.file?.let { file ->
        val path = if (File(file).isAbsolute || state.projectDir == null) file else File(state.projectDir, file).path
        ({ actions.openFile(path) })
    }
    "runRecipe", "openSetup" -> { { onOpen(MoreDetail.Setup) } }
    "runOnLaptop" -> { { onOpen(MoreDetail.Laptop) } }
    // Low-memory mode is switched in Settings.
    "lowMemory" -> { { onOpen(MoreDetail.Settings) } }
    "openProject" -> { { actions.openTab(MobileTab.Projects) } }
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorCardContent(explanation: ErrorExplanation, actionFor: (ErrorFix) -> (() -> Unit)?) {
    Surface(color = DiligentTheme.colors.scheme.errorContainer, shape = DiligentTheme.shapes.medium) {
        Column(Modifier.padding(DiligentTheme.spacing.m), verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
            Text(
                explanation.title,
                modifier = Modifier.semantics { heading() },
                style = DiligentTheme.type.material.titleSmall,
                color = DiligentTheme.colors.scheme.onErrorContainer,
            )
            Text(
                explanation.explanation,
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.scheme.onErrorContainer,
            )
            val buttons = explanation.fixes.mapNotNull { fix -> actionFor(fix)?.let { fix.label to it } }
            if (buttons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
                    buttons.forEach { (label, onClick) ->
                        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}
