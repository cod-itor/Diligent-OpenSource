package com.vscodroid.diligent

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A plain TCP forwarder from one listening address to a server on loopback.
 *
 * Raw bytes both ways, never parsed as HTTP, so a WebSocket upgrade (the
 * editor's whole connection) passes through untouched, and the Host and
 * Origin the other device sends arrive exactly as it sent them.
 *
 * [bindAddress] is chosen by the caller: [ShareKeeper] passes only the
 * Tailscale address, the tests pass 127.0.0.1. The wildcard address is
 * refused here so no caller can open the editor to every network at once.
 *
 * [targets] are tried in order for each connection, so a dev server that
 * listens only on `::1` is still reached after `127.0.0.1` refuses.
 *
 * A side that ends its stream half-closes the other (shutdownOutput), so a
 * request-then-close client still gets its answer; an error on either side
 * closes both.
 */
class ShareProxy private constructor(
    private val server: ServerSocket,
    private val targets: List<InetSocketAddress>,
    private val clock: () -> Long,
) : Closeable {

    /** The port this proxy listens on. */
    val port: Int get() = server.localPort

    private val lastTraffic = AtomicLong(clock())
    private val open = mutableSetOf<Socket>()
    private val count = AtomicInteger(0)

    @Volatile
    private var closed = false

    /** When bytes last moved either way, or the proxy started, on [clock]. */
    val lastTrafficMs: Long get() = lastTraffic.get()

    /** Connections open right now. */
    val connections: Int get() = count.get()

    init {
        thread(name = "diligent-share-accept", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            if (count.get() >= MAX_CONNECTIONS) {
                closeQuietly(client)
                continue
            }
            thread(name = "diligent-share-connect", isDaemon = true) { connect(client) }
        }
        close()
    }

    private fun connect(client: Socket) {
        val upstream = targets.firstNotNullOfOrNull { target ->
            val socket = Socket()
            try {
                socket.connect(target, CONNECT_TIMEOUT_MS)
                socket
            } catch (_: IOException) {
                closeQuietly(socket)
                null
            }
        }
        if (upstream == null || !track(client, upstream)) {
            closeQuietly(client)
            upstream?.let(::closeQuietly)
            return
        }
        touch()
        runCatching { client.tcpNoDelay = true; upstream.tcpNoDelay = true }
        val finished = AtomicInteger(0)
        val done = {
            if (finished.incrementAndGet() == 2) release(client, upstream)
        }
        thread(name = "diligent-share-up", isDaemon = true) { pump(client, upstream, done) }
        thread(name = "diligent-share-down", isDaemon = true) { pump(upstream, client, done) }
    }

    private fun pump(from: Socket, to: Socket, done: () -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
                touch()
            }
            // The end of this direction: pass it on, keep the other one open.
            runCatching { to.shutdownOutput() }
            done()
        } catch (_: IOException) {
            // A reset or a socket closed under us: nothing more goes either way.
            release(from, to)
            done()
        }
    }

    private fun touch() = lastTraffic.set(clock())

    @Synchronized
    private fun track(a: Socket, b: Socket): Boolean {
        if (closed) return false
        open += a
        open += b
        count.incrementAndGet()
        return true
    }

    @Synchronized
    private fun release(a: Socket, b: Socket) {
        val wasOpen = open.remove(a) or open.remove(b)
        closeQuietly(a)
        closeQuietly(b)
        if (wasOpen) count.decrementAndGet()
    }

    /** Stops listening and drops every connection. Safe to call more than once. */
    override fun close() {
        val toClose: List<Socket>
        synchronized(this) {
            if (closed) return
            closed = true
            toClose = open.toList()
            open.clear()
            count.set(0)
        }
        closeQuietly(server)
        toClose.forEach(::closeQuietly)
    }

    val isClosed: Boolean get() = closed

    /** Whether the listening socket is still open; false once [close] has run. */
    internal val isListening: Boolean get() = !server.isClosed

    companion object {
        const val MAX_CONNECTIONS = 64
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val BUFFER_BYTES = 16 * 1024

        /**
         * Listens on [bindAddress], on [preferredPort] when it is free there,
         * else on any free port, and forwards each connection to the first of
         * [targets] that accepts it.
         *
         * @throws IOException when nothing can be bound.
         * @throws IllegalArgumentException for the wildcard address.
         */
        fun open(
            bindAddress: InetAddress,
            preferredPort: Int,
            targets: List<InetSocketAddress>,
            clock: () -> Long = { System.nanoTime() / 1_000_000L },
        ): ShareProxy {
            require(!bindAddress.isAnyLocalAddress) { "refusing to listen on every address" }
            require(targets.isNotEmpty())
            val server = bindFirst(bindAddress, listOf(preferredPort, 0).filter { it in 0..65535 }.distinct())
            return ShareProxy(server, targets, clock)
        }

        private fun bindFirst(address: InetAddress, ports: List<Int>): ServerSocket {
            var last: IOException? = null
            for (port in ports) {
                val server = ServerSocket()
                try {
                    // No SO_REUSEADDR: the editor's own port is taken on loopback
                    // and must stay that way; this binds a different address.
                    server.bind(InetSocketAddress(address, port), BACKLOG)
                    return server
                } catch (e: IOException) {
                    closeQuietly(server)
                    last = e
                }
            }
            throw last ?: IOException("no port to bind")
        }

        private const val BACKLOG = 16

        private fun closeQuietly(c: Closeable) {
            try {
                c.close()
            } catch (_: IOException) {
                // Already gone.
            }
        }
    }
}
