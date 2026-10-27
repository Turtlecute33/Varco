package app.varco.ui.prompts

import kotlin.test.Test
import kotlin.test.assertEquals

class CleanTest {
    @Test
    fun removesControlAndBidiCharacters() {
        assertEquals("PIN: abc\n", clean("\u001b[1mPIN\u001b[0m:\u0007 \u202eabc\u2066\u2069\u0085\r\n"))
    }

    @Test
    fun keepsAtMost500Characters() {
        assertEquals(500, clean("x".repeat(2000)).length)
    }
}
