package com.vscodroid.diligent.devices

import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.port.Connection
import com.ditarector.diligent.sync.port.ConnectionAcceptor
import com.ditarector.diligent.sync.port.ConnectionHandler
import com.ditarector.diligent.sync.port.Endpoint
import com.ditarector.diligent.sync.port.PathFilter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/** A [KeyWrapper] with an in-memory AES key, standing in for the Android Keystore. */
class MemoryKeyWrapper : KeyWrapper {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun wrap(plain: ByteArray): ByteArray = GcmSeal.seal(key, plain)
    override fun unwrap(sealed: ByteArray): ByteArray = GcmSeal.open(key, sealed)
}

class LinkAdaptersTest {

    // ---- SRP ----

    @Test
    fun `SRP client and server agree on the key with the right code`() {
        val srp = BcSrp()
        val server = srp.newServer("482913")
        val challenge = server.challenge()
        val client = srp.newClient("482913")
        val proof = client.respond(challenge.salt, challenge.b)
        val m2 = server.verify(proof.a, proof.m1)
        assertNotNull(m2)
        assertTrue(client.verifyServer(m2!!))
        assertArrayEquals(server.sessionKey(), client.sessionKey())
    }

    @Test
    fun `SRP refuses a wrong code`() {
        val srp = BcSrp()
        val server = srp.newServer("482913")
        val challenge = server.challenge()
        val proof = srp.newClient("482914").respond(challenge.salt, challenge.b)
        assertNull(server.verify(proof.a, proof.m1))
        assertThrows<IllegalStateException> { server.sessionKey() }
    }

    @Test
    fun `SRP client refuses a server that did not know the code`() {
        val srp = BcSrp()
        val server = srp.newServer("111111")
        val challenge = server.challenge()
        val client = srp.newClient("111111")
        client.respond(challenge.salt, challenge.b)
        assertFalse(client.verifyServer(ByteArray(64) { 7 }))
    }

    // ---- Identity ----

    @Test
    fun `identity is an ECDSA P-256 certificate valid 20 years, stored sealed and reloaded`(@TempDir dir: File) {
        val wrapper = MemoryKeyWrapper()
        val now = System.currentTimeMillis()
        val first = DeviceIdentity.loadOrCreate(dir, wrapper, now)
        assertEquals("EC", first.certificate.publicKey.algorithm)
        assertEquals("SHA256withECDSA", first.certificate.sigAlgName)
        first.certificate.verify(first.certificate.publicKey)
        val years = (first.certificate.notAfter.time - now) / (365.25 * 24 * 3600 * 1000)
        assertTrue(years > 19.99 && years < 20.01, "valid $years years")
        // The private key is not on disk in the clear.
        val sealed = File(dir, "device.key.sealed").readBytes()
        assertFalse(Files.readAllBytes(File(dir, "device.key.sealed").toPath()).contentEquals(first.privateKey.encoded))
        assertTrue(sealed.isNotEmpty())

        val again = DeviceIdentity.loadOrCreate(dir, wrapper)
        assertArrayEquals(first.certificateDer(), again.certificateDer())
        assertArrayEquals(first.privateKey.encoded, again.privateKey.encoded)
    }

