package com.vscodroid.diligent.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.vscodroid.diligent.health.HealthCheck
import com.vscodroid.diligent.health.HealthFacts
import com.vscodroid.diligent.health.HealthFix
import com.vscodroid.diligent.health.HealthProbe
import com.vscodroid.diligent.health.HealthRules
import com.vscodroid.diligent.health.HealthStatus
import com.vscodroid.diligent.health.HealthText
import com.vscodroid.diligent.ui.components.StatusLabel
import com.vscodroid.diligent.ui.components.StatusTone
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * Health (More, then Health; also the notification after Android kills a
 * task): whether the phone lets what the user starts keep running. Each check
 * says passed, needs a fix, or cannot check, with a button to the setting
 * that fixes it. The decisions are [HealthRules]; this only reads the phone
 * ([HealthProbe]) and draws them.
 *
 * Checked again every time the screen resumes, which is how the user comes
 * back from a Fix, so the result follows what they just changed.
 *
 * [onOpenRun] opens the Run screen for the process count's fix; null where
 * there is none (Desktop view), which hides that button.
 */
@Composable
fun HealthScreen(onOpenRun: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var facts by remember { mutableStateOf<HealthFacts?>(null) }
    LifecycleResumeEffect(Unit) {
        facts = HealthProbe.read(context)
        onPauseOrDispose { }
    }
    val shown = facts ?: return
    HealthContent(
        facts = shown,
        onFix = { fix -> if (fix == HealthFix.RunScreen) onOpenRun?.invoke() else HealthProbe.open(context, fix) },
        canOpenRun = onOpenRun != null,
        onCopy = { copy(context, it) },
        onCheckAgain = { facts = HealthProbe.read(context) },
        modifier = modifier,
    )
}

@Composable
private fun HealthContent(
    facts: HealthFacts,
    onFix: (HealthFix) -> Unit,
    canOpenRun: Boolean,
    onCopy: (String) -> Unit,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
        ) {
            Text(
                stringResource(R.string.diligent_health_intro),
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
            for (check in HealthRules.evaluate(facts)) {
                val fix = check.fix?.takeIf { it != HealthFix.RunScreen || canOpenRun }
                CheckCard(check.copy(fix = fix), facts, onFix, onCopy)
            }
            TextButton(onClick = onCheckAgain, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.diligent_health_check_again))
            }
        }
    }
}

@Composable
private fun CheckCard(check: HealthCheck, facts: HealthFacts, onFix: (HealthFix) -> Unit, onCopy: (String) -> Unit) {
    Surface(color = DiligentTheme.colors.surfaceContainer, shape = DiligentTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(HealthText.titleRes(check.topic)),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = DiligentTheme.type.material.titleSmall,
                    color = DiligentTheme.colors.onSurface,
                )
                StatusLabel(stringResource(check.status.label), check.status.tone)
            }
            Text(
                body(check, facts),
                style = DiligentTheme.type.material.bodyMedium,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
            if (check.steps) {
                Text(
                    stringResource(R.string.diligent_health_children_steps),
                    style = DiligentTheme.type.material.bodyMedium,
                    color = DiligentTheme.colors.onSurface,
                )
            }
            check.commands.forEach { CommandRow(it, onCopy) }
            check.fix?.let { fix ->
                Button(
                    onClick = { onFix(fix) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget),
                ) { Text(stringResource(fix.label)) }
            }
        }
    }
}

/** One adb command in code type, selectable, with a copy button. */
@Composable
private fun CommandRow(command: String, onCopy: (String) -> Unit) {
    Surface(color = DiligentTheme.colors.surfaceContainerHigh, shape = DiligentTheme.shapes.small) {
        Row(
            Modifier.fillMaxWidth().padding(start = DiligentTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionContainer(Modifier.weight(1f).padding(vertical = DiligentTheme.spacing.s)) {
                Text(command, style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurface)
            }
            IconButton(onClick = { onCopy(command) }, modifier = Modifier.size(DiligentTheme.sizes.touchTarget)) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.diligent_health_copy),
                    tint = DiligentTheme.colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** The sentence under a check's title, for its status. */
@Composable
private fun body(check: HealthCheck, facts: HealthFacts): String = HealthText.detail(LocalContext.current, check, facts)

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", text))
    // Android 13+ confirms a copy itself; earlier versions say nothing.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.diligent_health_copied, Toast.LENGTH_SHORT).show()
    }
}

private val HealthStatus.label: Int
    get() = when (this) {
        HealthStatus.Pass -> R.string.diligent_health_pass
        HealthStatus.Fail -> R.string.diligent_health_fail
        HealthStatus.CannotCheck -> R.string.diligent_health_cannot_check
    }

private val HealthStatus.tone: StatusTone
    get() = when (this) {
        HealthStatus.Pass -> StatusTone.Running
        HealthStatus.Fail -> StatusTone.Failed
        HealthStatus.CannotCheck -> StatusTone.Warning
    }

private val HealthFix.label: Int
    get() = when (this) {
        HealthFix.BatterySettings -> R.string.diligent_health_fix_battery
        HealthFix.DeveloperOptions -> R.string.diligent_health_fix_developer
        HealthFix.NotificationSettings -> R.string.diligent_health_fix_notifications
        HealthFix.StorageSettings -> R.string.diligent_health_fix_storage
        HealthFix.RunScreen -> R.string.diligent_health_fix_run
    }

private val PreviewFactsAndroid14 = HealthFacts(
    sdk = 34,
    ignoringBatteryOptimizations = false,
    phantomMonitor = null,
    notificationsEnabled = true,
    freeBytes = 12L * 1024 * 1024 * 1024,
    processes = 26,
)

private val PreviewFactsAndroid13 = HealthFacts(
    sdk = 33,
    ignoringBatteryOptimizations = true,
    phantomMonitor = null,
    notificationsEnabled = false,
    freeBytes = 600L * 1024 * 1024,
    processes = null,
)

@Preview(name = "Health, Android 14, light", widthDp = 360, heightDp = 1100)
@Composable
private fun HealthLightPreview() = DiligentTheme(darkTheme = false) {
    HealthContent(PreviewFactsAndroid14, onFix = {}, canOpenRun = true, onCopy = {}, onCheckAgain = {})
}

@Preview(name = "Health, Android 14, dark", widthDp = 360, heightDp = 1100)
@Composable
private fun HealthDarkPreview() = DiligentTheme(darkTheme = true) {
    HealthContent(PreviewFactsAndroid14, onFix = {}, canOpenRun = true, onCopy = {}, onCheckAgain = {})
}

@Preview(name = "Health, Android 13 adb, light", widthDp = 360, heightDp = 1200)
@Composable
private fun HealthAdbLightPreview() = DiligentTheme(darkTheme = false) {
    HealthContent(PreviewFactsAndroid13, onFix = {}, canOpenRun = true, onCopy = {}, onCheckAgain = {})
}

@Preview(name = "Health, Android 13 adb, dark", widthDp = 360, heightDp = 1200)
@Composable
private fun HealthAdbDarkPreview() = DiligentTheme(darkTheme = true) {
    HealthContent(PreviewFactsAndroid13, onFix = {}, canOpenRun = true, onCopy = {}, onCheckAgain = {})
}
