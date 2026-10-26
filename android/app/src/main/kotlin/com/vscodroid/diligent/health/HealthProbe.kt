package com.vscodroid.diligent.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.vscodroid.diligent.TaskKeeper

/**
 * Reads what [HealthRules] decides on, and opens the settings a Fix names.
 * Every read is guarded: a value the phone will not give is null, which
 * the screen shows as "cannot check".
 */
object HealthProbe {
    /** The `Settings.Global` key Android 12L+ consults before killing phantom processes. */
    const val PHANTOM_MONITOR_KEY = "settings_enable_monitor_phantom_procs"

    fun read(context: Context): HealthFacts {
        val latest = TaskKeeper.latest
        return HealthFacts(
            sdk = Build.VERSION.SDK_INT,
            ignoringBatteryOptimizations = guarded {
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
            },
            phantomMonitor = guarded { Settings.Global.getString(context.contentResolver, PHANTOM_MONITOR_KEY) },
            notificationsEnabled = guarded { NotificationManagerCompat.from(context).areNotificationsEnabled() },
            freeBytes = guarded { StatFs(context.filesDir.path).availableBytes },
            processes = latest?.processes,
            processLimit = latest?.processLimit ?: 32,
        )
    }

    /**
     * Opens the system screen for [fix]; [HealthFix.RunScreen] is the app's
     * own and handled by the caller. Falls back to the main Settings screen on
     * a phone that lacks the specific one.
     */
    fun open(context: Context, fix: HealthFix) {
        val intent = when (fix) {
            HealthFix.BatterySettings -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            HealthFix.DeveloperOptions -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            HealthFix.NotificationSettings -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            HealthFix.StorageSettings -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            HealthFix.RunScreen -> return
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // Nothing to open on this phone; the check's text still says what to do.
            }
        }
    }

    private inline fun <T> guarded(read: () -> T?): T? = try {
        read()
    } catch (_: RuntimeException) {
        // SecurityException for a setting this app may not read, and whatever
        // else a vendor's service throws: either way, not known.
        null
    }
}
