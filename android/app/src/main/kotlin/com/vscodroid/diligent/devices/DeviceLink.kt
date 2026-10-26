package com.vscodroid.diligent.devices

import kotlinx.coroutines.flow.StateFlow

/**
 * What the Devices screen and Sync may ask of diligent-link, and what they
 * can show. The screens see only this: plain Kotlin values, no sync-core
 * types, no sockets. [DeviceLinkService] implements it over sync-core and
 * the Android adapters (Identity, Discovery, Transport, Srp); previews and
 * tests use [PreviewDeviceLink].
 *
 * Every call returns at once and is safe on the main thread; results arrive
 * as a new [state]. Nothing here syncs on its own: every transfer starts from
 * [planSync] then [confirmSync], or from automatic sync the user turned on
 * for that project and device.
 */
interface DeviceLink {
    val state: StateFlow<DevicesState>

    /** The Devices screen is on screen or not; drives [Visibility.WhileScreenOpen]. */
    fun screenOpen(open: Boolean)
    fun setVisibility(visibility: Visibility)
    fun setStayReachable(on: Boolean)
    fun setConnectionMode(mode: ConnectionMode)

    /** Pairing, as the requester: Connect on a nearby device, or a typed or scanned address. */
    fun connect(nearbyDeviceId: String)
    fun connectAddress(host: String, port: Int, deviceId: String?)
    fun submitCode(code: String)
    fun cancelPairing()

    /** Text for the "Add by QR code" code: this device's address, port and device ID. Null when not listening. */
    fun pairingQr(): String?

    fun rename(deviceId: String, name: String)
    fun forget(deviceId: String)
    fun setApproval(deviceId: String, approval: Approval)

    /** Sync: saves open files, builds both manifests, and shows the plan in [DevicesState.sync]. */
    fun planSync(deviceId: String, projectPath: String)
    fun confirmSync()
    fun resolveConflict(path: String, choice: ConflictChoice)
    fun cancelSync()
    fun setAutoSync(projectPath: String, deviceId: String, on: Boolean)

    /** The answer to [DevicesState.incomingProject]. */
    fun answerIncomingProject(accept: Boolean)

    /** Dismisses a finished pairing or sync result. */
    fun dismiss()
}

enum class Visibility { On, Off, WhileScreenOpen }

enum class ConnectionMode { Automatic, WifiOrHotspot, TailscaleOnly, PhoneToPhone }

enum class Approval { AlwaysAllow, AskEachTime }

enum class ConflictChoice { KeepMine, KeepTheirs, KeepBoth }

/** How a device is reached right now. */
enum class LinkRoute { Wifi, Tailscale, PhoneToPhone }

data class DevicesState(
    val myName: String = "",
    val myDeviceId: String = "",
    val visibility: Visibility = Visibility.WhileScreenOpen,
    val stayReachable: Boolean = false,
    val connectionMode: ConnectionMode = ConnectionMode.Automatic,
    val myDevices: List<PairedDevice> = emptyList(),
    val nearby: List<NearbyDevice> = emptyList(),
    val pairing: PairingUi? = null,
    val sync: SyncUi? = null,
    val incomingProject: IncomingProject? = null,
)

data class PairedDevice(
    val deviceId: String,
    val name: String,
    /** Null when offline. */
    val route: LinkRoute?,
    val lastSyncMs: Long?,
    val approval: Approval,
    /** Its certificate no longer matches the pinned one: blocked until paired again. */
    val identityChanged: Boolean = false,
    /** Projects with automatic sync on for this device. */
    val autoSyncProjects: Set<String> = emptySet(),
)

data class NearbyDevice(val deviceId: String, val name: String, val platform: String)

/** The pairing flow, on either side. */
sealed interface PairingUi {
    /** Requester: waiting for the other device to show its code. */
    data class Requesting(val name: String) : PairingUi

    /** Requester: type the code shown on [name]. */
    data class EnterCode(val name: String, val triesLeft: Int, val wrongBefore: Boolean) : PairingUi

    /** Target: show [code] to the user, with who is asking. */
    data class ShowCode(val code: String, val requesterName: String, val expiresAtMs: Long) : PairingUi

    data class Paired(val name: String) : PairingUi
    data class Expired(val name: String) : PairingUi
    data class Locked(val name: String) : PairingUi
    data class IdentityChanged(val name: String) : PairingUi

    /** [reason] is a plain sentence for the user (unreachable, refused, network blocks devices...). */
    data class Failed(val name: String, val reason: String) : PairingUi
}

sealed interface SyncUi {
    val deviceName: String

    data class Planning(override val deviceName: String) : SyncUi

    data class Plan(
        override val deviceName: String,
        val newerHere: List<String>,
        val newerThere: List<String>,
        val changedOnBoth: List<String>,
        val route: LinkRoute,
    ) : SyncUi

    data class Transferring(override val deviceName: String, val doneFiles: Int, val totalFiles: Int) : SyncUi

    /** Files changed on both sides; each needs a [ConflictChoice]. */
    data class Conflicts(override val deviceName: String, val conflicts: List<SyncConflict>) : SyncUi

    /** [installHint] is a command such as `npm install` when a manifest or lock file changed. */
    data class Done(override val deviceName: String, val sent: Int, val received: Int, val installHint: String?) : SyncUi

    data class Failed(override val deviceName: String, val reason: String) : SyncUi
}

/** One conflict, with both texts for the side-by-side compare (null for a binary file). */
data class SyncConflict(val path: String, val mine: String?, val theirs: String?)

data class IncomingProject(val name: String, val fromDevice: String, val targetPath: String)
