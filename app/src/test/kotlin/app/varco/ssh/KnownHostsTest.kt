package app.varco.ssh

import app.varco.data.KnownHost
import app.varco.ssh.KnownHosts.Verdict
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

    // The host key trust rule (KnownHosts.verdict). Made-up key blobs: the type name, then filler bytes.
    private fun blob(type: String, fill: Int) = byteArrayOf(0, 0, 0, type.length.toByte()) + type.toByteArray() + ByteArray(32) { fill.toByte() }
    private val ed1 = blob("ssh-ed25519", 1)
    private val ed2 = blob("ssh-ed25519", 2)
    private val ec1 = blob("ecdsa-sha2-nistp256", 1)
    private fun own(type: String, key: ByteArray) = KnownHost("example.com", 22, type, Base64.getEncoder().encodeToString(key))
    private fun line(type: String, key: ByteArray, revoked: Boolean = false) = KnownHosts.Line("example.com", type, key, revoked)

    @Test
    fun verdictTrustsOnlyTheOwnKeyOfTheSameType() {
        assertEquals(Verdict.NEW, KnownHosts.verdict(emptyList(), emptyList(), "ssh-ed25519", ed1))
        assertEquals(Verdict.TRUSTED, KnownHosts.verdict(listOf(own("ssh-ed25519", ed1)), emptyList(), "ssh-ed25519", ed1))
        assertEquals(Verdict.CHANGED, KnownHosts.verdict(listOf(own("ssh-ed25519", ed1)), emptyList(), "ssh-ed25519", ed2))
    }

    @Test
    fun verdictCountsAKeyTypeSwitchAsChanged() {
        // The MITM case: the server offers only a type that Varco has no key for.
        assertEquals(Verdict.CHANGED, KnownHosts.verdict(listOf(own("ecdsa-sha2-nistp256", ec1)), emptyList(), "ssh-ed25519", ed1))
        assertEquals(Verdict.CHANGED, KnownHosts.verdict(emptyList(), listOf(line("ecdsa-sha2-nistp256", ec1)), "ssh-ed25519", ed1))
    }

    @Test
    fun verdictUsesImportedLinesOnlyWithoutAnOwnKeyOfThatType() {
        assertEquals(Verdict.TRUSTED, KnownHosts.verdict(emptyList(), listOf(line("ssh-ed25519", ed1)), "ssh-ed25519", ed1))
        // An own key of another type does not block the imported line of this type.
        assertEquals(
            Verdict.TRUSTED,
            KnownHosts.verdict(listOf(own("ecdsa-sha2-nistp256", ec1)), listOf(line("ssh-ed25519", ed1)), "ssh-ed25519", ed1),
        )
        // The own key wins over an imported line of the same type.
        assertEquals(
            Verdict.CHANGED,
            KnownHosts.verdict(listOf(own("ssh-ed25519", ed2)), listOf(line("ssh-ed25519", ed1)), "ssh-ed25519", ed1),
        )
        assertEquals(Verdict.CHANGED, KnownHosts.verdict(emptyList(), listOf(line("ssh-ed25519", ed2)), "ssh-ed25519", ed1))
    }

    @Test
    fun verdictBlocksARevokedKey() {
        assertEquals(Verdict.REVOKED, KnownHosts.verdict(emptyList(), listOf(line("ssh-ed25519", ed1, revoked = true)), "ssh-ed25519", ed1))
        // Also when the user trusted the key before.
        assertEquals(
            Verdict.REVOKED,
            KnownHosts.verdict(listOf(own("ssh-ed25519", ed1)), listOf(line("ssh-ed25519", ed1, revoked = true)), "ssh-ed25519", ed1),
        )
        // A revoked line of another key is not a known key.
        assertEquals(Verdict.NEW, KnownHosts.verdict(emptyList(), listOf(line("ssh-ed25519", ed2, revoked = true)), "ssh-ed25519", ed1))
    }
}