    @Test
    fun `device ID is the base32 of the certificate SHA-256, 32 characters`() {
        val identity = DeviceIdentity.create()
        val id = identity.deviceId.value
        assertEquals(32, id.length)
        assertTrue(id.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
        assertEquals(identity.deviceId, Fingerprint.ofDigest(JvmHashing.sha256(identity.certificateDer())).deviceId)
        assertEquals(8, identity.deviceId.display.split('-').size)
    }

    // ---- Transport ----

    private class Collect : ConnectionHandler {
        val bytes = ArrayBlockingQueue<ByteArray>(16)
        override fun onBytes(bytes: ByteArray) {
            this.bytes.put(bytes)
        }
        override fun onClosed(error: Throwable?) = Unit
    }

    private fun echoServer(identity: DeviceIdentity): Pair<TlsTransport, Int> {
        val transport = TlsTransport(identity, bindHost = "127.0.0.1")
        val port = transport.listen(0, ConnectionAcceptor { connection: Connection ->
            object : ConnectionHandler {
                override fun onBytes(bytes: ByteArray) = connection.send(bytes)
                override fun onClosed(error: Throwable?) = Unit
            }
        })
        return transport to port
    }

    @Test
    fun `TLS 1_3 handshake on loopback with mutual certificates, unpinned and pinned`() {
        val server = DeviceIdentity.create()
        val client = DeviceIdentity.create()
        val (serverTransport, port) = echoServer(server)
        try {
            val transport = TlsTransport(client)
            for (pin in listOf(null, server.fingerprint)) {
                val handler = Collect()
                val connection = transport.connect(Endpoint("127.0.0.1", port), pin, handler)
                assertArrayEquals(server.certificateDer(), connection.peerCertificateDer)
                connection.send("hello".toByteArray())
                assertEquals("hello", String(handler.bytes.poll(5, TimeUnit.SECONDS)!!))
                connection.close()
            }
        } finally {
            serverTransport.stopListening()
        }
    }

    @Test
    fun `TLS refuses a peer whose certificate is not the pinned one`() {
        val server = DeviceIdentity.create()
        val other = DeviceIdentity.create()
        val (serverTransport, port) = echoServer(server)
        try {
            val error = assertThrows<IOException> {
                TlsTransport(DeviceIdentity.create()).connect(Endpoint("127.0.0.1", port), other.fingerprint, Collect())
            }
            assertTrue(isPinMismatch(error), "pin mismatch in $error")
        } finally {
            serverTransport.stopListening()
        }
    }

    @Test
    fun `listen falls back to a free port when the asked one is taken`() {
        val (first, port) = echoServer(DeviceIdentity.create())
        try {
            val second = TlsTransport(DeviceIdentity.create(), bindHost = "127.0.0.1")
            val other = second.listen(port, ConnectionAcceptor { Collect() })
            assertTrue(other != port && other > 0)
            second.stopListening()
        } finally {
            first.stopListening()
        }
    }

    // ---- FileStore ----

    @Test
    fun `received files go through a temporary file outside the project, then commit`(@TempDir root: File, @TempDir temp: File) {
        val store = ProjectFileStore(root, temp)
        store.writeTemp("src/a.txt", 0, "hel".toByteArray())
        store.writeTemp("src/a.txt", 3, "lo".toByteArray())
        assertFalse(File(root, "src/a.txt").exists())
        assertEquals(JvmHashing.sha256Hex("hello".toByteArray()), store.tempSha256("src/a.txt"))
        assertTrue(store.list(PathFilter { _, _ -> true }).isEmpty())
        store.commitTemp("src/a.txt", "src/a.txt", executable = true)
        assertEquals("hello", File(root, "src/a.txt").readText())
        assertTrue(File(root, "src/a.txt").canExecute())
        assertEquals(listOf("src/a.txt"), store.list(PathFilter { _, _ -> true }).map { it.path })
        assertEquals("hello", store.readText("src/a.txt"))
        assertArrayEquals("ell".toByteArray(), store.read("src/a.txt", 1, 3))
        assertTrue(temp.listFiles().orEmpty().isEmpty())

        store.delete("src/a.txt")
        assertFalse(File(root, "src").exists())
    }

    @Test
    fun `a write at the wrong offset is refused and a discarded temp is gone`(@TempDir root: File, @TempDir temp: File) {
        val store = ProjectFileStore(root, temp)
        store.writeTemp("a", 0, "x".toByteArray())
        assertThrows<IOException> { store.writeTemp("a", 5, "y".toByteArray()) }
        store.discardTemp("a")
        assertTrue(temp.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `paths leaving the project and symbolic links are refused`(@TempDir root: File, @TempDir outside: File, @TempDir temp: File) {
        val store = ProjectFileStore(root, temp)
        File(outside, "secret.txt").writeText("secret")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "file-link").toPath(), File(outside, "secret.txt").toPath())
        assertThrows<IOException> { store.readText("../x") }
        assertThrows<IOException> { store.readText("/etc/passwd") }
        assertThrows<IOException> { store.readText("link/secret.txt") }
        assertThrows<IOException> { store.sha256("file-link") }
        store.writeTemp("ok.txt", 0, "data".toByteArray())
        assertThrows<IOException> { store.commitTemp("ok.txt", "link/new.txt", false) }
        assertThrows<IOException> { store.delete("link/secret.txt") }
        assertFalse(File(outside, "new.txt").exists())
        assertTrue(File(outside, "secret.txt").exists())
        // Listing skips links instead of following them.
        assertTrue(store.list(PathFilter { _, _ -> true }).isEmpty())
    }
}
