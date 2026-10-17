package app.varco.keys

import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.piv.KeyType
import com.yubico.yubikit.piv.PivSession
import com.yubico.yubikit.piv.Slot
import java.security.MessageDigest
import com.yubico.yubikit.core.keys.PublicKeyValues

/**
 * SSH keys on the PIV application of a YubiKey (what OpenSSH uses through PKCS#11). Varco reads the public
 * keys of the four main slots and signs with the PIN. RSA, ECDSA P-256 and Ed25519 (firmware 5.7+) keys work.
 */
object Piv {
    /** The slots that hold user keys: 9a (authentication, the usual SSH slot), 9c, 9d, 9e. */
    val SLOTS = listOf(Slot.AUTHENTICATION, Slot.SIGNATURE, Slot.KEY_MANAGEMENT, Slot.CARD_AUTH)

    /** A key in [slot]: its PIV type and its SSH public key blob. */
    class SlotKey(val slot: Slot, val keyType: KeyType, val blob: ByteArray) {
        val sshType: String get() = SshWire.Reader(blob).text()
    }

    /** Public keys of the slots that have an SSH-usable key. Needs no PIN. */
    fun readKeys(connection: SmartCardConnection): List<SlotKey> {
        val piv = PivSession(connection)
        return SLOTS.mapNotNull { slot -> runCatching { slotKey(piv, slot) }.getOrNull() }
    }

    /**
     * Verifies [pin] and signs [data] with the key in [slot], after a check that the slot holds the key with
     * the public blob [expected]. Returns the SSH signature blob.
     * Throws [com.yubico.yubikit.core.application.InvalidPinException] on a wrong PIN.
     */
    fun sign(
        connection: SmartCardConnection,
        slot: Slot,
        expected: ByteArray,
        pin: CharArray,
        data: ByteArray,
        sshAlgorithm: String,
    ): ByteArray {
        val piv = PivSession(connection)
        val key = slotKey(piv, slot)
        if (!key.blob.contentEquals(expected)) throw WrongKeyException()
        piv.verifyPin(pin)
        val keyType = key.keyType
        return when (keyType.params.algorithm) {
            KeyType.Algorithm.RSA -> {
                val padded = pkcs1(sshAlgorithm, data, keyType.params.bitLength / 8)
                SshWire.signature(sshAlgorithm, piv.rawSignOrDecrypt(slot, keyType, padded))
            }
            else -> if (keyType == KeyType.ED25519) {
                SshWire.signature(SshWire.ED25519, piv.rawSignOrDecrypt(slot, keyType, data))
            } else {
                require(keyType == KeyType.ECCP256) { "Only P-256 ECDSA keys work for SSH" }
                val hash = MessageDigest.getInstance("SHA-256").digest(data)
                SshWire.signature(SshWire.ECDSA_P256, piv.rawSignOrDecrypt(slot, keyType, hash))
            }
        }
    }

    class WrongKeyException : Exception("This YubiKey does not hold the key of this server")

    fun slot(id: Int): Slot = SLOTS.first { it.value == id }

    /**
     * The key in [slot]: from the slot metadata (firmware 5.3+), else from the slot's certificate. Ed25519 keys
     * use their raw bytes, because Android has no JCA KeyFactory for Ed25519 outside the Keystore.
     */
    private fun slotKey(piv: PivSession, slot: Slot): SlotKey {
        if (piv.supports(PivSession.FEATURE_METADATA)) {
            val metadata = piv.getSlotMetadata(slot)
            val values = metadata.publicKeyValues
            val blob = if (values is PublicKeyValues.Cv25519) {
                require(metadata.keyType == KeyType.ED25519) { "X25519 keys cannot sign" }
                SshWire.ed25519Blob(values.bytes)
            } else {
                SshWire.publicBlob(values.toPublicKey())
            }
            return SlotKey(slot, metadata.keyType, blob)
        }
        val key = piv.getCertificate(slot).publicKey
        return SlotKey(slot, KeyType.fromKey(key), SshWire.publicBlob(key))
    }

    /** EMSA-PKCS1-v1_5 (RFC 8017 §9.2): 00 01 FF… 00 DigestInfo(hash of [data]), [size] bytes. */
    internal fun pkcs1(sshAlgorithm: String, data: ByteArray, size: Int): ByteArray {
        val (digest, prefix) = when (sshAlgorithm) {
            SshWire.RSA_SHA512 -> "SHA-512" to SHA512_PREFIX
            SshWire.RSA_SHA256 -> "SHA-256" to SHA256_PREFIX
            else -> throw IllegalArgumentException("RSA with SHA-1 (ssh-rsa) is not allowed")
        }
        val t = prefix + MessageDigest.getInstance(digest).digest(data)
        require(size >= t.size + 11) { "RSA key too small" }
        return byteArrayOf(0, 1) + ByteArray(size - t.size - 3) { 0xff.toByte() } + byteArrayOf(0) + t
    }

    private val SHA256_PREFIX = hex("3031300d060960864801650304020105000420")
    private val SHA512_PREFIX = hex("3051300d060960864801650304020305000440")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
