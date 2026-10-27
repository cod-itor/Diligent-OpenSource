package com.vscodroid.diligent.ui.screens

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vscodroid.R
import com.vscodroid.diligent.storage.CleanupItem
import com.vscodroid.diligent.storage.CleanupKind
import com.vscodroid.diligent.storage.StorageReport
import com.vscodroid.diligent.storage.StorageScanner
import com.vscodroid.diligent.storage.UsageRow
import com.vscodroid.diligent.ui.components.ListRow
import com.vscodroid.diligent.ui.components.SectionHeader
import com.vscodroid.diligent.ui.theme.DiligentTheme
import com.vscodroid.util.StorageManager
import kotlinx.coroutines.launch

/**
 * Storage (More, then Storage): what uses space on the phone, and what can
 * be freed without losing work.
 *
 * Measured each time the screen comes back, since a terminal may have
 * installed packages meanwhile. Every Delete names the size and asks first;
 * nothing here removes a source file (StorageCleanup.mayDelete).
 */
@Composable
fun StorageScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scanner = remember { StorageScanner(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    val rescan: () -> Unit = {
        scope.launch {
            busy = true
            report = scanner.scan()
            busy = false
        }
    }
    LifecycleResumeEffect(Unit) {
        rescan()
        onPauseOrDispose { }
    }
    StorageContent(
        report = report,
        busy = busy,
        onDelete = { item ->
            scope.launch {
                busy = true
                val freed = scanner.delete(item)
                val message = if (freed == null) {
                    resources.getString(R.string.diligent_storage_delete_failed)
                } else {
                    resources.getString(R.string.diligent_storage_freed, StorageManager.formatSize(freed))
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                report = scanner.scan()
                busy = false
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun StorageContent(
    report: StorageReport?,
    busy: Boolean,
    onDelete: (CleanupItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf<CleanupItem?>(null) }
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        if (report == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.diligent_storage_measuring),
                        modifier = Modifier.padding(top = DiligentTheme.spacing.m),
                        style = DiligentTheme.type.material.bodyMedium,
                        color = DiligentTheme.colors.onSurfaceVariant,
                    )
                }
            }
            return@Surface
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                stringResource(
                    R.string.diligent_storage_summary,
                    StorageManager.formatSize(report.total),
                    StorageManager.formatSize(report.free),
                ),
                modifier = Modifier.padding(DiligentTheme.spacing.m),
                style = DiligentTheme.type.material.titleMedium,
                color = DiligentTheme.colors.onSurface,
            )
            SectionHeader(stringResource(R.string.diligent_storage_section_free))
            if (report.items.isEmpty()) {
                Text(
                    stringResource(R.string.diligent_storage_nothing),
                    modifier = Modifier.padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
                    style = DiligentTheme.type.material.bodyMedium,
                    color = DiligentTheme.colors.onSurfaceVariant,
                )
            }
            for (item in report.items) {
                ListRow(
                    title = itemTitle(item),
                    icon = null,
                    subtitle = stringResource(
                        R.string.diligent_storage_item_body,
                        StorageManager.formatSize(item.bytes),
                        stringResource(item.kind.body),
                    ),
                    trailing = {
                        TextButton(onClick = { confirm = item }, enabled = !busy) {
                            Text(stringResource(R.string.diligent_storage_delete))
                        }
                    },
                )
            }
            SectionHeader(stringResource(R.string.diligent_storage_section_used))
            for (row in report.usage) {
                ListRow(
                    title = stringResource(usageTitle(row)),
                    icon = null,
                    trailing = {
                        Text(
                            StorageManager.formatSize(row.bytes),
                            style = DiligentTheme.type.material.bodyMedium,
                            color = DiligentTheme.colors.onSurfaceVariant,
                        )
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (busy) CircularProgressIndicator()
            }
        }
    }
    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.diligent_storage_confirm_title, itemTitle(item))) },
            text = {
                Text(
                    stringResource(
                        R.string.diligent_storage_confirm_body,
                        StorageManager.formatSize(item.bytes),
                        stringResource(item.kind.body),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onDelete(item)
                }) { Text(stringResource(R.string.diligent_storage_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.diligent_storage_cancel)) } },
        )
    }
}

@Composable
private fun itemTitle(item: CleanupItem): String = when (item.kind) {
    CleanupKind.NodeModules -> stringResource(R.string.diligent_storage_node_modules, item.project.orEmpty())
    CleanupKind.NpmCache -> stringResource(R.string.diligent_storage_npm_cache)
    CleanupKind.PipCache -> stringResource(R.string.diligent_storage_pip_cache)
    CleanupKind.AllCaches -> stringResource(R.string.diligent_storage_all_caches)
    CleanupKind.OldSnapshots -> pluralStringResource(R.plurals.diligent_storage_old_snapshots, item.count, item.count, item.project.orEmpty())
    CleanupKind.OldRunLogs -> pluralStringResource(R.plurals.diligent_storage_old_logs, item.count, item.count)
}

/** What happens after the delete, in plain words. */
@get:StringRes
private val CleanupKind.body: Int
    get() = when (this) {
        CleanupKind.NodeModules -> R.string.diligent_storage_node_modules_body
        CleanupKind.NpmCache -> R.string.diligent_storage_npm_cache_body
        CleanupKind.PipCache -> R.string.diligent_storage_pip_cache_body
        CleanupKind.AllCaches -> R.string.diligent_storage_all_caches_body
        CleanupKind.OldSnapshots -> R.string.diligent_storage_old_snapshots_body
        CleanupKind.OldRunLogs -> R.string.diligent_storage_old_logs_body
    }

@StringRes
private fun usageTitle(row: UsageRow): Int = when (row.key) {
    "projects" -> R.string.diligent_storage_used_projects
    "tools" -> R.string.diligent_storage_used_tools
    "vscode_server" -> R.string.diligent_storage_used_server
    "extensions" -> R.string.diligent_storage_used_extensions
    "user_data" -> R.string.diligent_storage_used_user_data
    "saf_mirrors" -> R.string.diligent_storage_used_saf_mirrors
    "cache" -> R.string.diligent_storage_used_cache
    else -> R.string.diligent_storage_used_logs
}

private val previewReport = StorageReport(
    usage = listOf(UsageRow("projects", 412_000_000), UsageRow("tools", 380_000_000), UsageRow("cache", 96_000_000)),
    total = 1_420_000_000,
    free = 9_800_000_000,
    items = listOf(
        CleanupItem(CleanupKind.NodeModules, 310_000_000, "/p/my-site/node_modules", "my-site"),
        CleanupItem(CleanupKind.NpmCache, 84_000_000, "/c/npm-cache"),
        CleanupItem(CleanupKind.OldSnapshots, 12_000_000, "/p/my-site", "my-site", 40),
        CleanupItem(CleanupKind.OldRunLogs, 2_000_000, "/h/.diligent/logs", count = 6),
    ),
)

@Preview(name = "Storage, light", widthDp = 360, heightDp = 760)
@Composable
private fun StorageLightPreview() = DiligentTheme(darkTheme = false) {
    StorageContent(previewReport, busy = false, onDelete = {})
}

@Preview(name = "Storage, dark", widthDp = 360, heightDp = 760)
@Composable
private fun StorageDarkPreview() = DiligentTheme(darkTheme = true) {
    StorageContent(previewReport, busy = false, onDelete = {})
}
