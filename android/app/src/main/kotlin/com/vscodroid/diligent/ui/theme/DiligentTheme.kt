package com.vscodroid.diligent.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Diligent's design tokens: every colour, type size, spacing step, size and
 * shape a Mobile view screen uses comes from this file, and screens name
 * tokens rather than values. The interface is expected to be redesigned many
 * times, and this is the one place a redesign starts.
 *
 * Read a token through [DiligentTheme]: `DiligentTheme.colors.primary`,
 * `DiligentTheme.spacing.m`, `DiligentTheme.sizes.touchTarget`.
 */

// -- Colour --

/**
 * The raw palette. Private on purpose: screens use the roles in
 * [DiligentColors], so changing a hue here changes it everywhere it means the
 * same thing and nowhere else.
 */
private object Palette {
    val teal10 = Color(0xFF00201C)
    val teal20 = Color(0xFF003731)
    val teal30 = Color(0xFF005048)
    val teal40 = Color(0xFF006A60)
    val teal80 = Color(0xFF53DBC9)
    val teal90 = Color(0xFF74F8E5)
    /** A calmer teal for large light-mode fills; teal90 is neon over a whole card. */
    val teal92 = Color(0xFFA0F2E4)

    val slate10 = Color(0xFF0E1415)
    val slate17 = Color(0xFF1A2122)
    val slate22 = Color(0xFF242B2C)
    val slate30 = Color(0xFF2E3536)
    val slate50 = Color(0xFF6F7978)
    val slate60 = Color(0xFF899392)
    val slate80 = Color(0xFFBEC9C7)
    val slate90 = Color(0xFFDAE5E3)
    val slate94 = Color(0xFFE8EFEE)
    val slate97 = Color(0xFFF2F7F6)
    val slate99 = Color(0xFFF9FCFB)
    val slateText = Color(0xFF161D1C)
    val slateTextVariant = Color(0xFF3F4948)

    val amber40 = Color(0xFF7A5900)
    val amber80 = Color(0xFFF5BF48)
    val green40 = Color(0xFF1B6C2E)
    val green80 = Color(0xFF8BD893)
    val red40 = Color(0xFFBA1A1A)
    val red80 = Color(0xFFFFB4AB)
    val red90 = Color(0xFFFFDAD6)
    val red20 = Color(0xFF690005)
}

/**
 * The colour roles Diligent's screens use: Material 3's scheme, plus the run
 * states, which Material has no role for.
 */
@Immutable
data class DiligentColors(
    val scheme: ColorScheme,
    /** A task that is running. */
    val running: Color,
    /** A task that is starting, or a warning that is not an error. */
    val warning: Color,
    /** A task that stopped with an error. Same as the scheme's error. */
    val failed: Color,
    val isDark: Boolean,
    /**
     * A QR code's modules and its background. Dark on light in both themes,
     * because not every camera reads an inverted code.
     */
    val qrInk: Color = Palette.slateText,
    val qrPaper: Color = Color.White,
) {
    val primary get() = scheme.primary
    val onPrimary get() = scheme.onPrimary
    val primaryContainer get() = scheme.primaryContainer
    val onPrimaryContainer get() = scheme.onPrimaryContainer
    val background get() = scheme.background
    val onBackground get() = scheme.onBackground
    val surface get() = scheme.surface
    val onSurface get() = scheme.onSurface
    val surfaceVariant get() = scheme.surfaceVariant
    val onSurfaceVariant get() = scheme.onSurfaceVariant
    val surfaceContainerLow get() = scheme.surfaceContainerLow
    val surfaceContainer get() = scheme.surfaceContainer
    val surfaceContainerHigh get() = scheme.surfaceContainerHigh
    val outline get() = scheme.outline
    val outlineVariant get() = scheme.outlineVariant

    /** Content of something that cannot be used now: Material's 38% of onSurface. */
    val disabled get() = scheme.onSurface.copy(alpha = 0.38f)
    val error get() = scheme.error
    val onError get() = scheme.onError
}

internal val LightColors = DiligentColors(
    scheme = lightColorScheme(
        primary = Palette.teal40,
        onPrimary = Color.White,
        primaryContainer = Palette.teal92,
        onPrimaryContainer = Palette.teal10,
        inversePrimary = Palette.teal80,
        tertiary = Palette.amber40,
        onTertiary = Color.White,
        tertiaryContainer = Palette.amber80,
        onTertiaryContainer = Palette.slateText,
        inverseSurface = Palette.slate17,
        inverseOnSurface = Palette.slate94,
        surfaceDim = Palette.slate90,
        surfaceBright = Palette.slate99,
        scrim = Color.Black,
        secondary = Palette.teal30,
        onSecondary = Color.White,
        secondaryContainer = Palette.slate90,
        onSecondaryContainer = Palette.slateText,
        background = Palette.slate99,
        onBackground = Palette.slateText,
        surface = Palette.slate99,
        onSurface = Palette.slateText,
        surfaceVariant = Palette.slate90,
        onSurfaceVariant = Palette.slateTextVariant,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Palette.slate97,
        surfaceContainer = Palette.slate94,
        surfaceContainerHigh = Palette.slate90,
        surfaceContainerHighest = Palette.slate80,
        outline = Palette.slate50,
        outlineVariant = Palette.slate80,
        error = Palette.red40,
        onError = Color.White,
        errorContainer = Palette.red90,
        onErrorContainer = Palette.red20,
    ),
    running = Palette.green40,
    warning = Palette.amber40,
    failed = Palette.red40,
    isDark = false,
)

