package com.vscodroid.diligent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Real sockets on loopback: the proxy is given 127.0.0.1 where production gives it the Tailscale address. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ShareProxyTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val toClose = mutableListOf<Closeable>()

    private fun <T : Closeable> keep(c: T): T = c.also { toClose += it }

    @AfterEach
    fun tearDown() = toClose.reversed().forEach { runCatching { it.close() } }

    private fun target(): ServerSocket = keep(ServerSocket(0, 8, loopback))

    private fun proxyTo(port: Int, clock: () -> Long = { System.nanoTime() / 1_000_000L }): ShareProxy =
        keep(ShareProxy.open(loopback, 0, listOf(InetSocketAddress(loopback, port)), clock))

    private fun dial(proxy: ShareProxy): Socket = keep(Socket(loopback, proxy.port)).apply { soTimeout = 10_000 }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            val read = read(out, at, n - at)
            if (read < 0) throw IOException("EOF after $at of $n bytes")
            at += read
        }
        return out
    }

    private fun InputStream.readToEnd(): ByteArray {
        val out = ByteArrayOutputStream()
        copyTo(out)
        return out.toByteArray()
    }

    @Test
    fun `bytes pass both ways unchanged, a WebSocket upgrade included`() {
        val upstream = target()
        val proxy = proxyTo(upstream.localPort)
        val client = dial(proxy)
        val server = keep(upstream.accept()).apply { soTimeout = 10_000 }

        val upgrade = "GET /?tkn=x HTTP/1.1\r\nHost: 100.101.102.103:4000\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
            .toByteArray()
        client.getOutputStream().write(upgrade)
        assertArrayEquals(upgrade, server.getInputStream().readExactly(upgrade.size))

        val switching = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n".toByteArray()
        val frame = byteArrayOf(0x82.toByte(), 0x03, 0x00, 0xFF.toByte(), 0x7F)
        server.getOutputStream().write(switching + frame)
        assertArrayEquals(switching + frame, client.getInputStream().readExactly(switching.size + frame.size))

        // A large binary payload each way, written while the other side reads.
        val up = Random(1).nextBytes(512 * 1024)
        val down = Random(2).nextBytes(512 * 1024)
        val writer = Thread { client.getOutputStream().write(up) }.apply { start() }
        assertArrayEquals(up, server.getInputStream().readExactly(up.size))
        writer.join()
        val writer2 = Thread { server.getOutputStream().write(down) }.apply { start() }
        assertArrayEquals(down, client.getInputStream().readExactly(down.size))
        writer2.join()
    }

    @Test
    fun `a client that finishes sending still gets the whole answer`() {
        val upstream = target()
        val proxy = proxyTo(upstream.localPort)
        val client = dial(proxy)
        val server = keep(upstream.accept()).apply { soTimeout = 10_000 }

        client.getOutputStream().write("request".toByteArray())
        client.shutdownOutput()
        // The end of the client's stream reaches the server as an end of stream.
        assertArrayEquals("request".toByteArray(), server.getInputStream().readToEnd())
        server.getOutputStream().write("answer".toByteArray())
        server.close()
        // And the server's close reaches the client after the answer.
        assertArrayEquals("answer".toByteArray(), client.getInputStream().readToEnd())
    }

    @Test
    fun `a server that closes closes the client`() {
        val upstream = target()
        val proxy = proxyTo(upstream.localPort)
        val client = dial(proxy)
        val server = keep(upstream.accept())
        server.close()
        assertEquals(-1, client.getInputStream().read())
    }

    @Test
    fun `closing the proxy drops open connections and stops listening`() {
        val upstream = target()
        val proxy = proxyTo(upstream.localPort)
        val client = dial(proxy)
        val server = keep(upstream.accept()).apply { soTimeout = 10_000 }
        client.getOutputStream().write(1)
        assertEquals(1, server.getInputStream().read())
        val port = proxy.port

        proxy.close()
        proxy.close() // twice is fine

        val ended = try {
            client.getInputStream().read() == -1
        } catch (_: IOException) {
            true
        }
        assertTrue(ended)
        assertEquals(-1, server.getInputStream().read())
        assertThrows(IOException::class.java) { Socket(loopback, port).close() }
        assertTrue(proxy.isClosed)
    }

    @Test
    fun `traffic moves the idle clock and nothing else does`() {
        val now = AtomicLong(1_000L)
        val upstream = target()
        val proxy = proxyTo(upstream.localPort) { now.get() }
        assertEquals(1_000L, proxy.lastTrafficMs)
        now.set(5_000L)
        assertEquals(1_000L, proxy.lastTrafficMs)

        val client = dial(proxy)
        val server = keep(upstream.accept()).apply { soTimeout = 10_000 }
        client.getOutputStream().write(7)
        assertEquals(7, server.getInputStream().read())
        // The pump records the time right after it writes.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (proxy.lastTrafficMs != 5_000L && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(5_000L, proxy.lastTrafficMs)
        assertEquals(1, proxy.connections)
    }

    @Test
    fun `a refused target falls through to the next one`() {
        val dead = ServerSocket(0, 1, loopback).let { val p = it.localPort; it.close(); p }
        val upstream = target()
        val proxy = keep(
            ShareProxy.open(
                loopback,
                0,
                listOf(InetSocketAddress(loopback, dead), InetSocketAddress(loopback, upstream.localPort)),
            ),
        )
        val client = dial(proxy)
        val server = keep(upstream.accept()).apply { soTimeout = 10_000 }
        client.getOutputStream().write(42)
        assertEquals(42, server.getInputStream().read())
    }

    @Test
    fun `no target at all closes the client`() {
        val dead = ServerSocket(0, 1, loopback).let { val p = it.localPort; it.close(); p }
        val proxy = proxyTo(dead)
        val client = dial(proxy)
        val ended = try {
            client.getInputStream().read() == -1
        } catch (_: IOException) {
            true
        }
        assertTrue(ended)
    }

    @Test
    fun `the preferred port is used when free, another when taken`() {
        val upstream = target()
        val free = ServerSocket(0, 1, loopback).let { val p = it.localPort; it.close(); p }
        val onPreferred = keep(ShareProxy.open(loopback, free, listOf(InetSocketAddress(loopback, upstream.localPort))))
        assertEquals(free, onPreferred.port)
        val taken = keep(ShareProxy.open(loopback, free, listOf(InetSocketAddress(loopback, upstream.localPort))))
        assertNotEquals(free, taken.port)
    }

    @Test
    fun `the wildcard address is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareProxy.open(InetAddress.getByName("0.0.0.0"), 0, listOf(InetSocketAddress(loopback, 1)))
        }
    }
}
