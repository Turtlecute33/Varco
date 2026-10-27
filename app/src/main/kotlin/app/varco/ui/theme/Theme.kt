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
import androidx.compose.ui.graphics.luminance
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

/**
 * Material roles from a terminal scheme: surfaces are steps from background to foreground; blue is the accent.
 * Text roles (onSurface, onSurfaceVariant, primary, error) get a contrast floor; containers keep the raw colours.
 */
fun colorsFor(scheme: Scheme): ColorScheme {
    val bg = Color(scheme.background)
    val fg = Color(scheme.foreground)
    val rawAccent = Color(scheme.ansi[4])
    val accent2 = Color(scheme.ansi[5])
    val rawError = Color(scheme.ansi[1])
    fun step(f: Float) = lerp(bg, fg, f)
    fun on(c: Color) = if (luminance(c) > 0.45f) Color(0xFF11111B) else Color(0xFFF8F8F8)
    // WCAG AA (4.5:1) on the background and in dialogs (surfaceContainerHigh): first toward the text colour,
    // then toward white or black when the text colour of the scheme is not sufficient.
    val high = step(0.08f)
    val pole = if (scheme.isDark) Color.White else Color.Black
    fun text(c: Color) = legible(legible(c, fg, bg, high), pole, bg, high)
    val onBg = text(fg)
    val accent = text(rawAccent)
    val error = text(rawError)
    val base = if (scheme.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = on(accent),
        primaryContainer = lerp(bg, rawAccent, 0.28f),
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
        errorContainer = lerp(bg, rawError, 0.25f),
        onErrorContainer = fg,
        background = bg,
        onBackground = onBg,
        surface = bg,
        onSurface = onBg,
        surfaceVariant = step(0.10f),
        onSurfaceVariant = text(step(0.70f)),
        surfaceTint = rawAccent,
        inverseSurface = fg,
        inverseOnSurface = bg,
        inversePrimary = lerp(rawAccent, bg, 0.3f),
        outline = step(0.38f),
        outlineVariant = step(0.18f),
        scrim = Color.Black,
        surfaceBright = step(0.12f),
        surfaceDim = bg,
        surfaceContainerLowest = lerp(bg, Color.Black, 0.2f),
        surfaceContainerLow = step(0.03f),
        surfaceContainer = step(0.05f),
        surfaceContainerHigh = high,
        surfaceContainerHighest = step(0.11f),
    )
}

private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

/** [c] blended toward [toward] in 5 % steps until it has 4.5:1 contrast on [a] and on [b]; else [toward]. */
private fun legible(c: Color, toward: Color, a: Color, b: Color): Color {
    for (i in 0..20) {
        val x = lerp(c, toward, i / 20f)
        if (contrast(x, a) >= 4.5f && contrast(x, b) >= 4.5f) return x
    }
    return toward
}

/** WCAG contrast ratio of two colours. */
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}

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
