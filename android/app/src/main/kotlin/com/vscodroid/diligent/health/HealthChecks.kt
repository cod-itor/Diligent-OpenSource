package com.vscodroid.diligent.health

/** The outcome of one check. */
enum class HealthStatus { Pass, Fail, CannotCheck }

/** What each check is about; the screen adds the words. */
enum class HealthTopic {
    /** Android's battery optimization for this app. */
    Battery,

    /** Android 14+: "Disable child process restrictions" in Developer options. */
    ChildProcesses,

    /** Android 12 and 13: the phantom process limit, lifted only over adb. */
    ChildProcessesAdb,

    /** Android 13+: notifications allowed. */
    Notifications,

    /** Free space for packages and builds. */
    Storage,

    /** The app's process count against Android's limit. */
    Processes,
}

/** Where a check's Fix button goes. */
enum class HealthFix { BatterySettings, DeveloperOptions, NotificationSettings, StorageSettings, RunScreen }

/**
 * One row of the Health screen. [steps] says to show the Developer options
 * steps; [commands] are adb commands with a copy button each.
 */
data class HealthCheck(
    val topic: HealthTopic,
    val status: HealthStatus,
    val fix: HealthFix? = null,
    val steps: Boolean = false,
    val commands: List<String> = emptyList(),
)

/**
 * What the phone says, read by [HealthProbe]. A null is a value that could not
 * be read, which the check reports as "cannot check" rather than guessing.
 *
 * [phantomMonitor] is the raw `Settings.Global` value of
 * `settings_enable_monitor_phantom_procs`.
 */
data class HealthFacts(
    val sdk: Int,
    val ignoringBatteryOptimizations: Boolean?,
    val phantomMonitor: String?,
    val notificationsEnabled: Boolean?,
    val freeBytes: Long?,
    val processes: Int?,
    val processLimit: Int = 32,
)

/** The Health screen's decisions, kept free of Android so they are tested on the JVM. */
object HealthRules {
    /** Free space below this fails: npm installs and builds need room. */
    const val MIN_FREE_BYTES = 1024L * 1024 * 1024

    /** Process count at which to warn, a margin below Android's limit of 32. */
    const val PROCESS_WARNING = 24

    const val SDK_12 = 31
    const val SDK_12L = 32
    const val SDK_13 = 33
    const val SDK_14 = 34

    /** Lifts Android 12 and 13's phantom process limit; run over Wireless debugging. */
    val ADB_COMMANDS = listOf(
        "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent\"",
        "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\"",
    )

    /** Android 12L and 13 only: turns the phantom process monitor off. */
    const val ADB_COMMAND_MONITOR = "adb shell settings put global settings_enable_monitor_phantom_procs false"

    fun evaluate(facts: HealthFacts): List<HealthCheck> = listOfNotNull(
        battery(facts.ignoringBatteryOptimizations),
        childProcesses(facts.sdk, facts.phantomMonitor),
        notifications(facts.sdk, facts.notificationsEnabled),
        storage(facts.freeBytes),
        processes(facts.processes),
    )

    fun battery(ignoring: Boolean?): HealthCheck = when (ignoring) {
        true -> HealthCheck(HealthTopic.Battery, HealthStatus.Pass)
        false -> HealthCheck(HealthTopic.Battery, HealthStatus.Fail, HealthFix.BatterySettings)
        null -> HealthCheck(HealthTopic.Battery, HealthStatus.CannotCheck, HealthFix.BatterySettings)
    }

    /**
     * The phantom process limit. Android 11 has none (null: no row). On 14+ the
     * setting can be turned off in Developer options; whether this app may
     * read it differs by phone, so unreadable shows the steps. On 12 and 13
     * only adb lifts it, and the limit itself cannot be read, so it passes
     * only when the monitor is visibly off (12L and 13).
     */
    fun childProcesses(sdk: Int, monitor: String?): HealthCheck? {
        val off = monitorOff(monitor)
        return when {
            sdk < SDK_12 -> null
            sdk >= SDK_14 -> when (off) {
                true -> HealthCheck(HealthTopic.ChildProcesses, HealthStatus.Pass)
                false -> HealthCheck(HealthTopic.ChildProcesses, HealthStatus.Fail, HealthFix.DeveloperOptions, steps = true)
                null -> HealthCheck(HealthTopic.ChildProcesses, HealthStatus.CannotCheck, HealthFix.DeveloperOptions, steps = true)
            }
            sdk >= SDK_12L && off == true -> HealthCheck(HealthTopic.ChildProcessesAdb, HealthStatus.Pass)
            else -> HealthCheck(
                HealthTopic.ChildProcessesAdb,
                if (off == false) HealthStatus.Fail else HealthStatus.CannotCheck,
                HealthFix.DeveloperOptions,
                commands = if (sdk >= SDK_12L) ADB_COMMANDS + ADB_COMMAND_MONITOR else ADB_COMMANDS,
            )
        }
    }

    /** Whether the raw setting says the monitor is off; null when unset or unreadable. */
    fun monitorOff(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "false", "0" -> true
        "true", "1" -> false
        else -> null
    }

    /** Asked for since Android 13, when apps need permission to notify. */
    fun notifications(sdk: Int, enabled: Boolean?): HealthCheck? = when {
        sdk < SDK_13 -> null
        enabled == true -> HealthCheck(HealthTopic.Notifications, HealthStatus.Pass)
        enabled == false -> HealthCheck(HealthTopic.Notifications, HealthStatus.Fail, HealthFix.NotificationSettings)
        else -> HealthCheck(HealthTopic.Notifications, HealthStatus.CannotCheck, HealthFix.NotificationSettings)
    }

    fun storage(freeBytes: Long?): HealthCheck = when {
        freeBytes == null || freeBytes < 0 -> HealthCheck(HealthTopic.Storage, HealthStatus.CannotCheck)
        freeBytes >= MIN_FREE_BYTES -> HealthCheck(HealthTopic.Storage, HealthStatus.Pass)
        else -> HealthCheck(HealthTopic.Storage, HealthStatus.Fail, HealthFix.StorageSettings)
    }

    /** From the supervisor's latest report; none yet is "cannot check". */
    fun processes(count: Int?): HealthCheck = when {
        count == null -> HealthCheck(HealthTopic.Processes, HealthStatus.CannotCheck)
        count < PROCESS_WARNING -> HealthCheck(HealthTopic.Processes, HealthStatus.Pass)
        else -> HealthCheck(HealthTopic.Processes, HealthStatus.Fail, HealthFix.RunScreen)
    }
}
