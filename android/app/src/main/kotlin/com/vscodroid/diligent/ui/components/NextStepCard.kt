package com.vscodroid.diligent.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.guide.NextStep
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * "Next step": what a guided start leaves above the bars once the project
 * is open, pointing at the one button to tap. Goes when dismissed or when
 * the project starts running.
 */
@Composable
fun NextStepCard(step: NextStep, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
        color = DiligentTheme.colors.primaryContainer,
        contentColor = DiligentTheme.colors.onPrimaryContainer,
        shape = DiligentTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(start = DiligentTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
        ) {
            Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(DiligentTheme.sizes.icon))
            Column(Modifier.weight(1f).padding(vertical = DiligentTheme.spacing.s)) {
                Text(stringResource(R.string.diligent_next_step_title), style = DiligentTheme.type.material.labelLarge)
                Text(stringResource(step.text), style = DiligentTheme.type.material.bodyMedium)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(DiligentTheme.sizes.touchTarget)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.diligent_next_step_dismiss))
            }
        }
    }
}

@get:StringRes
private val NextStep.text: Int
    get() = when (this) {
        NextStep.RunPage -> R.string.diligent_next_step_run_page
        NextStep.RunApp -> R.string.diligent_next_step_run_app
        NextStep.RunScript -> R.string.diligent_next_step_run_script
    }

@Preview(name = "Next step, light", widthDp = 360)
@Composable
private fun NextStepLightPreview() = DiligentTheme(darkTheme = false) { NextStepCard(NextStep.RunPage, onDismiss = {}) }

@Preview(name = "Next step, dark", widthDp = 360)
@Composable
private fun NextStepDarkPreview() = DiligentTheme(darkTheme = true) { NextStepCard(NextStep.RunApp, onDismiss = {}) }
