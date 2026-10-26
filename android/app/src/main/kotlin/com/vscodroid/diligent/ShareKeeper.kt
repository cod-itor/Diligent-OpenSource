package com.vscodroid.diligent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vscodroid.MainActivity
import com.vscodroid.R
import com.vscodroid.util.Environment
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One share that is on: what it opens and the address another device uses. */
data class ActiveShare(val kind: ShareKind, val url: String, val address: String, val port: Int, val targetPort: Int)

/** How a tap on Share editor or Share preview ended. */
enum class ShareStart { Started, NoTailscale, NoToken, Failed }

/**
 * Sharing the editor or a preview with the user's other Tailscale devices.
 *
 * Off by default and in memory only: nothing is saved, so a restart or a boot
 * never turns it on. While anything is shared a "Sharing on" notification
 * with Stop is posted. Every [ShareRules.CHECK_EVERY_MS] each share is checked
 * and stopped after [ShareRules.IDLE_TIMEOUT_MS] without traffic, or as soon
 * as the Tailscale address it listens on is gone.
 *
 * One per process, like [TaskKeeper]. Sockets are opened and closed on a
 * worker thread; the Compose state changes on the main thread only.
 */
object ShareKeeper {
    /** Notification Stop: handled by [DiligentController.onIntent]. */
    const val ACTION_STOP_SHARING = "com.vscodroid.diligent.action.STOP_SHARING"

    /** Notification tap: open the Share screen. */
    const val ACTION_OPEN_SHARE = "com.vscodroid.diligent.action.OPEN_SHARE"

    private const val CHANNEL_ID = "diligent_share"
    private const val NOTIFICATION_ID = 2300
    private const val REQUEST_STOP = 2301
    private const val REQUEST_OPEN = 2302

    /** What is shared now, by kind. */
    var shares by mutableStateOf<Map<ShareKind, ActiveShare>>(emptyMap())
        private set

    /** Why the last share stopped by itself, for the screen to say; null after a user's Stop or a new start. */
    var lastStop by mutableStateOf<ShareStopReason?>(null)
        private set

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "diligent-share").apply { isDaemon = true }
    }

    // Worker thread only.
    private val proxies = HashMap<ShareKind, Pair<ShareProxy, ActiveShare>>()
    private var checking: ScheduledFuture<*>? = null

    private val clock: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * Shares [kind], forwarding to `127.0.0.1:<targetPort>`. The editor's
     * address carries the connection token. [onResult] runs on the main thread.
     */
    fun start(context: Context, kind: ShareKind, targetPort: Int, onResult: (ShareStart) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = startNow(app, kind, targetPort)
            val now = snapshot()
            main.post {
                lastStop = null
                publish(app, now)
                onResult(result)
            }
        }
    }

    private fun startNow(app: Context, kind: ShareKind, targetPort: Int): ShareStart {
        val address = TailscaleAddress.find() ?: return ShareStart.NoTailscale
        val token = if (kind == ShareKind.Editor) {
            ShareRules.readToken(File(Environment.getConnectionTokenPath(app))) ?: return ShareStart.NoToken
        } else {
            null
        }
        proxies.remove(kind)?.first?.close()
        val proxy = try {
            ShareProxy.open(
                bindAddress = address,
                preferredPort = targetPort,
                targets = listOf(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort),
                    InetSocketAddress(InetAddress.getByName("::1"), targetPort),
                ),
                clock = clock,
            )
        } catch (_: IOException) {
            return ShareStart.Failed
        }
        val host = address.hostAddress ?: return ShareStart.Failed.also { proxy.close() }
        proxies[kind] = proxy to ActiveShare(kind, ShareRules.url(host, proxy.port, token), host, proxy.port, targetPort)
        if (checking == null) {
            checking = worker.scheduleWithFixedDelay(
                { check(app) },
                ShareRules.CHECK_EVERY_MS,
                ShareRules.CHECK_EVERY_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        return ShareStart.Started
    }

    /** Stops [kind], or every share when null. */
    fun stop(context: Context, kind: ShareKind? = null) {
        val app = context.applicationContext
        worker.execute {
            stopNow(kind)
            val now = snapshot()
            main.post {
                lastStop = null
                publish(app, now)
            }
        }
    }

    private fun stopNow(kind: ShareKind?) {
        val stopping = if (kind == null) proxies.keys.toList() else listOf(kind)
        stopping.forEach { proxies.remove(it)?.first?.close() }
        if (proxies.isEmpty()) {
            checking?.cancel(false)
            checking = null
        }
    }

    private fun check(app: Context) {
        val current = TailscaleAddress.find()?.hostAddress
        val now = clock()
        var reason: ShareStopReason? = null
        for ((kind, entry) in proxies.toList()) {
            val (proxy, share) = entry
            val why = if (proxy.isClosed) ShareStopReason.Failed
            else ShareRules.check(now, proxy.lastTrafficMs, share.address, current)
            if (why != null) {
                stopNow(kind)
                reason = why
            }
        }
        val stopped = reason ?: return
        val left = snapshot()
        main.post {
            lastStop = stopped
            publish(app, left)
        }
    }

    /** The worker's shares as they are now. Worker thread. */
    private fun snapshot(): Map<ShareKind, ActiveShare> = proxies.mapValues { it.value.second }

    /** Shows [now] on the screen and posts or removes the notification to match. Main thread. */
    private fun publish(app: Context, now: Map<ShareKind, ActiveShare>) {
        shares = now
        if (shares.isEmpty()) {
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } else {
            postNotification(app, shares.values)
        }
    }

    /**
     * Removes a "Sharing on" notification left by a process that has since
     * died: sharing never survives the process, so with nothing shared here
     * the notification is stale.
     */
    fun forgetStale(context: Context) {
        if (shares.isEmpty()) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    /** Whether [intent] is the notification's Stop. */
    fun isStop(intent: Intent?): Boolean = intent?.action == ACTION_STOP_SHARING

    /** Whether [intent] is a tap on the notification. */
    fun isOpen(intent: Intent?): Boolean = intent?.action == ACTION_OPEN_SHARE

    private fun postNotification(context: Context, active: Collection<ActiveShare>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.diligent_share_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.diligent_share_channel_description) },
        )
        val text = active.joinToString("\n") { share ->
            context.getString(
                if (share.kind == ShareKind.Editor) R.string.diligent_share_notification_editor
                else R.string.diligent_share_notification_preview,
                share.address,
                share.port,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.diligent_share_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityIntent(context, ACTION_OPEN_SHARE, REQUEST_OPEN))
            .addAction(0, context.getString(R.string.diligent_share_stop), activityIntent(context, ACTION_STOP_SHARING, REQUEST_STOP))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * An activity rather than a broadcast, as [TaskKeeper]'s Stop: it still
     * works, and clears the notification, after the process that posted it
     * has died.
     */
    private fun activityIntent(context: Context, action: String, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
