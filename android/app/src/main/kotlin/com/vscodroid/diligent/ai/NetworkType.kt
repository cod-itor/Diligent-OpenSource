package com.vscodroid.diligent.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * What kind of network the phone is on, for the AI suggestions' "Suggestions
 * only on Wi-Fi" setting (`diligentNetworkType`): `wifi`, `cellular` or
 * `none`.
 *
 * Errs towards `cellular`: the setting exists so that code is never sent on
 * mobile data by surprise, so a network this cannot place (a VPN alone, a
 * Bluetooth tether) only counts as Wi-Fi when Android says it is not metered.
 */
object NetworkType {
    const val WIFI = "wifi"
    const val CELLULAR = "cellular"
    const val NONE = "none"

    /**
     * The pure decision. [connected] is an active network that reaches the
     * internet; the transports are the ones its capabilities name.
     */
    fun classify(
        connected: Boolean,
        wifi: Boolean,
        ethernet: Boolean,
        cellular: Boolean,
        notMetered: Boolean,
    ): String = when {
        !connected -> NONE
        // A VPN over mobile data names both; mobile data decides.
        cellular -> CELLULAR
        wifi || ethernet -> WIFI
        notMetered -> WIFI
        else -> CELLULAR
    }

    /** The active network now. Any failure to read it answers `none`. */
    fun current(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return NONE
        val caps = try {
            manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        } catch (_: SecurityException) {
            null
        } ?: return NONE
        return classify(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
}
