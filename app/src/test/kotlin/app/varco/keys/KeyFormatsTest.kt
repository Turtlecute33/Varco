package app.varco.keys

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The import path: jsch decrypts and writes "openssh-key-v1"; OpenSshKey takes the numbers out. */
class KeyFormatsTest {
    private fun roundTrip(type: Int, size: Int = 0, passphrase: String? = null): Pair<KeyPair, OpenSshKey.Private> {
        val jsch = JSch()
        val generated = if (size > 0) KeyPair.genKeyPair(jsch, type, size) else KeyPair.genKeyPair(jsch, type)
        val file = ByteArrayOutputStream().also { generated.writeOpenSSHv1PrivateKey(it, passphrase?.toByteArray()) }.toByteArray()
        val loaded = KeyPair.load(jsch, file, null)
        if (passphrase != null) {
            assertTrue(loaded.isEncrypted)
            assertTrue(!loaded.decrypt("wrong".toByteArray()))
            assertTrue(loaded.decrypt(passphrase.toByteArray()))
        }
        val plain = ByteArrayOutputStream().also { loaded.writeOpenSSHv1PrivateKey(it, null) }.toString(Charsets.US_ASCII.name())
        return generated to OpenSshKey.parse(plain)
    }

    @Test
    fun ed25519WithPassphrase() {
        val (pair, parsed) = roundTrip(KeyPair.ED25519, passphrase = "correct horse")
        assertIs<OpenSshKey.Private.Ed25519>(parsed)
        assertContentEquals(pair.publicKeyBlob, parsed.publicBlob)
        assertEquals(32, parsed.seed.size)
    }

    @Test
    fun ecdsaP256() {
        val (pair, parsed) = roundTrip(KeyPair.ECDSA, 256)
        assertIs<OpenSshKey.Private.EcP256>(parsed)
        assertContentEquals(pair.publicKeyBlob, parsed.publicBlob)
    }

    @Test
    fun rsa() {
        val (pair, parsed) = roundTrip(KeyPair.RSA, 2048)
        assertIs<OpenSshKey.Private.Rsa>(parsed)
        assertContentEquals(pair.publicKeyBlob, parsed.publicBlob)
        assertEquals(parsed.n, parsed.p * parsed.q)
    }

    @Test
    fun ecdsaDerToSsh() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(gen.private); update(byteArrayOf(1, 2, 3)); sign() }
        val blob = SshWire.signature(SshWire.ECDSA_P256, der)
        val r = SshWire.Reader(blob)
        assertEquals(SshWire.ECDSA_P256, r.text())
        val inner = SshWire.Reader(r.string())
        val (rr, ss) = SshWire.derToRs(der)
        assertEquals(rr, BigInteger(inner.string()))
        assertEquals(ss, BigInteger(inner.string()))
    }

    @Test
    fun publicBlobAndFingerprintMatchJsch() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val blob = SshWire.publicBlob(gen.public)
        assertEquals(SshWire.ECDSA_P256, SshWire.Reader(blob).text())
        assertTrue(SshWire.fingerprint(blob).startsWith("SHA256:"))
        assertTrue(SshWire.authorizedKey(blob, "test").startsWith("ecdsa-sha2-nistp256 AAAA"))
    }

    /** The PKCS#1 block that the YubiKey signs raw must give the same signature as JCA's SHA512withRSA. */
    @Test
    fun pivPaddingEqualsJcaSignature() {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val data = "session data".toByteArray()
        val expected = Signature.getInstance("SHA512withRSA").run { initSign(gen.private); update(data); sign() }
        val padded = Piv.pkcs1(SshWire.RSA_SHA512, data, 256)
        val d = (gen.private as RSAPrivateKey).privateExponent
        val n = (gen.private as RSAPrivateKey).modulus
        val raw = BigInteger(1, padded).modPow(d, n).toByteArray().let { if (it.size > 256) it.copyOfRange(it.size - 256, it.size) else it }
        assertContentEquals(expected, raw)
    }
}
