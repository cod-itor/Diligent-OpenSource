package com.vscodroid.diligent.devices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [DeviceLink] that only holds a state, for Compose previews and screen tests. */
class PreviewDeviceLink(initial: DevicesState = DevicesState()) : DeviceLink {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<DevicesState> = flow

    override fun screenOpen(open: Boolean) = Unit
    override fun setVisibility(visibility: Visibility) { flow.value = flow.value.copy(visibility = visibility) }
    override fun setStayReachable(on: Boolean) { flow.value = flow.value.copy(stayReachable = on) }
    override fun setConnectionMode(mode: ConnectionMode) { flow.value = flow.value.copy(connectionMode = mode) }
    override fun connect(nearbyDeviceId: String) = Unit
    override fun connectAddress(host: String, port: Int, deviceId: String?) = Unit
    override fun submitCode(code: String) = Unit
    override fun cancelPairing() { flow.value = flow.value.copy(pairing = null) }
    override fun pairingQr(): String? = null
    override fun rename(deviceId: String, name: String) = Unit
    override fun forget(deviceId: String) = Unit
    override fun setApproval(deviceId: String, approval: Approval) = Unit
    override fun planSync(deviceId: String, projectPath: String) = Unit
    override fun confirmSync() = Unit
    override fun resolveConflict(path: String, choice: ConflictChoice) = Unit
    override fun cancelSync() { flow.value = flow.value.copy(sync = null) }
    override fun setAutoSync(projectPath: String, deviceId: String, on: Boolean) = Unit
    override fun answerIncomingProject(accept: Boolean) { flow.value = flow.value.copy(incomingProject = null) }
    override fun dismiss() { flow.value = flow.value.copy(pairing = null, sync = null) }
}

/** What [com.vscodroid.diligent.MobileActions.devices] answers where no real link exists (previews, tests). */
val IdleDeviceLink: DeviceLink by lazy { PreviewDeviceLink() }
