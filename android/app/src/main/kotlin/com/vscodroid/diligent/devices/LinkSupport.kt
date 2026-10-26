package com.vscodroid.diligent.devices

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The one thread [DeviceLinkService] runs on. sync-core's state machines are
 * not thread-safe, so every socket callback, timer and user action is posted
 * here. Tests use a queue they drain by hand.
 */
interface LinkLoop {
    fun post(task: () -> Unit)

    /** Runs [task] after [delayMs]; the returned function cancels it. */
    fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit
}

/** A [LinkLoop] on one daemon thread. */
class ThreadLinkLoop(name: String = "diligent-link") : LinkLoop {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    override fun post(task: () -> Unit) {
        executor.execute(task)
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }
}

/** Why a pairing or a sync could not go ahead, shown as a plain sentence ([LinkHost.text]). */
enum class LinkProblem {
    /** This phone is on no Wi-Fi or hotspot, and Tailscale does not help. */
    NotOnNetwork,

    /** Not found on this network: different networks, or one that hides devices from each other. */
    NotFound,

    /** It did not answer at any known address. */
    Unreachable,
    NoTailscaleHere,
    NoTailscaleAddress,
    PhoneToPhoneUnavailable,
    IdentityChanged,
    NotAccepting,
    Refused,
    WrongDevice,
    ConnectionLost,
    Declined,
    Busy,
    SnapshotFailed,
    SyncFailed,
    NotPaired,
    NotAProject,
}

/**
 * What [DeviceLinkService] needs from Android, behind an interface so JVM
 * tests can fake it: the editor, snapshots, the network and strings.
 */
interface LinkHost {
    /** This device's name as other devices see it. */
    val deviceName: String

    /** Where a project received from another device goes (`~/projects`). */
    val projectsDir: File

    /** Saves every open editor (`workbench.action.files.saveAll`). */
    fun saveAll()

    /**
     * Takes a snapshot of [projectDir] that Undo can restore and returns its
     * id. Blocking; throws when it could not be taken, which stops the sync
     * before anything changes.
     */
    fun snapshot(projectDir: File, label: String): String

    /** This phone's Tailscale IPv4 address, or null when Tailscale is off. */
    fun ownTailscaleAddress(): String?

    /** This phone's address on its Wi-Fi or hotspot, or null when it is on neither. */
    fun ownLanAddress(): String?

    /** The plain sentence for [problem], about the device called [name]. */
    fun text(problem: LinkProblem, name: String): String

    /** Whether the foreground notification should say this device stays reachable in the background. */
    fun reachableInBackground(on: Boolean)
}

/** Install commands to offer when a received file is a package manifest or lock file (Part D 1.6). */
object InstallHints {
    private val byName = linkedMapOf(
        "package-lock.json" to "npm install",
        "yarn.lock" to "yarn install",
        "pnpm-lock.yaml" to "pnpm install",
        "package.json" to "npm install",
        "requirements.txt" to "pip install -r requirements.txt",
        "Pipfile.lock" to "pipenv install",
        "Gemfile.lock" to "bundle install",
        "Gemfile" to "bundle install",
        "Cargo.lock" to "cargo fetch",
        "go.sum" to "go mod download",
    )

    /** The command for the first known file among [paths] (lock files win over manifests), or null. */
    fun forPaths(paths: Collection<String>): String? {
        val names = paths.map { it.substringAfterLast('/') }.toSet()
        // A lock file names the tool better than package.json does.
        return byName.entries.firstOrNull { it.key in names }?.value
    }
}

/** A folder name for a project arriving from another device: safe characters, never empty. */
internal fun incomingFolderName(name: String?): String {
    val cleaned = name.orEmpty().filter { it.isLetterOrDigit() || it in "._- " }.trim().trimStart('.')
    return cleaned.ifEmpty { "project" }.take(80)
}

/** [base] in [parent], or `base-2`, `base-3`... when taken. */
internal fun uniqueFolder(parent: File, base: String): File {
    var candidate = File(parent, base)
    var n = 2
    while (candidate.exists()) candidate = File(parent, "$base-${n++}")
    return candidate
}
