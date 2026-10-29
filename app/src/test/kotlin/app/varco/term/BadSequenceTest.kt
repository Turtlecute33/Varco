package app.varco.term

import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bad escape sequences from the server: append() must not throw, and the text after them must show. */
class BadSequenceTest {
    private class Output : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun assertNoCrash(sequence: String): TerminalEmulator {
        val emulator = TerminalEmulator(Output(), 80, 24, 10, 20, 100, null)
        val bytes = "${sequence}ok".toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
        assertTrue("ok" in emulator.screen.transcriptText, sequence)
        return emulator
    }

    @Test
    fun resetColorWithBadIndex() {
        // OSC 104: a token that is not a number, an index out of range, a trailing separator.
        listOf("x", "300", "-1", "1;").forEach { assertNoCrash("\u001b]104;$it\u001b\\") }
        // A good index after a bad token is still reset.
        val emulator = assertNoCrash("\u001b]4;1;#123456\u001b\\\u001b]104;x;1\u001b\\")
        assertEquals(TerminalColors.COLOR_SCHEME.mDefaultColors[1], emulator.mColors.mCurrentColors[1])
    }

    @Test
    fun rectangleAttributesInsideMargins() {
        // DECCARA with origin mode and left and right margins (DECSLRM), or top and bottom margins (DECSTBM).
        val emulator = assertNoCrash("\u001b[?69h\u001b[40;80s\u001b[?6h\u001b[2*x\u001b[1;1;24;80;1\$r")
        assertNoCrash("\u001b[?69h\u001b[40;80s\u001b[?6h\u001b[1;1;24;80;1\$r")
        assertNoCrash("\u001b[23;24r\u001b[?6h\u001b[1;1;24;80;1\$r")
        // The last row of the rectangle is bold too.
        val effect = TextStyle.decodeEffect(emulator.screen.getStyleAt(22, 79))
        assertTrue(effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0)
    }
}
