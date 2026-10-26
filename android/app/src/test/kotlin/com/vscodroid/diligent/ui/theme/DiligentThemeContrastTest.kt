package com.vscodroid.diligent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every text colour Diligent's screens draw, on every background it is drawn
 * on, reaches WCAG AA for body text (4.5:1) in both themes.
 *
 * The tokens will be redesigned many times, and a contrast that slips is
 * invisible on the designer's own screen at full brightness: this is what
 * notices it for them.
 */
class DiligentThemeContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }

    private fun pairs(c: DiligentColors) = listOf(
        "onPrimary on primary" to (c.onPrimary to c.primary),
        "onPrimaryContainer on primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
        "onBackground on background" to (c.onBackground to c.background),
        "onSurface on surface" to (c.onSurface to c.surface),
        "onSurface on surfaceContainer" to (c.onSurface to c.surfaceContainer),
        "onSurface on surfaceContainerHigh" to (c.onSurface to c.surfaceContainerHigh),
        "onSurfaceVariant on surface" to (c.onSurfaceVariant to c.surface),
        "onSurfaceVariant on surfaceContainer" to (c.onSurfaceVariant to c.surfaceContainer),
        "onError on error" to (c.onError to c.error),
        "primary on surface" to (c.primary to c.surface),
        "running on surface" to (c.running to c.surface),
        "warning on surface" to (c.warning to c.surface),
        "failed on surface" to (c.failed to c.surface),
        "running on surfaceContainer" to (c.running to c.surfaceContainer),
        "failed on surfaceContainer" to (c.failed to c.surfaceContainer),
    )

    @Test
    fun `light theme text reaches AA`() = check("light", LightColors)

    @Test
    fun `dark theme text reaches AA`() = check("dark", DarkColors)

    private fun check(name: String, colors: DiligentColors) {
        val failing = pairs(colors).mapNotNull { (label, pair) ->
            val ratio = contrast(pair.first, pair.second)
            if (ratio < AA) "$label: %.2f".format(ratio) else null
        }
        assertTrue(failing.isEmpty(), "$name theme below $AA:1: $failing")
    }

    private companion object {
        const val AA = 4.5
    }
}
