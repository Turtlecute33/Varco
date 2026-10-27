package app.varco.ssh

import kotlin.test.Test
import kotlin.test.assertEquals

class PrintableTest {
    @Test
    fun removesControlSequencesAndBytes() {
        assertEquals("ared]0;xb\n", printable("a\u001b[31mred\u001b[0m\u001b]0;x\u0007b\r\n"))
    }

    @Test
    fun keepsPlainText() {
        assertEquals("plain text\n", printable("plain text\n"))
    }
}
