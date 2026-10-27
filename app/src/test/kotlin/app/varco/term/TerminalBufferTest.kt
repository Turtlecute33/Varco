package app.varco.term

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Changes in the vendored TerminalBuffer (terminal/NOTICE.md). */
class TerminalBufferTest {
    private class Output : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun emulator(columns: Int, rows: Int, scrollback: Int = 100) =
        TerminalEmulator(Output(), columns, rows, 10, 20, scrollback, null)

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        append(bytes, bytes.size)
    }

    @Test
    fun eraseToEndOfLineClearsLineWrap() {
        val e = emulator(columns = 10, rows = 4)
        e.feed("abcdefghijkl")
        assertTrue(e.screen.getLineWrap(0))
        // Cursor to row 1, column 3, then erase to the end of the line.
        e.feed("\u001b[1;3H\u001b[K")
        assertFalse(e.screen.getLineWrap(0))
    }

    @Test
    fun columnChangeKeepsTheTranscript() {
        // 100 rows in all: 5 on the screen, 95 in the transcript.
        val e = emulator(columns = 20, rows = 5, scrollback = 100)
        val lines = (1..260).map { "line $it" }
        e.feed(lines.take(60).joinToString("\r\n"))
        e.resize(12, 5, 10, 20)
        assertEquals(lines.take(60).joinToString("\n"), e.screen.transcriptTextWithoutJoinedLines)

        e.feed(lines.subList(60, 70).joinToString("") { "\r\n$it" })
        e.resize(20, 5, 10, 20)
        // Rows that resize did not make are made when the screen scrolls, also after the ring is full.
        e.feed(lines.drop(70).joinToString("") { "\r\n$it" })
        assertEquals(lines.takeLast(100).joinToString("\n"), e.screen.transcriptTextWithoutJoinedLines)
    }
}
