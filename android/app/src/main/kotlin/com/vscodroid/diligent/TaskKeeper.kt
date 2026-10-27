package com.vscodroid.diligent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vscodroid.MainActivity
import com.vscodroid.R

/**
 * Keeps what the user started running while the phone is in a pocket.
 *
 * The diligent-core supervisor reports its tasks through
 * `diligentUpdateTasks`; this then:
 *
 * - lists the running tasks on VSCodroid's existing foreground notification
 *   (NodeService calls [decorate] while building it), each with a Stop that
 *   sends `diligent.core.stopTask` back to the editor;
 * - holds a partial wake lock while any task runs or restarts, renewed hourly
 *   and released as soon as none does ([WakePolicy]);
 * - posts a notification that opens Health when Android kills a task or one
 *   fails for a named cause.
 *
 * One per process, because the notification belongs to the service and the
 * tasks arrive through the activity's WebView: neither owns the other. State
 * changes on the main thread only.
 */
object TaskKeeper {
    /** Notification action: stop the tasks in [EXTRA_TASK_IDS]. Handled by [DiligentController.onIntent]. */
    const val ACTION_STOP_TASKS = "com.vscodroid.diligent.action.STOP_TASKS"

    /** Notification tap after a kill: open the Health screen. */
    const val ACTION_OPEN_HEALTH = "com.vscodroid.diligent.action.OPEN_HEALTH"

    const val EXTRA_TASK_IDS = "com.vscodroid.diligent.extra.TASK_IDS"

    /** The editor command (diligent-core) that stops one task by id. */
    const val COMMAND_STOP_TASK = "diligent.core.stopTask"

    /** A Stop per task up to this many; past it one Stop for all, so the server's own Stop still fits. */
    const val MAX_STOP_ACTIONS = 2

    private const val CHANNEL_ID = "diligent_tasks"
    private const val PROBLEM_NOTIFICATION_ID = 2001
    private const val REQUEST_STOP_BASE = 2100
    private const val REQUEST_HEALTH = 2200
    private const val WAKE_TAG = "Diligent:tasks"

    /** The supervisor's latest report, or null before the first. Read by Health for the process count. */
    var latest by mutableStateOf<TaskUpdate?>(null)
        private set

    private var appContext: Context? = null
    private var refreshNotification: (() -> Unit)? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var acquiredAt: Long? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val recheck = Runnable { applyWakeLock() }

    /** The foreground service is up: [refresh] re-posts its notification. */
    fun attach(refresh: () -> Unit) {
        refreshNotification = refresh
    }

    /**
     * The foreground service is gone, and the editor server with it: no
     * report will come to say the tasks ended, so forget them and let the
     * phone sleep rather than renew the lock for tasks that no longer run.
     * Main thread (the service's onDestroy).
     */
    @MainThread
    fun detach() {
        refreshNotification = null
        latest = null
        applyWakeLock()
    }

    /** A new report from the supervisor. */
    @MainThread
    fun update(context: Context, update: TaskUpdate) {
        val app = context.applicationContext
        appContext = app
        val previous = latest
        latest = update
        applyWakeLock()
        if (previous?.active != update.active) refreshNotification?.invoke()
        update.newProblems(previous).lastOrNull()?.let { postProblem(app, it) }
    }

    /**
     * Adds the running tasks to the foreground notification [builder], whose
     * own line is [baseText]: the collapsed line names the tasks, the expanded
     * one keeps [baseText] above the list, and each task gets a Stop.
     */
    fun decorate(context: Context, builder: NotificationCompat.Builder, baseText: String) {
        val active = latest?.active.orEmpty()
        if (active.isEmpty()) return
        val res = context.resources
        val summary = if (active.size == 1) {
            context.getString(R.string.diligent_tasks_one_running, active.single().name)
        } else {
            res.getQuantityString(R.plurals.diligent_tasks_running, active.size, active.size)
        }
        val lines = active.joinToString("\n") { task ->
            val label = if (task.project.isEmpty()) task.name
            else context.getString(R.string.diligent_task_label, task.name, task.project)
            context.getString(
                if (task.state == TaskState.Restarting) R.string.diligent_task_line_restarting
                else R.string.diligent_task_line_running,
                label,
            )
        }
        builder.setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$baseText\n\n$lines"))
        if (active.size <= MAX_STOP_ACTIONS) {
            active.forEachIndexed { index, task ->
                builder.addAction(
                    0,
                    context.getString(R.string.diligent_task_stop, task.name),
                    stopIntent(context, index, listOf(task.id)),
                )
            }
        } else {
            builder.addAction(
                0,
                context.getString(R.string.diligent_task_stop_all),
                stopIntent(context, 0, active.map { it.id }),
            )
        }
    }

    /**
     * Opens the editor activity, which sends `diligent.core.stopTask` for each
     * id. An activity rather than a broadcast because only the editor page can
     * reach the supervisor, and the activity is what holds the page; it is
     * `singleTask`, so this brings the running one forward.
     */
    private fun stopIntent(context: Context, slot: Int, ids: List<String>): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_STOP_TASKS)
            .putExtra(EXTRA_TASK_IDS, ids.toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_STOP_BASE + slot,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** The ids a Stop from the notification carries, or empty for any other intent. */
    fun stopIds(intent: Intent?): List<String> {
        if (intent?.action != ACTION_STOP_TASKS) return emptyList()
        return intent.getStringArrayExtra(EXTRA_TASK_IDS)?.filter { it.isNotBlank() }.orEmpty()
    }

    // -- Wake lock --

    private fun applyWakeLock() {
        handler.removeCallbacks(recheck)
        val context = appContext ?: return
        val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
            ?: return
        val now = SystemClock.elapsedRealtime()
        val running = latest?.active?.isNotEmpty() == true
        when (WakePolicy.decide(running, lock.isHeld, acquiredAt, now)) {
            WakeAction.Acquire, WakeAction.Renew -> {
                // Not reference counted, so a renewal replaces the timeout
                // rather than stacking a second hold.
                lock.acquire(WakePolicy.HOLD_MS)
                acquiredAt = now
                handler.postDelayed(recheck, WakePolicy.RENEW_MS)
            }
            WakeAction.Keep -> handler.postDelayed(recheck, WakePolicy.nextCheckIn(acquiredAt ?: now, now))
            WakeAction.Release -> {
                lock.release()
                acquiredAt = null
            }
            WakeAction.None -> acquiredAt = null
        }
    }

    // -- Problem notification --

    private fun postProblem(context: Context, task: TaskInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.diligent_task_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.diligent_task_channel_description) },
        )
        val title = context.getString(
            if (task.state == TaskState.Restarting) R.string.diligent_task_problem_restarting
            else R.string.diligent_task_problem_stopped,
            task.name,
        )
        val reason = task.message ?: context.getString(causeText(task.cause))
        val text = context.getString(R.string.diligent_task_problem_text, reason)
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_HEALTH)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, REQUEST_HEALTH, open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        manager.notify(PROBLEM_NOTIFICATION_ID, notification)
    }

    private fun causeText(cause: TaskCause?): Int = when (cause) {
        TaskCause.LowMemory -> R.string.diligent_task_cause_low_memory
        TaskCause.ProcessLimit -> R.string.diligent_task_cause_process_limit
        TaskCause.PortBusy -> R.string.diligent_task_cause_port_busy
        TaskCause.Killed -> R.string.diligent_task_cause_killed
        TaskCause.Exit, null -> R.string.diligent_task_cause_exit
    }
}
