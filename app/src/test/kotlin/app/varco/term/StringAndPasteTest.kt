package app.varco.term

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** APC cancel, one write for a bracketed paste and the soft reset at the end of a connection. */
class StringAndPasteTest {
    private class Output : TerminalOutput() {
        val writes = mutableListOf<String>()
        override fun write(data: ByteArray, offset: Int, count: Int) {
            writes += String(data, offset, count, Charsets.UTF_8)
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private val out = Output()
    private val emulator = TerminalEmulator(out, 80, 24, 10, 20, 100, null)

    private fun feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
    }

    @Test
    fun canAndSubCancelApc() {
        feed("\u001b_junk\u0018one")
        feed("\u001b_junk\u001b\u001aTwo")
        val text = emulator.screen.transcriptText
        assertTrue("one" in text, text)
        assertTrue("Two" in text, text)
        assertFalse("junk" in text, text)
    }

    @Test
    fun bracketedPasteIsOneWrite() {
        feed("\u001b[?2004h")
        emulator.paste("ls\n")
        assertEquals(listOf("\u001b[200~ls\r\u001b[201~"), out.writes)
    }

    @Test
    fun softResetEndsStringsAndModes() {
        // Vim or tmux state, and a cut inside an OSC string.
        feed("\u001b[?1049h\u001b[?1000h\u001b[?2004h\u001b[?1h\u001b[5;10r\u001b]0;cut")
        // The same bytes that Shell feeds before the end notice.
        feed("\u001b\\\u001b[?1049l\u001b[!p[Connection lost]")
        assertFalse(emulator.isAlternateBufferActive)
        assertFalse(emulator.isMouseTrackingActive)
        assertFalse(emulator.isCursorKeysApplicationMode)
        emulator.paste("x")
        assertEquals(listOf("x"), out.writes)
        assertTrue("[Connection lost]" in emulator.screen.transcriptText)
    }
}
