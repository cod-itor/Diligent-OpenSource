package com.vscodroid.diligent.devices

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.vscodroid.R
import com.vscodroid.diligent.TailscaleAddress
import com.vscodroid.diligent.TaskKeeper
import com.vscodroid.diligent.process.BundledShell
import com.vscodroid.diligent.snapshot.SnapshotResult
import com.vscodroid.diligent.snapshot.SnapshotRunner
import com.vscodroid.util.Environment
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * The process's one [DeviceLinkService]. One per process rather than per
 * activity, because "Stay reachable in the background" keeps it listening
 * after the activity is gone, for as long as the editor's foreground service
 * keeps the process.
 */
object DeviceLinks {
    @Volatile private var instance: DeviceLinkService? = null

    /** Saves every open editor; set by the activity that holds the editor page, null without one. */
    @Volatile var saveAll: (() -> Unit)? = null

    /** Whether the notification says this device is reachable in the background. Read by [TaskKeeper.decorate]. */
    @Volatile var reachableInBackground: Boolean = false
        private set

    fun of(context: Context): DeviceLinkService = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(app: Context): DeviceLinkService {
        val storage = LinkStorage(File(app.filesDir, "diligent-link"))
        val identity = DeviceIdentity.loadOrCreate(storage.identityDir, AndroidKeystoreWrapper())
        val discovery = NsdDiscovery(app)
        val io = Executors.newCachedThreadPool { r -> Thread(r, "diligent-link-io").apply { isDaemon = true } }
        return DeviceLinkService(
            identity = identity,
            transport = TlsTransport(identity),
            discovery = discovery,
            srp = BcSrp(),
            host = AndroidLinkHost(app),
            storage = storage,
            loop = ThreadLinkLoop(),
            io = io,
            onListening = { discovery.port = it },
        )
    }

    private class AndroidLinkHost(private val context: Context) : LinkHost {
        private val main = Handler(Looper.getMainLooper())

        override val deviceName: String =
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

        override val projectsDir: File get() = File(Environment.getProjectsDir(context))

        override fun saveAll() {
            main.post { saveAll?.invoke() }
        }

        override fun snapshot(projectDir: File, label: String): String {
            val result = runBlocking { SnapshotRunner(BundledShell(context)).take(projectDir, label, initIfNeeded = true) }
            return when (result) {
                is SnapshotResult.Taken -> result.commit
                SnapshotResult.NotARepository -> throw IOException("not a repository")
                is SnapshotResult.Failed -> throw IOException(result.lines.lastOrNull() ?: "snapshot failed")
            }
        }

        override fun ownTailscaleAddress(): String? = TailscaleAddress.find()?.hostAddress

        override fun ownLanAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !TailscaleAddress.inRange(it) }
                ?.hostAddress
        } catch (e: SocketException) {
            null
        }

        override fun text(problem: LinkProblem, name: String): String = when (problem) {
            LinkProblem.NotOnNetwork -> context.getString(R.string.diligent_link_not_on_network, name)
            LinkProblem.NotFound -> context.getString(R.string.diligent_link_not_found, name)
            LinkProblem.Unreachable -> context.getString(R.string.diligent_link_unreachable, name)
            LinkProblem.NoTailscaleHere -> context.getString(R.string.diligent_link_no_tailscale_here)
            LinkProblem.NoTailscaleAddress -> context.getString(R.string.diligent_link_no_tailscale_address, name)
            LinkProblem.PhoneToPhoneUnavailable -> context.getString(R.string.diligent_link_phone_to_phone_unavailable)
            LinkProblem.IdentityChanged -> context.getString(R.string.diligent_link_identity_changed)
            LinkProblem.NotAccepting -> context.getString(R.string.diligent_link_not_accepting, name)
            LinkProblem.Refused -> context.getString(R.string.diligent_link_refused, name)
            LinkProblem.WrongDevice -> context.getString(R.string.diligent_link_wrong_device, name)
            LinkProblem.ConnectionLost -> context.getString(R.string.diligent_link_connection_lost, name)
            LinkProblem.Declined -> context.getString(R.string.diligent_link_declined, name)
            LinkProblem.Busy -> context.getString(R.string.diligent_link_busy)
            LinkProblem.SnapshotFailed -> context.getString(R.string.diligent_link_snapshot_failed)
            LinkProblem.SyncFailed -> context.getString(R.string.diligent_link_sync_failed, name)
            LinkProblem.NotPaired -> context.getString(R.string.diligent_link_not_paired, name)
            LinkProblem.NotAProject -> context.getString(R.string.diligent_link_not_a_project)
        }

        override fun reachableInBackground(on: Boolean) {
            if (reachableInBackground == on) return
            reachableInBackground = on
            main.post { TaskKeeper.refresh() }
        }
    }
}
