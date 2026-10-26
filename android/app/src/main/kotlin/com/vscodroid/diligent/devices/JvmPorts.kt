package com.vscodroid.diligent.devices

import com.ditarector.diligent.sync.port.Clock
import com.ditarector.diligent.sync.port.Hashing
import com.ditarector.diligent.sync.port.SecureRandomSource
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** sync-core's [Hashing] over the platform's `MessageDigest` and `Mac`. */
object JvmHashing : Hashing {
    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    /** Lowercase hex SHA-256, the form manifests use. */
    fun sha256Hex(data: ByteArray): String = hex(sha256(data))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** Wall-clock time for pairing expiry, lockout and the pairing record. */
object SystemClockPort : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Pairing codes and SRP values come from `SecureRandom`, never `kotlin.random`. */
class JvmSecureRandom(private val random: SecureRandom = SecureRandom()) : SecureRandomSource {
    override fun nextBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)
}
