package app.varco.keys

import java.math.BigInteger
import java.util.Base64

/**
 * Reads an unencrypted OpenSSH private key ("openssh-key-v1", PROTOCOL.key in OpenSSH). jsch decrypts keys
 * and writes this form; this code takes out the key numbers, so that the Keystore can import them.
 */
object OpenSshKey {
    sealed class Private {
        abstract val publicBlob: ByteArray
        abstract val comment: String

        class Ed25519(override val publicBlob: ByteArray, override val comment: String, val seed: ByteArray) : Private()

        class EcP256(override val publicBlob: ByteArray, override val comment: String, val d: BigInteger) : Private()

        class Rsa(
            override val publicBlob: ByteArray,
            override val comment: String,
            val n: BigInteger,
            val e: BigInteger,
            val d: BigInteger,
            val p: BigInteger,
            val q: BigInteger,
            val iqmp: BigInteger,
        ) : Private()

        /** A type the Keystore cannot hold (for example ecdsa-sha2-nistp384); kept as a file key. */
        class Other(override val publicBlob: ByteArray, override val comment: String, val type: String) : Private()
    }

    private const val BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val END = "-----END OPENSSH PRIVATE KEY-----"
    private val MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)

    fun parse(pem: String): Private {
        val body = pem.substringAfter(BEGIN, "").substringBefore(END, "")
        require(body.isNotBlank()) { "Not an OpenSSH private key" }
        val data = Base64.getMimeDecoder().decode(body.trim())
        require(data.size > MAGIC.size && data.copyOf(MAGIC.size).contentEquals(MAGIC)) { "Not an OpenSSH private key" }
        val r = SshWire.Reader(data.copyOfRange(MAGIC.size, data.size))
        require(r.text() == "none" && r.text() == "none") { "The key is still encrypted" }
        r.string() // KDF options
        require(r.uint32() == 1) { "Only files with one key are supported" }
        val publicBlob = r.string()
        val p = SshWire.Reader(r.string())
        require(p.uint32() == p.uint32()) { "Damaged key (check numbers differ)" }
        return when (val type = p.text()) {
            SshWire.ED25519 -> {
                p.string() // public key, 32 bytes
                val secret = p.string() // seed (32) + public key (32)
                require(secret.size == 64) { "Damaged Ed25519 key" }
                Private.Ed25519(publicBlob, p.text(), secret.copyOf(32))
            }
            SshWire.ECDSA_P256 -> {
                require(p.text() == "nistp256") { "Damaged ECDSA key" }
                p.string() // public point
                val d = BigInteger(p.string())
                Private.EcP256(publicBlob, p.text(), d)
            }
            SshWire.RSA -> {
                val n = BigInteger(p.string())
                val e = BigInteger(p.string())
                val d = BigInteger(p.string())
                val iqmp = BigInteger(p.string())
                val pp = BigInteger(p.string())
                val q = BigInteger(p.string())
                Private.Rsa(publicBlob, p.text(), n, e, d, pp, q, iqmp)
            }
            else -> Private.Other(publicBlob, "", type)
        }
    }
}
