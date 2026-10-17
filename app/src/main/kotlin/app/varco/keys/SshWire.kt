package app.varco.keys

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** SSH wire encoding (RFC 4251 §5) of public keys and signatures. */
object SshWire {
    const val ED25519 = "ssh-ed25519"
    const val ECDSA_P256 = "ecdsa-sha2-nistp256"
    const val RSA = "ssh-rsa"
    const val RSA_SHA256 = "rsa-sha2-256"
    const val RSA_SHA512 = "rsa-sha2-512"
    private val ED25519_NAMES = setOf("Ed25519", "EdDSA", "ED25519", "1.3.101.112")

    class Writer {
        private val out = ByteArrayOutputStream()

        fun uint32(v: Int) = apply {
            out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v)
        }

        fun string(bytes: ByteArray) = apply { uint32(bytes.size); out.write(bytes) }
        fun string(text: String) = string(text.toByteArray(Charsets.UTF_8))
        fun mpint(v: BigInteger) = string(v.toByteArray())
        fun bytes() = out.toByteArray()
    }

    class Reader(private val data: ByteArray) {
        private var pos = 0
        val remaining get() = data.size - pos

        fun uint32(): Int {
            require(remaining >= 4) { "Truncated SSH data" }
            var v = 0
            repeat(4) { v = (v shl 8) or (data[pos++].toInt() and 0xff) }
            return v
        }

        fun string(): ByteArray {
            val n = uint32()
            require(n in 0..remaining) { "Truncated SSH data" }
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun text() = string().toString(Charsets.UTF_8)
    }

    /** The SSH key type of a JCA public key. */
    fun typeOf(key: PublicKey): String = when {
        key is RSAPublicKey -> RSA
        key is ECPublicKey -> ECDSA_P256.also { require(key.params.curve.field.fieldSize == 256) { "Only P-256" } }
        // The Keystore names its Ed25519 keys by OID (AndroidKeyStoreEdECPublicKey).
        key is EdECPublicKey || key.algorithm in ED25519_NAMES -> ED25519
        else -> throw IllegalArgumentException("Key type not supported: ${key.algorithm}")
    }

    /** The public key blob (type + key data), as in authorized_keys after base64. */
    fun publicBlob(key: PublicKey): ByteArray = when (val type = typeOf(key)) {
        RSA -> (key as RSAPublicKey).let { Writer().string(RSA).mpint(it.publicExponent).mpint(it.modulus).bytes() }
        ECDSA_P256 -> Writer().string(type).string("nistp256").string(ecPoint(key as ECPublicKey)).bytes()
        else -> Writer().string(ED25519).string(ed25519Raw(key)).bytes()
    }

    /** "type base64 comment", one authorized_keys line. */
    fun authorizedKey(blob: ByteArray, comment: String): String {
        val type = Reader(blob).text()
        return "$type ${Base64.getEncoder().encodeToString(blob)} $comment".trim()
    }

    /** "SHA256:…", the fingerprint form of OpenSSH. */
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** The signature blob: string algorithm + string signature. For ECDSA, [raw] is the JCA DER form. */
    fun signature(algorithm: String, raw: ByteArray): ByteArray = when (algorithm) {
        ECDSA_P256 -> {
            val (r, s) = derToRs(raw)
            Writer().string(algorithm).string(Writer().mpint(r).mpint(s).bytes()).bytes()
        }
        else -> Writer().string(algorithm).string(raw).bytes()
    }

    /** The JCA signature algorithm for an SSH signature algorithm. */
    fun jcaAlgorithm(sshAlgorithm: String): String = when (sshAlgorithm) {
        ED25519 -> "Ed25519"
        ECDSA_P256 -> "SHA256withECDSA"
        RSA_SHA512 -> "SHA512withRSA"
        RSA_SHA256 -> "SHA256withRSA"
        RSA -> "SHA1withRSA"
        else -> throw IllegalArgumentException("Signature algorithm not supported: $sshAlgorithm")
    }

    private fun ecPoint(key: ECPublicKey): ByteArray {
        val x = unsigned(key.w.affineX, 32)
        val y = unsigned(key.w.affineY, 32)
        return byteArrayOf(4) + x + y
    }

    /** The 32 raw bytes of an Ed25519 key: the end of its X.509 (SubjectPublicKeyInfo) form. */
    private fun ed25519Raw(key: PublicKey): ByteArray {
        val encoded = key.encoded
        require(encoded.size >= 32) { "Bad Ed25519 key" }
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    private fun unsigned(v: BigInteger, size: Int): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == size -> b
            b.size > size -> b.copyOfRange(b.size - size, b.size)
            else -> ByteArray(size - b.size) + b
        }
    }

    /** DER SEQUENCE { INTEGER r, INTEGER s } -> (r, s). */
    internal fun derToRs(der: ByteArray): Pair<BigInteger, BigInteger> {
        var pos = 0
        fun length(): Int {
            val first = der[pos++].toInt() and 0xff
            if (first < 0x80) return first
            var n = 0
            repeat(first and 0x7f) { n = (n shl 8) or (der[pos++].toInt() and 0xff) }
            return n
        }
        fun integer(): BigInteger {
            require(der[pos++].toInt() == 0x02) { "Bad ECDSA signature" }
            val n = length()
            return BigInteger(der.copyOfRange(pos, pos + n)).also { pos += n }
        }
        require(der[pos++].toInt() == 0x30) { "Bad ECDSA signature" }
        length()
        return integer() to integer()
    }
}
