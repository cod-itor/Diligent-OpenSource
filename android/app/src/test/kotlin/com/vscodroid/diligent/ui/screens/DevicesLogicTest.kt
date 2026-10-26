package com.vscodroid.diligent.ui.screens

import com.vscodroid.R
import com.vscodroid.diligent.devices.Approval
import com.vscodroid.diligent.devices.DevicesState
import com.vscodroid.diligent.devices.LinkRoute
import com.vscodroid.diligent.devices.PairedDevice
import com.vscodroid.diligent.projects.RelativeTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DevicesLogicTest {

    private val id = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    @Test
    fun `a host and the default port connect`() {
        assertNull(AddressForm.problem("192.168.1.20", DEFAULT_LINK_PORT.toString(), ""))
        assertEquals(LinkAddress("192.168.1.20", 47321, null), AddressForm.parse(" 192.168.1.20 ", "47321", " "))
    }

    @Test
    fun `host names and IPv6 addresses in brackets are fine`() {
        assertEquals("totos-laptop.local", AddressForm.parse("totos-laptop.local", "47321", "")?.host)
        assertEquals("fe80::1%wlan0", AddressForm.parse("[fe80::1%wlan0]", "47321", "")?.host)
    }

    @Test
    fun `the form says what is wrong first`() {
        assertEquals(AddressProblem.EmptyHost, AddressForm.problem(" ", "x", "x"))
        assertEquals(AddressProblem.BadHost, AddressForm.problem("http://192.168.1.20", "47321", ""))
        assertEquals(AddressProblem.BadHost, AddressForm.problem("my laptop", "47321", ""))
        assertEquals(AddressProblem.BadPort, AddressForm.problem("10.0.0.2", "", ""))
        assertEquals(AddressProblem.BadPort, AddressForm.problem("10.0.0.2", "0", ""))
        assertEquals(AddressProblem.BadPort, AddressForm.problem("10.0.0.2", "65536", ""))
        assertEquals(AddressProblem.BadDeviceId, AddressForm.problem("10.0.0.2", "47321", "ABCD-1234"))
        assertNull(AddressForm.parse("10.0.0.2", "47321", "ABCD-1234"))
    }

    @Test
    fun `a device ID may be typed in groups, in any case`() {
        assertEquals(id, AddressForm.deviceId("abcd-efgh-ijkl-mnop-qrst-uvwx-yz23-4567"))
        assertEquals(id, AddressForm.parse("10.0.0.2", "47321", "ABCD EFGH IJKL MNOP QRST UVWX YZ23 4567")?.deviceId)
        // 0, 1, 8 and 9 are not base32.
        assertNull(AddressForm.deviceId("ABCDEFGHIJKLMNOPQRSTUVWXYZ234568"))
        assertNull(AddressForm.deviceId(id.dropLast(1)))
    }

    @Test
    fun `a diligent code fills the form`() {
        assertEquals(LinkAddress("192.168.1.20", 47322, id), AddressForm.fromQr("diligent://192.168.1.20:47322/$id"))
        assertEquals(LinkAddress("10.0.0.2", 47321, null), AddressForm.fromQr("DILIGENT://10.0.0.2:47321"))
        assertNull(AddressForm.fromQr("192.168.1.20"))
        assertNull(AddressForm.fromQr("diligent://192.168.1.20/$id"))
    }

    @Test
    fun `each route has its own words, and no route is offline`() {
        val labels = LinkRoute.entries.map(::statusRes) + statusRes(null)
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(R.string.diligent_devices_offline, statusRes(null))
        assertEquals(LinkRoute.entries.size, LinkRoute.entries.map(::viaRes).toSet().size)
    }

    @Test
    fun `last sync reuses the projects clock, and never synced is null`() {
        assertNull(lastSyncAgo(null, 1_000_000L))
        assertEquals(RelativeTime.Minutes(5), lastSyncAgo(0L, 5 * 60_000L))
    }

    @Test
    fun `sync targets are the reachable devices that are not blocked`() {
        val online = PairedDevice("A", "Laptop", LinkRoute.Wifi, null, Approval.AlwaysAllow)
        val offline = online.copy(deviceId = "B", route = null)
        val changed = online.copy(deviceId = "C", identityChanged = true)
        val tail = online.copy(deviceId = "D", route = LinkRoute.Tailscale)
        assertEquals(listOf(online, tail), syncTargets(DevicesState(myDevices = listOf(online, offline, changed, tail))))
    }
}

class PairingLogicTest {

    @Test
    fun `the code field keeps 6 digits`() {
        assertEquals("482913", cleanCode("482 913"))
        assertEquals("123456", cleanCode("12-34-56-78"))
        assertEquals("", cleanCode("abc"))
    }

    @Test
    fun `the shown code is grouped in threes`() {
        assertEquals("482 913", groupCode("482913"))
    }

    @Test
    fun `the countdown rounds up and stops at zero`() {
        assertEquals(120, secondsLeft(120_000L, 0L))
        assertEquals(1, secondsLeft(1_000L, 1L))
        assertEquals(0, secondsLeft(1_000L, 1_000L))
        assertEquals(0, secondsLeft(1_000L, 5_000L))
        assertEquals("2:00", countdownText(120))
        assertEquals("1:05", countdownText(65))
        assertEquals("0:09", countdownText(9))
    }
}
