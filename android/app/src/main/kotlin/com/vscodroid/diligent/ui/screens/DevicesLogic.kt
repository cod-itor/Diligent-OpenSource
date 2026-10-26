package com.vscodroid.diligent.ui.screens

import androidx.annotation.StringRes
import com.vscodroid.R
import com.vscodroid.diligent.devices.DevicesState
import com.vscodroid.diligent.devices.LinkRoute
import com.vscodroid.diligent.devices.PairedDevice
import com.vscodroid.diligent.projects.RelativeTime

/*
 * The parts of the Devices, pairing and Sync screens that are plain rules,
 * kept out of the composables so they are tested without a device.
 */

/** diligent-link's default port (Appendix A.2), filled in for the user. */
const val DEFAULT_LINK_PORT = 47321

/** Why the "Add by IP address" form cannot connect yet. */
enum class AddressProblem { EmptyHost, BadHost, BadPort, BadDeviceId }

/** A checked address, ready for [com.vscodroid.diligent.devices.DeviceLink.connectAddress]. */
data class LinkAddress(val host: String, val port: Int, val deviceId: String?)

/** The rules of the "Add by IP address" form, and the QR code text (`diligent://<ip>:<port>/<device ID>`). */
object AddressForm {
    private const val QR_PREFIX = "diligent://"
    private const val DEVICE_ID_LENGTH = 32
    private const val MAX_PORT = 65535
    private val hostChars = Regex("^[A-Za-z0-9.:%-]+$")
    private val deviceIdChars = Regex("^[A-Z2-7]+$")

    /** The first thing wrong with the form, or null when it can connect. */
    fun problem(host: String, port: String, deviceId: String): AddressProblem? = when {
        host.isBlank() -> AddressProblem.EmptyHost
        cleanHost(host) == null -> AddressProblem.BadHost
        port(port) == null -> AddressProblem.BadPort
        deviceId.isNotBlank() && deviceId(deviceId) == null -> AddressProblem.BadDeviceId
        else -> null
    }

    /** The form as an address, or null while [problem] has something to say. */
    fun parse(host: String, port: String, deviceId: String): LinkAddress? {
        if (problem(host, port, deviceId) != null) return null
        return LinkAddress(cleanHost(host)!!, port(port)!!, deviceId.takeIf { it.isNotBlank() }?.let(::deviceId))
    }

    /** A pasted or scanned `diligent://` code as an address, or null when it is not one. */
    fun fromQr(text: String): LinkAddress? {
        val rest = text.trim().takeIf { it.startsWith(QR_PREFIX, ignoreCase = true) }?.substring(QR_PREFIX.length) ?: return null
        val slash = rest.indexOf('/')
        val hostPort = if (slash < 0) rest else rest.substring(0, slash)
        val id = if (slash < 0) "" else rest.substring(slash + 1).trimEnd('/')
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return null
        return parse(hostPort.substring(0, colon), hostPort.substring(colon + 1), id)
    }

    /** A host name or IP address without spaces or a scheme; IPv6 may come in brackets. */
    private fun cleanHost(raw: String): String? {
        val host = raw.trim().removePrefix("[").removeSuffix("]")
        return host.takeIf { it.isNotEmpty() && hostChars.matches(it) && !it.startsWith('-') && !it.startsWith('.') }
    }

    private fun port(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..MAX_PORT }

    /**
     * A device ID as Appendix A.1 makes it: 32 base32 characters. The user
     * may type it in the groups of 4 it is shown in, in any case; the
     * answer is the 32 characters without the dashes.
     */
    fun deviceId(raw: String): String? {
        val id = raw.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        return id.takeIf { it.length == DEVICE_ID_LENGTH && deviceIdChars.matches(it) }
    }
}

@StringRes
fun AddressProblem.messageRes(): Int = when (this) {
    AddressProblem.EmptyHost -> R.string.diligent_devices_add_host_empty
    AddressProblem.BadHost -> R.string.diligent_devices_add_host_bad
    AddressProblem.BadPort -> R.string.diligent_devices_add_port_bad
    AddressProblem.BadDeviceId -> R.string.diligent_devices_add_id_bad
}

/** "Online, Wi-Fi", or "Offline" when there is no route. */
@StringRes
fun statusRes(route: LinkRoute?): Int = when (route) {
    LinkRoute.Wifi -> R.string.diligent_devices_online_wifi
    LinkRoute.Tailscale -> R.string.diligent_devices_online_tailscale
    LinkRoute.PhoneToPhone -> R.string.diligent_devices_online_phone
    null -> R.string.diligent_devices_offline
}

/** "Connected via Wi-Fi", for the sync plan. */
@StringRes
fun viaRes(route: LinkRoute): Int = when (route) {
    LinkRoute.Wifi -> R.string.diligent_devices_via_wifi
    LinkRoute.Tailscale -> R.string.diligent_devices_via_tailscale
    LinkRoute.PhoneToPhone -> R.string.diligent_devices_via_phone
}

/** How long ago the last sync was, with the Projects tab's words, or null when it never synced. */
fun lastSyncAgo(lastSyncMs: Long?, now: Long): RelativeTime? = lastSyncMs?.let { RelativeTime.between(it, now) }

/** Whether Sync can start with [device] now: reachable and not blocked by an identity change. */
fun canSync(device: PairedDevice): Boolean = device.route != null && !device.identityChanged

/** The paired devices Sync can pick, in the list's order. */
fun syncTargets(state: DevicesState): List<PairedDevice> = state.myDevices.filter(::canSync)
