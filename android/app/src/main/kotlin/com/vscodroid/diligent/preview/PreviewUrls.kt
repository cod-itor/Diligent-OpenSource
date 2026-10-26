package com.vscodroid.diligent.preview

import java.net.URI
import java.net.URISyntaxException

/**
 * Which addresses the preview shows itself and which it hands to the browser.
 *
 * The preview is for the user's own dev server on this phone, so it loads
 * loopback addresses only. A link out of the app (documentation, a sign-in
 * page, anything on the internet) opens in the browser, where the user has
 * their address bar, their passwords and a padlock to look at.
 */
object PreviewUrls {
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

    fun forPort(port: Int): String = "http://localhost:$port/"

    /** Whether [url] is an http(s) address on this device, which the preview may load itself. */
    fun isLocal(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host in LOOPBACK_HOSTS || host.endsWith(".localhost")
    }

    /** `localhost:3000`, for the title bar. */
    fun label(port: Int): String = "localhost:$port"
}
