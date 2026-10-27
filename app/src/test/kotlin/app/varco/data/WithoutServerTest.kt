package app.varco.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** A server delete also removes its trusted host keys, but not the keys that another server still uses. */
class WithoutServerTest {
    private val a = Server(id = "a", name = "a", host = "Box.example.org", user = "u")
    private val b = Server(id = "b", name = "b", host = "box.example.org", user = "admin")
    private val c = Server(id = "c", name = "c", host = "box.example.org", port = 2222, user = "u")
    private fun key(port: Int, type: String) = KnownHost("box.example.org", port, type, "AAAA$type")
    private val keys = listOf(key(22, "ssh-ed25519"), key(22, "ecdsa-sha2-nistp256"), key(2222, "ssh-ed25519"))

    @Test
    fun removesTheHostKeysOfAHostAndPortThatNoOtherServerUses() {
        val st = AppState(servers = listOf(a, c), knownHosts = keys, importedKnownHosts = "line")
        val out = st.withoutServer("a")
        assertEquals(listOf(c), out.servers)
        assertEquals(listOf(key(2222, "ssh-ed25519")), out.knownHosts)
        assertEquals("line", out.importedKnownHosts)
    }

    @Test
    fun keepsHostKeysThatAnotherServerUses() {
        val out = AppState(servers = listOf(a, b, c), knownHosts = keys).withoutServer("a")
        assertEquals(listOf(b, c), out.servers)
        assertEquals(keys, out.knownHosts)
    }

    @Test
    fun anUnknownIdChangesNothing() {
        val st = AppState(servers = listOf(a), knownHosts = keys)
        assertEquals(st, st.withoutServer("x"))
    }
}
