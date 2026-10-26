package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** What a status means, which decides its colour. */
enum class StatusTone { Neutral, Running, Warning, Failed }

@Composable
fun StatusTone.color(): Color = when (this) {
    StatusTone.Neutral -> DiligentTheme.colors.onSurfaceVariant
    StatusTone.Running -> DiligentTheme.colors.running
    StatusTone.Warning -> DiligentTheme.colors.warning
    StatusTone.Failed -> DiligentTheme.colors.failed
}

/** A small coloured dot, for the state of a task or a device. */
@Composable
fun StatusDot(tone: StatusTone, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(DiligentTheme.sizes.statusDot)
            .background(tone.color(), CircleShape),
    )
}

/** A dot followed by a short label, such as "Running". */
@Composable
fun StatusLabel(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.xs),
    ) {
        StatusDot(tone)
        Text(text, style = DiligentTheme.type.material.labelMedium, color = tone.color())
    }
}

@Composable
private fun StatusSample() {
    Surface(color = DiligentTheme.colors.surface) {
        Column(
            modifier = Modifier.padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            StatusTone.entries.forEach { StatusLabel(it.name, it) }
        }
    }
}

@Preview(name = "Status, light")
@Composable
private fun StatusLightPreview() = DiligentTheme(darkTheme = false) { StatusSample() }

@Preview(name = "Status, dark")
@Composable
private fun StatusDarkPreview() = DiligentTheme(darkTheme = true) { StatusSample() }
