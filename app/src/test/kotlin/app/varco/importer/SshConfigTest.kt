package app.varco.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SshConfigTest {
    @Test
    fun readsServersAndSkipsPatterns() {
        val entries = SshConfig.parse(
            """
            # comment
            Host *
                User nobody
                PKCS11Provider /usr/lib/opensc-pkcs11.so

            Host vps 203.0.113.10
                HostName 203.0.113.10
                Port 2222
                User debian
                IdentityFile ~/.ssh/id_ed25519_work
                IdentitiesOnly yes

            Host relay
              hostname=198.51.100.20
              user = root

            Match host *.example.com
                User matched

            Host "quoted name" *.lan
                HostName %h.lan
                Port 99999

            Host vps
                User second
            """.trimIndent(),
        )
        assertEquals(listOf("vps", "relay", "quoted name"), entries.map { it.alias })
        val vps = entries[0]
        assertEquals("203.0.113.10", vps.host)
        assertEquals(2222, vps.port)
        assertEquals("debian", vps.user)
        assertEquals(listOf("id_ed25519_work"), vps.identityFiles)
        val relay = entries[1]
        assertEquals("198.51.100.20", relay.host)
        assertEquals("root", relay.user)
        assertEquals(22, relay.port)
        // %h is the alias; a port out of range falls back to 22.
        assertEquals("quoted name.lan", entries[2].host)
        assertEquals(22, entries[2].port)
        assertFalse(entries.any { it.needsJump })
    }

    @Test
    fun marksEntriesThatNeedAJumpHost() {
        val entries = SshConfig.parse(
            """
            Host inner
                HostName 10.0.0.5
                ProxyJump bastion

            Host piped
                ProxyCommand ssh -W %h:%p bastion

            Host direct
                HostName 192.0.2.7
                ProxyJump none

            Host bastion
                HostName 192.0.2.1
            """.trimIndent(),
        )
        assertEquals(
            mapOf("inner" to true, "piped" to true, "direct" to false, "bastion" to false),
            entries.associate { it.alias to it.needsJump },
        )
    }
}
