package app.varco.ssh

import kotlin.test.Test
import kotlin.test.assertEquals

class SftpTest {
    @Test
    fun litEscapesGlobCharacters() {
        assertEquals("/a/b", Sftp.lit("/a/b"))
        assertEquals("/d/\\*", Sftp.lit("/d/*"))
        assertEquals("/d/a\\?c", Sftp.lit("/d/a?c"))
        assertEquals("/d/a\\\\b", Sftp.lit("/d/a\\b"))
    }
}
