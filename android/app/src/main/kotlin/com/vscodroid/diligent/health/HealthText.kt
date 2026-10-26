package com.vscodroid.diligent.health

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import com.vscodroid.R

/**
 * The words of each health check: its title and the sentence under it. Shared
 * by the Health screen and `diligentGetHealth` (what `kit doctor` prints), so
 * both say the same thing.
 *
 * Plain string resources through a [Context], with no Compose, so it can run
 * on the JavaBridge thread: reading resources is thread-safe.
 */
object HealthText {
    @StringRes
    fun titleRes(topic: HealthTopic): Int = when (topic) {
        HealthTopic.Battery -> R.string.diligent_health_battery
        HealthTopic.ChildProcesses, HealthTopic.ChildProcessesAdb -> R.string.diligent_health_children
        HealthTopic.Notifications -> R.string.diligent_health_notifications
        HealthTopic.Storage -> R.string.diligent_health_storage
        HealthTopic.Processes -> R.string.diligent_health_processes
    }

    fun title(context: Context, check: HealthCheck): String = context.getString(titleRes(check.topic))

    /** The sentence under a check's title, for its status. */
    fun detail(context: Context, check: HealthCheck, facts: HealthFacts): String {
        val status = check.status
        return when (check.topic) {
            HealthTopic.Battery -> when (status) {
                HealthStatus.Pass -> context.getString(R.string.diligent_health_battery_pass)
                HealthStatus.Fail -> context.getString(R.string.diligent_health_battery_fail)
                HealthStatus.CannotCheck -> context.getString(R.string.diligent_health_unreadable)
            }
            HealthTopic.ChildProcesses -> when (status) {
                HealthStatus.Pass -> context.getString(R.string.diligent_health_children_pass)
                HealthStatus.Fail -> context.getString(R.string.diligent_health_children_fail)
                HealthStatus.CannotCheck -> context.getString(R.string.diligent_health_children_unknown)
            }
            HealthTopic.ChildProcessesAdb ->
                if (status == HealthStatus.Pass) context.getString(R.string.diligent_health_children_pass)
                else context.getString(R.string.diligent_health_adb_body)
            HealthTopic.Notifications -> when (status) {
                HealthStatus.Pass -> context.getString(R.string.diligent_health_notifications_pass)
                HealthStatus.Fail -> context.getString(R.string.diligent_health_notifications_fail)
                HealthStatus.CannotCheck -> context.getString(R.string.diligent_health_unreadable)
            }
            HealthTopic.Storage -> {
                val free = Formatter.formatShortFileSize(context, facts.freeBytes ?: 0)
                when (status) {
                    HealthStatus.Pass -> context.getString(R.string.diligent_health_storage_pass, free)
                    HealthStatus.Fail -> context.getString(R.string.diligent_health_storage_fail, free)
                    HealthStatus.CannotCheck -> context.getString(R.string.diligent_health_unreadable)
                }
            }
            HealthTopic.Processes -> {
                val count = facts.processes ?: 0
                when (status) {
                    HealthStatus.Pass -> context.getString(R.string.diligent_health_processes_pass, count, facts.processLimit)
                    HealthStatus.Fail -> context.getString(R.string.diligent_health_processes_fail, count, facts.processLimit)
                    HealthStatus.CannotCheck -> context.getString(R.string.diligent_health_processes_unknown)
                }
            }
        }
    }
}
