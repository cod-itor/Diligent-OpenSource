package com.vscodroid.diligent.devices

import android.annotation.SuppressLint
import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.port.Connection
import com.ditarector.diligent.sync.port.ConnectionAcceptor
import com.ditarector.diligent.sync.port.ConnectionHandler
import com.ditarector.diligent.sync.port.Endpoint
import com.ditarector.diligent.sync.port.Transport
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** The peer showed a certificate other than the pinned one: an identity change (SPEC A.4). */
class PinMismatchException(val presented: Fingerprint) : CertificateException("the peer's certificate is not the pinned one")

/** True when [error] or one of its causes is a [PinMismatchException]. */
fun isPinMismatch(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.take(10).any { it is PinMismatchException }

/**
 * diligent-link's TCP with mutual TLS 1.3 (SPEC A.4) over the platform's
 * `SSLSocket` and `SSLServerSocket`. Both sides present [identity]'s
 * certificate. The trust manager accepts any certificate only when no pin is
 * given (pairing, and every incoming connection, whose pin the app checks
 * against its registry); with a pin it refuses every other certificate in
 * the handshake.
 *
 * Each connection gets one reader thread that hands bytes to its
 * [ConnectionHandler]; writes are serialised per connection.
 */
class TlsTransport(
    private val identity: DeviceIdentity,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val bindHost: String? = null,
) : Transport {
    private var server: SSLServerSocket? = null

    private fun context(pinned: Fingerprint?): SSLContext =
        SSLContext.getInstance(PROTOCOL).apply {
            init(arrayOf(OwnKey(identity.certificate, identity.privateKey)), arrayOf(PinTrust(pinned)), SecureRandom())
        }

    override fun connect(endpoint: Endpoint, pinned: Fingerprint?, handler: ConnectionHandler): Connection {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
            raw.soTimeout = connectTimeoutMs
            val socket = context(pinned).socketFactory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
            socket.enabledProtocols = arrayOf(PROTOCOL)
            socket.useClientMode = true
            socket.startHandshake()
            socket.soTimeout = 0
            return TlsConnection(socket, handler).also { it.startReading() }
        } catch (e: IOException) {
            raw.closeQuietly()
            throw e
        }
    }

    @Synchronized
    override fun listen(port: Int, acceptor: ConnectionAcceptor): Int {
        server?.let { return it.localPort }
        val factory = context(pinned = null).serverSocketFactory
        var socket = factory.createServerSocket() as SSLServerSocket
        try {
            socket.bindTo(port)
        } catch (e: BindException) {
            // The default port is taken: any free port, advertised over DNS-SD (SPEC A.2).
            socket.closeQuietly()
            if (port == 0) throw e
            socket = factory.createServerSocket() as SSLServerSocket
            socket.bindTo(0)
        }
        socket.enabledProtocols = arrayOf(PROTOCOL)
        socket.needClientAuth = true
        server = socket
        Thread({ acceptLoop(socket, acceptor) }, "diligent-link-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    private fun java.net.ServerSocket.bindTo(port: Int) {
        reuseAddress = true
        bind(if (bindHost == null) InetSocketAddress(port) else InetSocketAddress(bindHost, port))
    }

    @Synchronized
    override fun stopListening() {
        server?.closeQuietly()
        server = null
    }

    private fun acceptLoop(socket: SSLServerSocket, acceptor: ConnectionAcceptor) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept() as SSLSocket
            } catch (e: IOException) {
                break
            }
            Thread({ handshakeIncoming(client, acceptor) }, "diligent-link-in").apply { isDaemon = true }.start()
        }
    }

    private fun handshakeIncoming(socket: SSLSocket, acceptor: ConnectionAcceptor) {
        try {
            socket.soTimeout = connectTimeoutMs
            socket.startHandshake()
            socket.soTimeout = 0
            val connection = TlsConnection(socket, handler = null)
            connection.handler = acceptor.accept(connection)
            connection.startReading()
        } catch (e: IOException) {
            socket.closeQuietly()
        }
    }

    private class TlsConnection(private val socket: SSLSocket, @Volatile var handler: ConnectionHandler?) : Connection {
        override val peerCertificateDer: ByteArray = socket.session.peerCertificates.first().encoded
        override val remoteHost: String = socket.inetAddress?.hostAddress ?: ""
        private val output: OutputStream = socket.outputStream

        fun startReading() {
            Thread({ readLoop() }, "diligent-link-read").apply { isDaemon = true }.start()
        }

        private fun readLoop() {
            val target = handler ?: return
            val buffer = ByteArray(BUFFER_BYTES)
            var error: Throwable? = null
            try {
                val input = socket.inputStream
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) target.onBytes(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                if (!socket.isClosed) error = e
            }
            socket.closeQuietly()
            target.onClosed(error)
        }

        override fun send(bytes: ByteArray) {
            synchronized(output) {
                output.write(bytes)
                output.flush()
            }
        }

        override fun close() = socket.closeQuietly()
    }

    /** Presents this device's certificate whichever side it is on. */
    private class OwnKey(private val cert: X509Certificate, private val key: PrivateKey) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
        override fun getPrivateKey(alias: String?): PrivateKey = key
    }

    /**
     * Any certificate when [pinned] is null (pairing; incoming connections),
     * otherwise exactly the pinned one. No CA, no host name: identity is the
     * certificate itself (SPEC A.4).
     */
    // Deliberate (SPEC A.4): peers are self-signed devices, so there is no CA to
    // ask; trust is the pinned certificate fingerprint, checked here, and
    // unpinned handshakes carry only pairing, which SRP and pair.bind protect.
    @SuppressLint("CustomX509TrustManager")
    internal class PinTrust(private val pinned: Fingerprint?) : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            leaf.checkValidity()
            val pin = pinned ?: return
            val presented = Fingerprint.ofCertificate(leaf.encoded, JvmHashing)
            if (presented != pin) throw PinMismatchException(presented)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val PROTOCOL = "TLSv1.3"
        const val CONNECT_TIMEOUT_MS = 5000
        private const val ALIAS = "device"
        private const val BUFFER_BYTES = 64 * 1024
    }
}

private fun java.io.Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
