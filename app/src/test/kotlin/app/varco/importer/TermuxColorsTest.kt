package app.varco.importer

import app.varco.term.Schemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TermuxColorsTest {
    @Test
    fun readsAllColourForms() {
        val s = TermuxColors.parse(
            """
            # Catppuccin
            background=#1e1e2e
            foreground : #CDD6F4
            cursor=#fff
            color1=rgb:ff/00/80
            color2=rgb:ffff/0000/8080
            color17=#123456
            """.trimIndent(),
            "custom:x",
            "Test",
        )!!
        assertEquals(0xff1e1e2e.toInt(), s.background)
        assertEquals(0xffcdd6f4.toInt(), s.foreground)
        assertEquals(0xffffffff.toInt(), s.cursor)
        assertEquals(0xffff0080.toInt(), s.ansi[1])
        assertEquals(0xffff0080.toInt(), s.ansi[2])
        // Missing colours come from the Termux scheme.
        assertEquals(Schemes.byId("termux").ansi[3], s.ansi[3])
    }

    @Test
    fun rejectsOtherFiles() {
        assertNull(TermuxColors.parse("Host foo\n  User bar", "custom:x", "Test"))
        assertNull(TermuxColors.parseColour("red"))
        assertNull(TermuxColors.parseColour("#12345"))
    }
}
