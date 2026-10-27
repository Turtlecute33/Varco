package app.varco.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import app.varco.term.Scheme
import app.varco.term.Schemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UI text has at least 4.5:1 contrast (WCAG AA) on the background and in dialogs, for each scheme. */
class ThemeContrastTest {
    private fun ratio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return maxOf(la, lb) / minOf(la, lb)
    }

    private fun assertLegible(scheme: Scheme) {
        val c: ColorScheme = colorsFor(scheme)
        val text = listOf("onSurface" to c.onSurface, "onSurfaceVariant" to c.onSurfaceVariant, "primary" to c.primary, "error" to c.error)
        for ((role, colour) in text) {
            for ((surface, on) in listOf("background" to c.background, "surfaceContainerHigh" to c.surfaceContainerHigh)) {
                val r = ratio(colour, on)
                assertTrue(r >= 4.5f, "${scheme.name}: $role on $surface is $r:1")
            }
        }
    }

    @Test
    fun builtInSchemesHaveLegibleText() = Schemes.all.forEach(::assertLegible)

    @Test
    fun lowContrastImportedSchemeGetsLegibleText() {
        // Made-up scheme: grey text on a slightly darker grey, dark red and dark blue.
        val ansi = IntArray(16) { 0xff444444.toInt() }.also { it[1] = 0xff550000.toInt(); it[4] = 0xff000055.toInt() }
        assertLegible(Scheme("custom:test", "Test", 0xff777777.toInt(), 0xff666666.toInt(), 0xffffffff.toInt(), ansi))
    }

    @Test
    fun defaultSchemeKeepsItsColours() {
        val s = Schemes.default
        val bg = Color(s.background)
        val fg = Color(s.foreground)
        val c = colorsFor(s)
        assertEquals(fg, c.onSurface)
        assertEquals(lerp(bg, fg, 0.70f), c.onSurfaceVariant)
        assertEquals(Color(s.ansi[4]), c.primary)
        assertEquals(Color(s.ansi[1]), c.error)
    }

    @Test
    fun containersKeepTheRawColours() {
        val s = Schemes.byId("gruvbox-dark")
        val bg = Color(s.background)
        val c = colorsFor(s)
        assertTrue(c.error != Color(s.ansi[1]), "Gruvbox Dark error must get lighter")
        assertEquals(lerp(bg, Color(s.ansi[1]), 0.25f), c.errorContainer)
        assertEquals(lerp(bg, Color(s.ansi[4]), 0.28f), c.primaryContainer)
    }
}
