package app.varco.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.varco.term.Scheme

/**
 * The app theme follows the terminal colour scheme: the screens around the terminal use its background,
 * text and accent colours, so the whole app looks like one surface. Material 3 Expressive motion.
 */
@Composable
fun VarcoTheme(scheme: Scheme, content: @Composable () -> Unit) {
    val colors = remember(scheme) { colorsFor(scheme) }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = VarcoShapes,
        typography = VarcoTypography,
        content = content,
    )
}

/** Material roles from a terminal scheme: surfaces are steps from background to foreground; blue is the accent. */
fun colorsFor(scheme: Scheme): ColorScheme {
    val bg = Color(scheme.background)
    val fg = Color(scheme.foreground)
    val accent = Color(scheme.ansi[4])
    val accent2 = Color(scheme.ansi[5])
    val error = Color(scheme.ansi[1])
    fun step(f: Float) = lerp(bg, fg, f)
    fun on(c: Color) = if (luminance(c) > 0.45f) Color(0xFF11111B) else Color(0xFFF8F8F8)
    val base = if (scheme.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = on(accent),
        primaryContainer = lerp(bg, accent, 0.28f),
        onPrimaryContainer = fg,
        secondary = accent2,
        onSecondary = on(accent2),
        secondaryContainer = lerp(bg, accent2, 0.22f),
        onSecondaryContainer = fg,
        tertiary = Color(scheme.ansi[6]),
        onTertiary = on(Color(scheme.ansi[6])),
        tertiaryContainer = lerp(bg, Color(scheme.ansi[6]), 0.22f),
        onTertiaryContainer = fg,
        error = error,
        onError = on(error),
        errorContainer = lerp(bg, error, 0.25f),
        onErrorContainer = fg,
        background = bg,
        onBackground = fg,
        surface = bg,
        onSurface = fg,
        surfaceVariant = step(0.10f),
        onSurfaceVariant = step(0.70f),
        surfaceTint = accent,
        inverseSurface = fg,
        inverseOnSurface = bg,
        inversePrimary = lerp(accent, bg, 0.3f),
        outline = step(0.38f),
        outlineVariant = step(0.18f),
        scrim = Color.Black,
        surfaceBright = step(0.12f),
        surfaceDim = bg,
        surfaceContainerLowest = lerp(bg, Color.Black, 0.2f),
        surfaceContainerLow = step(0.03f),
        surfaceContainer = step(0.05f),
        surfaceContainerHigh = step(0.08f),
        surfaceContainerHighest = step(0.11f),
    )
}

private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

private val VarcoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    largeIncreased = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

private val VarcoTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium),
    )
}