internal val DarkColors = DiligentColors(
    scheme = darkColorScheme(
        primary = Palette.teal80,
        onPrimary = Palette.teal20,
        primaryContainer = Palette.teal30,
        onPrimaryContainer = Palette.teal90,
        inversePrimary = Palette.teal40,
        tertiary = Palette.amber80,
        onTertiary = Palette.slate10,
        tertiaryContainer = Palette.amber40,
        onTertiaryContainer = Palette.slate99,
        inverseSurface = Palette.slate90,
        inverseOnSurface = Palette.slate17,
        surfaceDim = Palette.slate10,
        surfaceBright = Palette.slate22,
        scrim = Color.Black,
        secondary = Palette.teal80,
        onSecondary = Palette.teal20,
        secondaryContainer = Palette.slate30,
        onSecondaryContainer = Palette.slate90,
        background = Palette.slate10,
        onBackground = Palette.slate90,
        surface = Palette.slate10,
        onSurface = Palette.slate90,
        surfaceVariant = Palette.slate30,
        onSurfaceVariant = Palette.slate80,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Palette.slate10,
        surfaceContainer = Palette.slate17,
        surfaceContainerHigh = Palette.slate22,
        surfaceContainerHighest = Palette.slate30,
        outline = Palette.slate60,
        outlineVariant = Palette.slate30,
        error = Palette.red80,
        onError = Palette.red20,
        errorContainer = Palette.red40,
        onErrorContainer = Palette.red90,
    ),
    running = Palette.green80,
    warning = Palette.amber80,
    failed = Palette.red80,
    isDark = true,
)

// -- Type --

/**
 * Material 3's type scale, in the platform font. Code (file names, commands,
 * log lines) uses [DiligentTypography.code].
 */
@Immutable
data class DiligentTypography(
    val material: Typography,
    val code: TextStyle,
    val codeSmall: TextStyle,
)

internal val Type = DiligentTypography(
    material = Typography(),
    code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp),
    codeSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
)

// -- Spacing, sizes and shapes --

/** An 8 dp spacing scale, with a half step for tight insides. */
@Immutable
data class DiligentSpacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 16.dp,
    val l: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
)

/** Fixed sizes. Anything a thumb taps is at least [touchTarget]. */
@Immutable
data class DiligentSizes(
    val touchTarget: Dp = 48.dp,
    val icon: Dp = 24.dp,
    val iconSmall: Dp = 18.dp,
    val iconLarge: Dp = 32.dp,
    val topBar: Dp = 56.dp,
    val tabBar: Dp = 64.dp,
    val actionBar: Dp = 56.dp,
    val statusDot: Dp = 8.dp,
    val divider: Dp = 1.dp,
    /** The narrowest window that shows editor and preview side by side. */
    val splitMinWidth: Dp = 600.dp,
    /** How wide a dialog-like sheet grows before it stops. */
    val sheetMaxWidth: Dp = 560.dp,
    /** The widest a QR code is drawn: big enough for a laptop webcam to read. */
    val qrCode: Dp = 280.dp,
)

/** Corner shapes, from a chip up to a large card. */
@Immutable
data class DiligentShapes(
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(12.dp),
    val large: RoundedCornerShape = RoundedCornerShape(20.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(50),
)

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalTypography = staticCompositionLocalOf { Type }
private val LocalSpacing = staticCompositionLocalOf { DiligentSpacing() }
private val LocalSizes = staticCompositionLocalOf { DiligentSizes() }
private val LocalShapes = staticCompositionLocalOf { DiligentShapes() }

/**
 * Wraps Diligent's screens. Light or dark follows the system unless [darkTheme]
 * says otherwise, which previews use.
 */
@Composable
fun DiligentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val shapes = DiligentShapes()
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalTypography provides Type,
        LocalSpacing provides DiligentSpacing(),
        LocalSizes provides DiligentSizes(),
        LocalShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = colors.scheme,
            typography = Type.material,
            shapes = Shapes(
                small = shapes.small,
                medium = shapes.medium,
                large = shapes.large,
            ),
            content = content,
        )
    }
}

/** The tokens of the [DiligentTheme] in force. */
object DiligentTheme {
    val colors: DiligentColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: DiligentTypography
        @Composable @ReadOnlyComposable get() = LocalTypography.current
    val spacing: DiligentSpacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val sizes: DiligentSizes
        @Composable @ReadOnlyComposable get() = LocalSizes.current
    val shapes: DiligentShapes
        @Composable @ReadOnlyComposable get() = LocalShapes.current
}

// -- Previews --

@Composable
private fun TokenSheet() {
    val c = DiligentTheme.colors
    val swatches = listOf(
        "primary" to c.primary,
        "primaryContainer" to c.primaryContainer,
        "surfaceContainer" to c.surfaceContainer,
        "running" to c.running,
        "warning" to c.warning,
        "failed" to c.failed,
    )
    Surface(color = c.background) {
        Column(
            modifier = Modifier.padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            Text("Diligent", style = DiligentTheme.type.material.headlineMedium, color = c.onBackground)
            Text("Body text on the background", style = DiligentTheme.type.material.bodyLarge)
            Text("npm run dev", style = DiligentTheme.type.code, color = c.onSurfaceVariant)
            swatches.forEach { (name, color) ->
                Row(horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
                    Box(
                        Modifier
                            .size(DiligentTheme.sizes.icon)
                            .background(color, DiligentTheme.shapes.small),
                    )
                    Text(name, style = DiligentTheme.type.material.labelLarge.copy(fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

@Preview(name = "Tokens, light")
@Composable
private fun TokensLightPreview() = DiligentTheme(darkTheme = false) { TokenSheet() }

@Preview(name = "Tokens, dark")
@Composable
private fun TokensDarkPreview() = DiligentTheme(darkTheme = true) { TokenSheet() }
