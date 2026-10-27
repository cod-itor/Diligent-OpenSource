package com.vscodroid.diligent.devices

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ditarector.diligent.sync.device.DeviceId
import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.port.Identity
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals and opens this device's private key at rest. On Android the sealing
 * key lives in the Android Keystore ([AndroidKeystoreWrapper]); JVM tests use
 * an in-memory key.
 */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(sealed: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key in the Android Keystore; output is the IV then the ciphertext. */
class AndroidKeystoreWrapper(private val alias: String = ALIAS) : KeyWrapper {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray = GcmSeal.seal(key(), plain)

    override fun unwrap(sealed: ByteArray): ByteArray = GcmSeal.open(key(), sealed)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "diligent_link_identity"
    }
}

/** AES-GCM with a fresh 12-byte IV per seal, as both wrappers use it. */
internal object GcmSeal {
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "unexpected IV length" }
        return iv + cipher.doFinal(plain)
    }

    fun open(key: SecretKey, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }
}

/**
 * This device's diligent-link identity (SPEC A.1): an ECDSA P-256 key and a
 * self-signed X.509 certificate valid 20 years, made with Bouncy Castle the
 * first time it is needed and kept in [dir]. The private key is stored only
 * sealed by [wrapper]; the certificate is public and stored as DER.
 *
 * The key is a software key rather than a Keystore key because TLS needs it
 * in a KeyManager on every platform the same way; the Keystore key protects
 * it at rest.
 */
class DeviceIdentity private constructor(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
) : Identity {
    private val der: ByteArray = certificate.encoded

    override fun certificateDer(): ByteArray = der.copyOf()

    val fingerprint: Fingerprint get() = Fingerprint.ofCertificate(der, JvmHashing)
    val deviceId: DeviceId get() = fingerprint.deviceId

    companion object {
        const val VALID_YEARS = 20
        private const val CERT_FILE = "device.crt"
        private const val KEY_FILE = "device.key.sealed"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Loads the identity in [dir], creating it on first use. */
        @Synchronized
        fun loadOrCreate(dir: File, wrapper: KeyWrapper, nowMs: Long = System.currentTimeMillis()): DeviceIdentity {
            val certFile = File(dir, CERT_FILE)
            val keyFile = File(dir, KEY_FILE)
            if (certFile.isFile && keyFile.isFile) {
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(wrapper.unwrap(keyFile.readBytes())))
                return DeviceIdentity(cert, key)
            }
            val created = create(nowMs)
            dir.mkdirs()
            writeAtomically(keyFile, wrapper.wrap(created.privateKey.encoded))
            writeAtomically(certFile, created.der)
            return created
        }

        /** A new key and certificate; never stored. */
        fun create(nowMs: Long = System.currentTimeMillis()): DeviceIdentity {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val pair = generator.generateKeyPair()
            val name = X500Name("CN=Diligent device")
            // A day of slack before now, so a peer whose clock is a little behind still accepts it.
            val notBefore = Date(nowMs - DAY_MS)
            val notAfter = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = nowMs
                add(Calendar.YEAR, VALID_YEARS)
            }.time
            val serial = BigInteger(127, SecureRandom()).add(BigInteger.ONE)
            // Bouncy Castle's ASN.1 builds the certificate body (RFC 5280); the
            // platform's Signature signs it. Nothing here is home-made crypto.
            val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(serial))
                setIssuer(name)
                setSubject(name)
                setStartDate(Time(notBefore))
                setEndDate(Time(notAfter))
                setSignature(algorithm)
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
            }.generateTBSCertificate()
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(tbs.encoded)
                sign()
            }
            val der = DERSequence(ASN1EncodableVector().apply {
                add(tbs)
                add(algorithm)
                add(DERBitString(signature))
            }).encoded
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return DeviceIdentity(cert, pair.private)
        }

        private fun writeAtomically(target: File, bytes: ByteArray) {
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeBytes(bytes)
            check(temp.renameTo(target)) { "could not store ${target.name}" }
        }
    }
}
