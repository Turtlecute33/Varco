package app.varco.keys

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.StrongBoxUnavailableException
import app.varco.data.SshKey
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.EdECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Date
import java.util.Optional
import java.util.UUID

/**
 * Keys in the phone's secure hardware (AndroidKeyStore): StrongBox when the phone has it and the key type
 * allows it, else the TEE. Private keys made here never leave the hardware; imported keys go in once and
 * then also stay there. All keys work only while the phone is unlocked.
 */
class ChipKeys(private val context: Context) {
    private val store: KeyStore get() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** Ed25519 needs KeyMint 2 (TEE only; StrongBox has no Curve25519). */
    val supportsEd25519: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE, 200)

    val hasStrongBox: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** Makes a new key: Ed25519 (TEE) or ECDSA P-256 (StrongBox when there). */
    fun generate(name: String, ed25519: Boolean, authPerUse: Boolean): SshKey {
        val alias = newAlias()
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(if (ed25519) "ed25519" else "secp256r1"))
            .setDigests(if (ed25519) KeyProperties.DIGEST_NONE else KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .setUnlockedDeviceRequired(true)
            .apply { if (authPerUse) requireAuth(this) }
            .build()

        fun make(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            .apply { initialize(spec(strongBox)) }
            .generateKeyPair()

        val strongBox = !ed25519 && hasStrongBox
        val (pair, inStrongBox) = try {
            make(strongBox) to strongBox
        } catch (_: StrongBoxUnavailableException) {
            make(false) to false
        }
        val blob = try {
            SshWire.publicBlob(pair.public)
        } catch (e: Exception) {
            runCatching { delete(alias) }
            throw e
        }
        return SshKey(
            id = UUID.randomUUID().toString(),
            name = name,
            kind = SshKey.Kind.KEYSTORE,
            type = SshWire.typeOf(pair.public),
            publicKey = Base64.getEncoder().encodeToString(blob),
            alias = alias,
            authPerUse = authPerUse,
            strongBox = inStrongBox,
        )
    }

    /**
     * Moves an imported key into the Keystore. Returns null when this phone cannot hold the key type
     * (then the caller keeps it as an encrypted file key).
     */
    fun import(key: OpenSshKey.Private, name: String, authPerUse: Boolean): SshKey? {
        val (private, cert, digests) = when (key) {
            is OpenSshKey.Private.Ed25519 -> {
                if (!supportsEd25519) return null
                val signer = Ed25519PrivateKeyParameters(key.seed, 0)
                val spki = ED25519_SPKI_PREFIX + signer.generatePublicKey().encoded
                val cert = selfSigned(spki, ED25519_OID, null) { tbs ->
                    Ed25519Signer().run { init(true, signer); update(tbs, 0, tbs.size); generateSignature() }
                }
                Triple(Ed25519Pkcs8(key.seed), EcNamed(cert), arrayOf(KeyProperties.DIGEST_NONE))
            }
            is OpenSshKey.Private.EcP256 -> {
                val params = AlgorithmParameters.getInstance("EC").run {
                    init(ECGenParameterSpec("secp256r1"))
                    getParameterSpec(ECParameterSpec::class.java)
                }
                val q = SshWire.Reader(key.publicBlob).run { text(); text(); string() }
                val point = ECPoint(BigInteger(1, q.copyOfRange(1, 33)), BigInteger(1, q.copyOfRange(33, 65)))
                val factory = KeyFactory.getInstance("EC")
                val private = factory.generatePrivate(ECPrivateKeySpec(key.d, params))
                val spki = factory.generatePublic(ECPublicKeySpec(point, params)).encoded
                val cert = selfSigned(spki, ECDSA_SHA256_OID, null) { tbs ->
                    Signature.getInstance("SHA256withECDSA").run { initSign(private); update(tbs); sign() }
                }
                Triple(private, cert, arrayOf(KeyProperties.DIGEST_SHA256))
            }
            is OpenSshKey.Private.Rsa -> {
                val factory = KeyFactory.getInstance("RSA")
                val private = factory.generatePrivate(
                    RSAPrivateCrtKeySpec(
                        key.n, key.e, key.d, key.p, key.q,
                        key.d.mod(key.p - BigInteger.ONE), key.d.mod(key.q - BigInteger.ONE), key.iqmp,
                    ),
                )
                val spki = factory.generatePublic(RSAPublicKeySpec(key.n, key.e)).encoded
                val cert = selfSigned(spki, RSA_SHA256_OID, DERNull.INSTANCE) { tbs ->
                    Signature.getInstance("SHA256withRSA").run { initSign(private); update(tbs); sign() }
                }
                Triple(private, cert, arrayOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512))
            }
            is OpenSshKey.Private.Other -> return null
        }

        val alias = newAlias()
        val entry = runCatching { KeyStore.PrivateKeyEntry(private, arrayOf(cert)) }.getOrElse { return null }
        fun put(strongBox: Boolean) = runCatching {
            @SuppressLint("WrongConstant") // digests holds only KeyProperties.DIGEST_* values.
            val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                .setDigests(*digests)
                .setIsStrongBoxBacked(strongBox)
                .setUnlockedDeviceRequired(true)
                .apply { if (key is OpenSshKey.Private.Rsa) setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1) }
                .apply { if (authPerUse) requireAuth(this) }
                .build()
            store.setEntry(alias, entry, protection)
        }.isSuccess

        // StrongBox holds P-256 and RSA-2048 only.
        val strongBoxFits = hasStrongBox && (key is OpenSshKey.Private.EcP256 ||
            (key is OpenSshKey.Private.Rsa && key.n.bitLength() <= 2048))
        val inStrongBox = when {
            strongBoxFits && put(strongBox = true) -> true
            put(strongBox = false) -> false
            else -> {
                runCatching { delete(alias) }
                return null
            }
        }
        val type = SshWire.Reader(key.publicBlob).text()
        return SshKey(
            id = UUID.randomUUID().toString(),
            name = name,
            kind = SshKey.Kind.KEYSTORE,
            type = type,
            publicKey = Base64.getEncoder().encodeToString(key.publicBlob),
            alias = alias,
            authPerUse = authPerUse,
            strongBox = inStrongBox,
        )
    }

    /** A Signature ready for [Signature.update]; for keys with auth per use, it needs a BiometricPrompt first. */
    fun signer(key: SshKey, sshAlgorithm: String): Signature {
        val private = store.getKey(requireNotNull(key.alias), null) as? PrivateKey
            ?: throw IllegalStateException("The key \"${key.name}\" is no longer in the Keystore")
        return Signature.getInstance(SshWire.jcaAlgorithm(sshAlgorithm)).apply { initSign(private) }
    }

    fun delete(alias: String) {
        store.deleteEntry(alias)
    }

    private fun requireAuth(builder: KeyGenParameterSpec.Builder) = builder
        .setUserAuthenticationRequired(true)
        .setUserAuthenticationParameters(0, AUTH)

    private fun requireAuth(builder: KeyProtection.Builder) = builder
        .setUserAuthenticationRequired(true)
        .setUserAuthenticationParameters(0, AUTH)

    /** The Keystore needs a certificate with each imported key; this one only carries the public key. */
    private fun selfSigned(
        spki: ByteArray,
        algorithm: ASN1ObjectIdentifier,
        params: DERNull?,
        sign: (ByteArray) -> ByteArray,
    ): X509Certificate {
        val algId = if (params != null) AlgorithmIdentifier(algorithm, params) else AlgorithmIdentifier(algorithm)
        val name = X500Name("CN=Varco imported key")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(1))
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(0)))
            setEndDate(Time(Date(253402300799000L))) // 9999-12-31, "no expiry" (RFC 5280 §4.1.2.5)
            setSignature(algId)
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(spki))
        }.generateTBSCertificate()
        val cert = DERSequence(
            ASN1EncodableVector().apply {
                add(tbs)
                add(algId)
                add(DERBitString(sign(tbs.encoded)))
            },
        )
        return CertificateFactory.getInstance("X.509").generateCertificate(cert.encoded.inputStream()) as X509Certificate
    }

    /**
     * An Ed25519 private key in PKCS#8 form, only to hand it to the Keystore. The Keystore reads the curve
     * from [getParams] and needs the algorithm name "EC" (AndroidKeyStoreSpi.getKeymasterEcCurve).
     */
    private class Ed25519Pkcs8(private val seed: ByteArray) : EdECPrivateKey {
        override fun getAlgorithm() = "EC"
        override fun getFormat() = "PKCS#8"
        override fun getEncoded(): ByteArray = ED25519_PKCS8_PREFIX + seed
        override fun getParams(): NamedParameterSpec = NamedParameterSpec.ED25519
        override fun getBytes(): Optional<ByteArray> = Optional.of(seed.copyOf())
    }

    /**
     * [cert] with the key algorithm name "EC". KeyStore.PrivateKeyEntry needs the same name for the private
     * key and the certificate key, and the Keystore takes Ed25519 imports only as "EC" (with an EdEC key).
     * The Keystore reads only the certificate bytes, which stay the real Ed25519 certificate.
     */
    private class EcNamed(private val cert: X509Certificate) : X509Certificate() {
        private val key = object : PublicKey by cert.publicKey {
            override fun getAlgorithm() = "EC"
        }

        override fun getPublicKey(): PublicKey = key
        override fun getEncoded(): ByteArray = cert.encoded
        override fun verify(k: PublicKey) = cert.verify(k)
        override fun verify(k: PublicKey, provider: String) = cert.verify(k, provider)
        override fun toString() = cert.toString()
        override fun checkValidity() = cert.checkValidity()
        override fun checkValidity(date: Date) = cert.checkValidity(date)
        override fun getVersion() = cert.version
        override fun getSerialNumber(): BigInteger = cert.serialNumber
        @Deprecated("Deprecated in Java")
        override fun getIssuerDN(): java.security.Principal = cert.issuerX500Principal
        @Deprecated("Deprecated in Java")
        override fun getSubjectDN(): java.security.Principal = cert.subjectX500Principal
        override fun getNotBefore(): Date = cert.notBefore
        override fun getNotAfter(): Date = cert.notAfter
        override fun getTBSCertificate(): ByteArray = cert.tbsCertificate
        override fun getSignature(): ByteArray = cert.signature
        override fun getSigAlgName(): String = cert.sigAlgName
        override fun getSigAlgOID(): String = cert.sigAlgOID
        override fun getSigAlgParams(): ByteArray? = cert.sigAlgParams
        override fun getIssuerUniqueID(): BooleanArray? = cert.issuerUniqueID
        override fun getSubjectUniqueID(): BooleanArray? = cert.subjectUniqueID
        override fun getKeyUsage(): BooleanArray? = cert.keyUsage
        override fun getBasicConstraints() = cert.basicConstraints
        override fun hasUnsupportedCriticalExtension() = cert.hasUnsupportedCriticalExtension()
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = cert.criticalExtensionOIDs
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = cert.nonCriticalExtensionOIDs
        override fun getExtensionValue(oid: String?): ByteArray? = cert.getExtensionValue(oid)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val AUTH = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        val ED25519_OID = ASN1ObjectIdentifier("1.3.101.112")
        val ECDSA_SHA256_OID = ASN1ObjectIdentifier("1.2.840.10045.4.3.2")
        val RSA_SHA256_OID = ASN1ObjectIdentifier("1.2.840.113549.1.1.11")

        // SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING (32 bytes) } without the key bytes (RFC 8410).
        val ED25519_SPKI_PREFIX = hex("302a300506032b6570032100")

        // SEQUENCE { INTEGER 0, SEQUENCE { OID 1.3.101.112 }, OCTET STRING { OCTET STRING (32 bytes) } } (RFC 8410).
        val ED25519_PKCS8_PREFIX = hex("302e020100300506032b657004220420")

        fun newAlias() = "varco.key." + UUID.randomUUID()

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
