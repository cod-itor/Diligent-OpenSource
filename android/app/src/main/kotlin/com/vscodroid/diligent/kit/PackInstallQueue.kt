package com.vscodroid.diligent.kit

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vscodroid.setup.ToolchainFailure
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's install path for toolchain packs, as [PackInstallQueue] uses it.
 * [ToolchainPackInstaller] is the real one; tests pass a stand-in.
 */
interface PackInstaller {
    /** Starts installing [pack]; progress and the outcome arrive through [listen]. */
    fun install(pack: String)
    fun cancel(pack: String)
    /** Starts (or, with null, stops) hearing about installs. Called on any thread. */
    fun listen(onState: ((pack: String, status: Int, percent: Int, why: ToolchainFailure?) -> Unit)?)
    /** Whether another install in the app holds [pack] right now. */
    fun heldElsewhere(pack: String): Boolean
    /** Puts Play's question about a large download on mobile data. */
    fun confirm()
    /** The installed packs, by pack name. Reads a file: call it off the main thread. */
    fun installed(): Set<String>
}

/**
 * [PackInstaller] over [ToolchainManager], the same install the Toolchains
 * screen and the first-run picker use: Play asset packs, or the GitHub
 * release download on a sideloaded build. Built on the application context,
 * so the download holds no screen; [activity] is read only to put Play's
 * mobile-data question.
 */
class ToolchainPackInstaller(context: Context, private val activity: () -> Activity?) : PackInstaller {
    private val manager = ToolchainManager(context.applicationContext)

    override fun install(pack: String) = manager.install(pack)
    override fun cancel(pack: String) = manager.cancel(pack)

    override fun listen(onState: ((String, Int, Int, ToolchainFailure?) -> Unit)?) {
        manager.onStateChange = onState
        if (onState != null) manager.registerListener() else manager.unregisterListener()
    }

    override fun heldElsewhere(pack: String): Boolean = ToolchainManager.packIsBeingInstalled(pack)

    override fun confirm() {
        val screen = activity()
        if (screen == null || screen.isFinishing || screen.isDestroyed) {
            Logger.w(TAG, "No screen to confirm the download on")
            return
        }
        manager.showConfirmationDialog(screen)
    }

    override fun installed(): Set<String> = Languages.installedPacks(manager.getInstalledToolchains())

    private companion object {
        const val TAG = "PackInstall"
    }
}

/**
 * Setup's Languages list: installs packs one after another through
 * [installer] and keeps where it stands for the screen. Kept by the
 * controller, so leaving Setup while packs download loses nothing.
 *
 * [post] runs a block on the main thread: the installer reports from its own
 * threads. Main thread only otherwise.
 */
class PackInstallQueue(
    private val installer: PackInstaller,
    private val post: (() -> Unit) -> Unit,
) {
    /** The queue on screen, or null when none was started (or it was put away). */
    var queue by mutableStateOf<PackQueue?>(null)
        private set

    /** The installed packs, by pack name, as last read. */
    var installed by mutableStateOf<Set<String>>(emptySet())
        private set

    val busy: Boolean get() = queue?.finished == false

    /** Reads the installed packs again. */
    suspend fun refresh() {
        installed = withContext(Dispatchers.IO) { installer.installed() }
    }

    /** Installs [packs] in this order. Does nothing while another queue runs or when [packs] is empty. */
    fun start(packs: List<String>) {
        if (busy || packs.isEmpty()) return
        queue = PackQueue(packs)
        installer.listen { pack, status, percent, why ->
            val held = installer.heldElsewhere(pack)
            post { onState(pack, status, percent, why, held) }
        }
        installer.install(packs.first())
    }

    /** One report from the installer, on the main thread. */
    fun onState(pack: String, status: Int, percent: Int, why: ToolchainFailure?, heldElsewhere: Boolean) {
        val before = queue ?: return
        if (before.finished) return
        val after = before.on(pack, status, percent, why, heldElsewhere)
        queue = after
        if (after.needsConfirmation && !before.needsConfirmation) installer.confirm()
        if (after.index != before.index) {
            installed = installed + after.installed
            val next = after.current
            if (next != null) installer.install(next) else end()
        }
    }

    /** Stops the pack downloading now, and the ones after it. */
    fun cancel() {
        val current = queue ?: return
        if (current.finished) return
        current.current?.let(installer::cancel)
        queue = current.cancel()
        end()
    }

    /** Puts a finished queue away. */
    fun dismiss() {
        if (!busy) queue = null
    }

    private fun end() {
        installer.listen(null)
    }
}
