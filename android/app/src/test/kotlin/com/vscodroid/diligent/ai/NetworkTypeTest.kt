package com.vscodroid.diligent.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NetworkTypeTest {

    private fun classify(
        connected: Boolean = true,
        wifi: Boolean = false,
        ethernet: Boolean = false,
        cellular: Boolean = false,
        notMetered: Boolean = false,
    ) = NetworkType.classify(connected, wifi, ethernet, cellular, notMetered)

    @Test
    fun `no network or no internet is none`() {
        assertEquals("none", classify(connected = false))
        assertEquals("none", classify(connected = false, wifi = true, notMetered = true))
    }

    @Test
    fun `wifi and ethernet are wifi`() {
        assertEquals("wifi", classify(wifi = true, notMetered = true))
        // A metered hotspot is still Wi-Fi: the user chose that network.
        assertEquals("wifi", classify(wifi = true))
        assertEquals("wifi", classify(ethernet = true))
    }

    @Test
    fun `mobile data is cellular, also under a VPN`() {
        assertEquals("cellular", classify(cellular = true))
        assertEquals("cellular", classify(cellular = true, wifi = true))
    }

    @Test
    fun `an unknown transport counts as wifi only when not metered`() {
        assertEquals("wifi", classify(notMetered = true))
        assertEquals("cellular", classify())
    }
}
