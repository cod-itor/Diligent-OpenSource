package com.vscodroid.diligent.kit

import androidx.annotation.StringRes
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.R
import com.vscodroid.handedToAnotherInstall
import com.vscodroid.isTerminalPackStatus
import com.vscodroid.setup.SPACE_BUFFER
import com.vscodroid.setup.ToolchainFailure
import com.vscodroid.setup.ToolchainRegistry

/**
 * One language pack the Languages list offers, from [ToolchainRegistry.available]:
 * [installBytes] is the unpacked size, [downloadBytes] the ZIP.
 */
data class LanguagePack(
    val pack: String,
    val label: String,
    @param:StringRes val descriptionRes: Int,
    val installBytes: Long,
    val downloadBytes: Long,
)

/** What installing a set of packs costs: the download, and the free space the installs ask for. */
data class PackTotals(val downloadBytes: Long, val spaceBytes: Long)

/**
 * The pure half of Setup's Languages list: which packs are missing, the order
 * to install them in, and what that costs. Tested on the JVM.
 */
object Languages {
    /**
     * Packs that need another one first. Kotlin compiles to Java bytecode and
     * runs on the Java pack, so asking for Kotlin installs Java before it.
     */
    val REQUIRES: Map<String, List<String>> = mapOf("toolchain_kotlin" to listOf("toolchain_java"))

    /** Every pack the app offers, in the registry's order, so a pack added there appears here. */
    fun packs(available: List<ToolchainRegistry.ToolchainInfo> = ToolchainRegistry.available): List<LanguagePack> =
        available.map { LanguagePack(it.packName, it.displayName, it.descriptionRes, it.estimatedSize, it.downloadSize) }

    /**
     * The packs to install for [wanted], with what they need first, leaving out
     * what is [installed] and what is not in [known]. The order is [known]'s,
     * except that a pack another one needs always comes before it.
     */
    fun toInstall(wanted: Collection<String>, installed: Set<String>, known: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        fun visit(pack: String, seen: Set<String>) {
            if (pack in out || pack in installed || pack !in known || pack in seen) return
            for (need in REQUIRES[pack].orEmpty()) visit(need, seen + pack)
            out.add(pack)
        }
        for (pack in known.filter { it in wanted }) visit(pack, emptySet())
        return out.toList()
    }

    /** Every pack not installed yet, in install order. */
    fun missing(packs: List<LanguagePack>, installed: Set<String>): List<String> {
        val known = packs.map { it.pack }
        return toInstall(known, installed, known)
    }

    /**
     * The download and free space [ids] need. The installs run one after
     * another, and each holds its download beside the unpacked tree until it
     * is done, so the space is every tree, plus the largest one again, plus
     * the buffer ToolchainManager's own check keeps.
     */
    fun totals(packs: List<LanguagePack>, ids: Collection<String>): PackTotals {
        val chosen = packs.filter { it.pack in ids }
        if (chosen.isEmpty()) return PackTotals(0, 0)
        return PackTotals(
            downloadBytes = chosen.sumOf { it.downloadBytes },
            spaceBytes = chosen.sumOf { it.installBytes } + chosen.maxOf { it.installBytes } + SPACE_BUFFER,
        )
    }

    /** The record's short names (`java`) as pack names (`toolchain_java`). */
    fun installedPacks(shortNames: Collection<String>): Set<String> =
        shortNames.filter { it.isNotBlank() }.map { if (it.startsWith("toolchain_")) it else "toolchain_$it" }.toSet()
}

/** A pack that did not install, and why when that is known. */
data class PackFailure(val pack: String, @param:StringRes val reason: Int?)

/**
 * Set up all (or one Install): [packs] installed one after another. A pack
 * that fails is recorded and the next one starts, so one missing download
 * does not stop the rest; a pack whose required pack failed is skipped.
 */
data class PackQueue(
    val packs: List<String>,
    val index: Int = 0,
    val percent: Int = 0,
    val installed: List<String> = emptyList(),
    val failed: List<PackFailure> = emptyList(),
    /** Play asked to confirm a large download on mobile data, and the question has not been put yet. */
    val needsConfirmation: Boolean = false,
    val cancelled: Boolean = false,
) {
    val current: String? get() = if (cancelled) null else packs.getOrNull(index)
    val finished: Boolean get() = cancelled || index >= packs.size

    /** Where [pack] stands in this queue. */
    fun stateOf(pack: String): PackRowState? = when {
        pack in installed -> PackRowState.Installed
        failed.any { it.pack == pack } -> PackRowState.Failed
        pack == current -> PackRowState.Installing
        pack in packs && !finished && packs.indexOf(pack) > index -> PackRowState.Waiting
        else -> null
    }

    /**
     * The queue after the app reports [status] for [pack]. Reports for a pack
     * other than the current one are ignored, as the first-run queue does.
     * [heldElsewhere] is whether another install in the app holds that pack.
     */
    fun on(pack: String, status: Int, percent: Int, why: ToolchainFailure?, heldElsewhere: Boolean): PackQueue {
        if (pack != current) return this
        return when {
            status == AssetPackStatus.DOWNLOADING || status == AssetPackStatus.TRANSFERRING ->
                copy(percent = percent.coerceIn(0, 100), needsConfirmation = false)
            status == AssetPackStatus.REQUIRES_USER_CONFIRMATION -> copy(needsConfirmation = true)
            status == AssetPackStatus.COMPLETED -> copy(installed = installed + pack).advance()
            // Another install is putting it in place right now: not a failure.
            handedToAnotherInstall(status, heldElsewhere) -> copy(installed = installed + pack).advance()
            isTerminalPackStatus(status) -> copy(failed = failed + PackFailure(pack, why?.message)).advance()
            else -> this
        }
    }

    /** Stops after the current pack; what was not reached is neither installed nor failed. */
    fun cancel(): PackQueue = copy(cancelled = true, needsConfirmation = false)

    /** On to the next pack, skipping any whose required pack failed. */
    private fun advance(): PackQueue {
        var next = copy(index = index + 1, percent = 0, needsConfirmation = false)
        while (true) {
            val pack = next.packs.getOrNull(next.index) ?: return next
            val failedNeed = Languages.REQUIRES[pack].orEmpty().any { need -> next.failed.any { it.pack == need } }
            if (!failedNeed) return next
            next = next.copy(
                index = next.index + 1,
                failed = next.failed + PackFailure(pack, R.string.diligent_languages_needs_other),
            )
        }
    }
}

/** A pack row's status while a queue holds it. */
enum class PackRowState { Waiting, Installing, Installed, Failed }
