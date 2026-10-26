package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.ui.theme.DiligentTheme

/** Mobile view's Files tab. A placeholder until the screen itself is built. */
@Composable
fun FilesScreen(shell: MobileShell, actions: MobileActions, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.diligent_screen_files),
                style = DiligentTheme.type.material.titleMedium,
                color = DiligentTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Files, light", widthDp = 360, heightDp = 640)
@Composable
private fun FilesLightPreview() = DiligentTheme(darkTheme = false) {
    FilesScreen(MobileShell(null), PreviewActions)
}

@Preview(name = "Files, dark", widthDp = 360, heightDp = 640)
@Composable
private fun FilesDarkPreview() = DiligentTheme(darkTheme = true) {
    FilesScreen(MobileShell(null), PreviewActions)
}
