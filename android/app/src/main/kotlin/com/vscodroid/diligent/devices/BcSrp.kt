package com.vscodroid.diligent.devices

import com.ditarector.diligent.sync.port.Srp
import com.ditarector.diligent.sync.port.SrpChallenge
import com.ditarector.diligent.sync.port.SrpClient
import com.ditarector.diligent.sync.port.SrpProof
import com.ditarector.diligent.sync.port.SrpServer
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6Server
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.security.SecureRandom

/**
 * SRP-6a exactly as SPEC A.6 fixes it, with Bouncy Castle's `SRP6Server` and
 * `SRP6Client`: the RFC 5054 3072-bit group, SHA-512, username
 * [Srp.USERNAME], password the 6 digits. Nothing here computes SRP itself;
 * it only converts between Bouncy Castle's `BigInteger`s and the unsigned
 * big-endian bytes sync-core carries.
 */
class BcSrp(private val random: SecureRandom = SecureRandom()) : Srp {
    private val group = SRP6StandardGroups.rfc5054_3072
    private val identity = Srp.USERNAME.toByteArray(Charsets.UTF_8)

    override fun newServer(password: String): SrpServer = Server(password.toByteArray(Charsets.UTF_8))

    override fun newClient(password: String): SrpClient = Client(password.toByteArray(Charsets.UTF_8))

    private inner class Server(private val password: ByteArray) : SrpServer {
        private val server = SRP6Server()
        private var verified = false

        override fun challenge(): SrpChallenge {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val generator = SRP6VerifierGenerator().apply { init(group, SHA512Digest()) }
            val verifier = generator.generateVerifier(salt, identity, password)
            server.init(group, verifier, SHA512Digest(), random)
            val b = server.generateServerCredentials()
            return SrpChallenge(salt, BigIntegers.asUnsignedByteArray(b))
        }

        override fun verify(a: ByteArray, m1: ByteArray): ByteArray? = try {
            server.calculateSecret(BigInteger(1, a))
            if (server.verifyClientEvidenceMessage(BigInteger(1, m1))) {
                verified = true
                BigIntegers.asUnsignedByteArray(server.calculateServerEvidenceMessage())
            } else {
                null
            }
        } catch (e: CryptoException) {
            // An illegal A (a multiple of N) is refused like a wrong code.
            null
        }

        override fun sessionKey(): ByteArray {
            check(verified) { "no verified exchange" }
            return BigIntegers.asUnsignedByteArray(server.calculateSessionKey())
        }
    }

    private inner class Client(private val password: ByteArray) : SrpClient {
        private val client = SRP6Client()
        private var verified = false

        override fun respond(salt: ByteArray, b: ByteArray): SrpProof {
            client.init(group, SHA512Digest(), random)
            val a = client.generateClientCredentials(salt, identity, password)
            client.calculateSecret(BigInteger(1, b))
            val m1 = client.calculateClientEvidenceMessage()
            return SrpProof(BigIntegers.asUnsignedByteArray(a), BigIntegers.asUnsignedByteArray(m1))
        }

        override fun verifyServer(m2: ByteArray): Boolean = try {
            client.verifyServerEvidenceMessage(BigInteger(1, m2)).also { verified = it }
        } catch (e: CryptoException) {
            false
        }

        override fun sessionKey(): ByteArray {
            check(verified) { "the server was not verified" }
            return BigIntegers.asUnsignedByteArray(client.calculateSessionKey())
        }
    }

    private companion object {
        const val SALT_BYTES = 32
    }
}
