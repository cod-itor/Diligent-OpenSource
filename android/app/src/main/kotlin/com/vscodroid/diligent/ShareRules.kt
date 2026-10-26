package com.vscodroid.diligent

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * The phone's Tailscale address, the only address sharing ever listens on.
 *
 * Tailscale gives each device an IPv4 address in the carrier-grade NAT range
 * 100.64.0.0/10. That range alone is not enough: some mobile carriers hand out
 * addresses from it too, and listening there would open the editor to the
 * carrier's network. So the address must also sit on a VPN interface, which on
 * Android is always a `tun` device (VpnService creates `tun0`, `tun1`, ...).
 */
object TailscaleAddress {

    /** Whether [address] is IPv4 in 100.64.0.0/10. */
    fun inRange(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        return (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
    }

    /** The first address in range on a `tun` interface, from (interface name, address) pairs. */
    fun pick(candidates: Sequence<Pair<String, InetAddress>>): Inet4Address? =
        candidates.firstOrNull { (name, address) -> name.startsWith("tun") && inRange(address) }
            ?.second as Inet4Address?

    /** The phone's Tailscale address now, or null when Tailscale is off or not installed. */
    fun find(): Inet4Address? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        pick(
            interfaces.asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .flatMap { nic -> nic.inetAddresses.toList().asSequence().map { nic.name to it } },
        )
    } catch (_: SocketException) {
        null
    }
}

/** What a share opens on the other device. */
enum class ShareKind { Editor, Preview }

/** Why sharing stopped, for the Share screen to say. */
enum class ShareStopReason { User, Idle, TailscaleGone, Failed }

object ShareRules {
    /** Sharing turns itself off after this long with no bytes either way. */
    const val IDLE_TIMEOUT_MS: Long = 60L * 60L * 1000L

    /** How often the keeper checks for idleness and a vanished address. */
    const val CHECK_EVERY_MS: Long = 30L * 1000L

    private val TOKEN = Regex("[0-9A-Za-z_-]+")

    /**
     * The address another device opens. The editor's carries the connection
     * token, which the server turns into a cookie on the first load; a
     * preview is the user's own dev server and gets no token.
     */
    fun url(host: String, port: Int, token: String?): String {
        val base = "http://$host:$port/"
        return if (token.isNullOrEmpty()) base else "$base?tkn=$token"
    }

    /**
     * The token from `<server-data-dir>/data/token`, or null when the file is
     * missing or holds something the server itself would not accept.
     */
    fun readToken(file: File): String? =
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { TOKEN.matches(it) }

    /**
     * Whether a share bound to [boundAddress] must stop now: the Tailscale
     * address is gone or changed, or no traffic since [lastTrafficMs] for
     * [timeoutMs]. Null means keep sharing.
     */
    fun check(
        nowMs: Long,
        lastTrafficMs: Long,
        boundAddress: String,
        currentAddress: String?,
        timeoutMs: Long = IDLE_TIMEOUT_MS,
    ): ShareStopReason? = when {
        currentAddress != boundAddress -> ShareStopReason.TailscaleGone
        nowMs - lastTrafficMs >= timeoutMs -> ShareStopReason.Idle
        else -> null
    }
}
