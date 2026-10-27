package com.vscodroid.diligent.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vscodroid.R
import com.vscodroid.diligent.MemoryWarning

/**
 * Low-memory mode's question before a run when the phone has under 500 MB
 * free: Android may stop the run, or the editor, to make room. Run anyway
 * starts it; Cancel and a tap outside leave it.
 */
@Composable
fun LowMemoryRunDialog(warning: MemoryWarning, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diligent_low_memory_run_title)) },
        text = { Text(stringResource(R.string.diligent_low_memory_run_body, warning.availableMb)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                warning.proceed()
            }) { Text(stringResource(R.string.diligent_low_memory_run_anyway)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.diligent_low_memory_run_cancel)) } },
    )
}
