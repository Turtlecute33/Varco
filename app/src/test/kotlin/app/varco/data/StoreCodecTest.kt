package app.varco.data

import app.varco.term.Schemes
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreCodecTest {
    @Test
    fun roundTrip() {
        val state = AppState(
            servers = listOf(Server("s1", "Relay", "198.51.100.20", 22, "root", keyId = "k1", password = null, lastUsed = 5)),
            keys = listOf(
                SshKey("k1", "YubiKey 9a", SshKey.Kind.YUBIKEY_PIV, "ssh-rsa", "AAAA", pivSlot = 0x9a),
                SshKey("k2", "Phone", SshKey.Kind.KEYSTORE, "ssh-ed25519", "BBBB", alias = "varco.key.x", authPerUse = true),
            ),
            snippets = listOf(Snippet("n1", "Update", "sudo apt update", run = false)),
            knownHosts = listOf(KnownHost("example.com", 2222, "ssh-ed25519", "CCCC")),
            importedKnownHosts = "host ssh-ed25519 DDDD",
            schemes = listOf(Schemes.all[2]),
            settings = Settings(schemeId = "nord", fontSize = 15, appLock = true, lockAfter = 0, serverClipboard = true),
        )
        val back = Store.decode(Store.encode(state))
        assertEquals(state.servers, back.servers)
        assertEquals(state.keys, back.keys)
        assertEquals(state.snippets, back.snippets)
        assertEquals(state.knownHosts, back.knownHosts)
        assertEquals(state.importedKnownHosts, back.importedKnownHosts)
        assertEquals(state.settings, back.settings)
        assertEquals(state.schemes[0].ansi.toList(), back.schemes[0].ansi.toList())
    }
}
