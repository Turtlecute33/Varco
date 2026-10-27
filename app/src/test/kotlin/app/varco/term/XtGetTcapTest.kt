package app.varco.term

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** XTGETTCAP (DCS + q): a reply may hold only hex names, never other server bytes. */
class XtGetTcapTest {
    private class Output : TerminalOutput() {
        val written = ByteArrayOutputStream()
        override fun write(data: ByteArray, offset: Int, count: Int) = written.write(data, offset, count)
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun reply(request: String): String {
        val out = Output()
        val emulator = TerminalEmulator(out, 80, 24, 10, 20, 100, null)
        val bytes = "\u001bP+q$request\u001b\\".toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
        return out.written.toString(Charsets.UTF_8)
    }

    @Test
    fun nonHexNameGetsNoReply() {
        assertEquals("", reply("\u0003X"))
        assertEquals("", reply("6b\u0003X75"))
    }

    @Test
    fun hexNameGetsReply() {
        assertTrue(reply("544e").startsWith("\u001bP1+r544e="))
    }
}
