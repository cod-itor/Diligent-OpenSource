package com.vscodroid.diligent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress

class ShareRulesTest {

    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun `the CGNAT range is 100_64 to 100_127`() {
        assertTrue(TailscaleAddress.inRange(ip("100.64.0.0")))
        assertTrue(TailscaleAddress.inRange(ip("100.101.102.103")))
        assertTrue(TailscaleAddress.inRange(ip("100.127.255.255")))
        assertFalse(TailscaleAddress.inRange(ip("100.63.255.255")))
        assertFalse(TailscaleAddress.inRange(ip("100.128.0.0")))
        assertFalse(TailscaleAddress.inRange(ip("192.168.1.20")))
        assertFalse(TailscaleAddress.inRange(ip("10.0.0.1")))
        assertFalse(TailscaleAddress.inRange(ip("127.0.0.1")))
        assertFalse(TailscaleAddress.inRange(ip("fd7a:115c:a1e0::1")))
    }

    @Test
    fun `only a tun interface counts, so a carrier CGNAT address is never picked`() {
        val wifi = "wlan0" to ip("192.168.1.20")
        val carrier = "rmnet_data0" to ip("100.70.1.2")
        val tailscale = "tun0" to ip("100.101.102.103")
        assertEquals(ip("100.101.102.103"), TailscaleAddress.pick(sequenceOf(wifi, carrier, tailscale)))
        assertNull(TailscaleAddress.pick(sequenceOf(wifi, carrier)))
        assertNull(TailscaleAddress.pick(sequenceOf("tun0" to ip("10.8.0.2"))))
        assertNull(TailscaleAddress.pick(emptySequence()))
    }

    @Test
    fun `the editor URL carries the token, a preview URL does not`() {
        assertEquals("http://100.101.102.103:41234/?tkn=abc-DEF_1", ShareRules.url("100.101.102.103", 41234, "abc-DEF_1"))
        assertEquals("http://100.101.102.103:5173/", ShareRules.url("100.101.102.103", 5173, null))
        assertEquals("http://100.101.102.103:5173/", ShareRules.url("100.101.102.103", 5173, ""))
    }

    @Test
    fun `the token file is read only when the server would accept it`(@TempDir dir: File) {
        val file = File(dir, "token")
        assertNull(ShareRules.readToken(file))
        file.writeText("6f1c2e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6\n")
        assertEquals("6f1c2e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6", ShareRules.readToken(file))
        file.writeText("bad token&x=1")
        assertNull(ShareRules.readToken(file))
        file.writeText("")
        assertNull(ShareRules.readToken(file))
    }

    @Test
    fun `sharing stops after an hour without traffic, and not a moment before`() {
        val hour = ShareRules.IDLE_TIMEOUT_MS
        assertEquals(60L * 60L * 1000L, hour)
        val addr = "100.101.102.103"
        assertNull(ShareRules.check(nowMs = 1_000L, lastTrafficMs = 1_000L, boundAddress = addr, currentAddress = addr))
        assertNull(ShareRules.check(nowMs = hour - 1, lastTrafficMs = 0L, boundAddress = addr, currentAddress = addr))
        assertEquals(ShareStopReason.Idle, ShareRules.check(nowMs = hour, lastTrafficMs = 0L, boundAddress = addr, currentAddress = addr))
        // Traffic resets the clock.
        assertNull(ShareRules.check(nowMs = hour + 10, lastTrafficMs = 10L + 1, boundAddress = addr, currentAddress = addr))
    }

    @Test
    fun `sharing stops when the Tailscale address disappears or changes`() {
        val addr = "100.101.102.103"
        assertEquals(ShareStopReason.TailscaleGone, ShareRules.check(0L, 0L, addr, null))
        assertEquals(ShareStopReason.TailscaleGone, ShareRules.check(0L, 0L, addr, "100.101.102.104"))
    }
}
