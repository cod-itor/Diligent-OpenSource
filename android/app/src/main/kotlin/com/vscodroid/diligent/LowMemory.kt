package com.vscodroid.diligent

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Low-memory mode's rules, kept free of Android so they are tested without a
 * device.
 *
 * On by default on a phone with 4 GB of memory or less. `ActivityManager`'s
 * `totalMem` is what the kernel can use, a little under what the box says, so
 * a "4 GB" phone reads about 3.6 to 3.8 GiB and a "6 GB" one about 5.5 GiB:
 * a cut at 4 GiB puts every 4 GB phone in and every 6 GB phone out.
 */
object LowMemoryPolicy {
    const val DEFAULT_ON_AT_OR_BELOW_BYTES = 4L * 1024 * 1024 * 1024

    /** Below this much free memory, a run in low-memory mode asks first. */
    const val RUN_WARNING_BELOW_BYTES = 500L * 1024 * 1024

    /** Whether low-memory mode starts on, for a phone reporting [totalMemBytes]. Unreadable (0 or less) reads as off. */
    fun defaultOn(totalMemBytes: Long): Boolean = totalMemBytes in 1..DEFAULT_ON_AT_OR_BELOW_BYTES

    /**
     * Whether to warn before a run: low-memory mode is on and the phone has
     * less than 500 MB free. An unreadable value (0 or less) never warns.
     */
    fun warnBeforeRun(lowMemory: Boolean, availMemBytes: Long): Boolean =
        lowMemory && availMemBytes in 1 until RUN_WARNING_BELOW_BYTES

    /** [bytes] in whole MB, for the warning. */
    fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)
}

/**
 * Whether low-memory mode is on, stored per device beside the view choice.
 * Until the user flips the switch it follows [LowMemoryPolicy.defaultOn].
 */
class LowMemorySettings(private val prefs: SharedPreferences, private val totalMemBytes: () -> Long) {

    var enabled: Boolean
        get() = if (prefs.contains(KEY_LOW_MEMORY)) prefs.getBoolean(KEY_LOW_MEMORY, false)
        else LowMemoryPolicy.defaultOn(totalMemBytes())
        set(value) {
            prefs.edit { putBoolean(KEY_LOW_MEMORY, value) }
        }

    companion object {
        internal const val KEY_LOW_MEMORY = "low_memory_mode"

        /** The editor command that applies or removes the mode's settings (diligent-bridge). */
        const val COMMAND_APPLY = "diligent.lowMemory.apply"
    }
}

/** The phone's memory as `ActivityManager` reports it; 0 when it cannot be read. */
object DeviceMemory {
    fun totalBytes(context: Context): Long = info(context)?.totalMem ?: 0L

    fun availableBytes(context: Context): Long = info(context)?.availMem ?: 0L

    private fun info(context: Context): ActivityManager.MemoryInfo? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }
}

/** The warning before a run: how much memory is free, and the run to start if the user goes on. */
class MemoryWarning(val availableMb: Long, val proceed: () -> Unit)
