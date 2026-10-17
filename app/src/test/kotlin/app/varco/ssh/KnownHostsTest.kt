package app.varco.ssh

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnownHostsTest {
    private val key = Base64.getEncoder().encodeToString(byteArrayOf(0, 0, 0, 11) + "ssh-ed25519".toByteArray() + ByteArray(36))

    @Test
    fun matchesPlainPortAndWildcardNames() {
        assertTrue(KnownHosts.matches("example.com,10.0.0.1", "example.com", 22))
        assertTrue(KnownHosts.matches("[example.com]:2222", "example.com", 2222))
        assertFalse(KnownHosts.matches("example.com", "example.com", 2222))
        assertTrue(KnownHosts.matches("*.lan", "nas.lan", 22))
        assertFalse(KnownHosts.matches("*.lan,!secret.lan", "secret.lan", 22))
        assertTrue(KnownHosts.matches("host?", "host1", 22))
    }

    @Test
    fun matchesHashedNames() {
        val salt = ByteArray(20) { it.toByte() }
        fun hashed(name: String): String {
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(salt, "HmacSHA1")) }
            val b64 = Base64.getEncoder()
            return "|1|${b64.encodeToString(salt)}|${b64.encodeToString(mac.doFinal(name.toByteArray()))}"
        }
        assertTrue(KnownHosts.matches(hashed("relay.example"), "relay.example", 22))
        assertTrue(KnownHosts.matches(hashed("[1.2.3.4]:2200"), "1.2.3.4", 2200))
        assertFalse(KnownHosts.matches(hashed("other"), "relay.example", 22))
    }

    @Test
    fun parsesMarkersAndSkipsBadLines() {
        val lines = KnownHosts.parse(
            """
            host1 ssh-ed25519 $key
            @revoked host2 ssh-ed25519 $key
            @cert-authority *.example.com ssh-ed25519 $key
            broken line
            host3 ssh-ed25519 not*base64
            host4 ssh-rsa $key
            hello from the server
            """.trimIndent(),
        )
        assertEquals(listOf("host1", "host2"), lines.map { it.hosts })
        assertTrue(lines[1].revoked)
    }
}
