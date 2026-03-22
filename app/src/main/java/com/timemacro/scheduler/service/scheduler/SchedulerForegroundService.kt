package com.timemacro.scheduler.service.scheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.timemacro.scheduler.App
import com.timemacro.scheduler.core.macro.MacroPlaybackStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zamanlama orkestratörü:
 * - AlarmReceiver/WorkManager tetikleri buraya gelir
 * - Koşullar doğrulanır (izinler, accessibility enabled, vs.)
 * - Playback başlatma isteği Accessibility katmanına yönlendirilir
 */
class SchedulerForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private var currentTaskId: String? = null
    private var lastNotifText: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Running macro task…")

        if (intent?.action == ACTION_RUN_TASK) {
            val taskId = intent.getStringExtra(EXTRA_TASK_ID)
            val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis())
            val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "UNKNOWN"
            if (taskId != null) {
                currentTaskId = taskId
                scope.launch { observePlaybackProgress(taskId) }
                scope.launch {
                    val app = application as App
                    val container = app.container
                    acquireWakeLock()

                    try {
                        // Run pipeline (with real scheduledAt).
                        (container.taskRunner as AndroidTaskRunner).runTaskInternal(
                            taskId = taskId,
                            trigger = trigger,
                            scheduledTimeEpochMs = scheduledAt,
                        )

                        // Schedule next run based on updated task state.
                        val task = withContext(Dispatchers.IO) { container.taskRepository.getById(taskId) }
                        if (task != null) {
                            container.schedulerEngine.schedule(task)
                        }
                    } finally {
                        releaseWakeLock()
                    }

                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        scope.cancel()
    }

    private fun startAsForeground(text: String) {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "TimeMacro tasks",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }

        val notification =
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("TimeMacro Scheduler")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .build()

        startForeground(1001, notification)
    }

    private suspend fun observePlaybackProgress(taskId: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        MacroPlaybackStateHolder.state.collect { state ->
            if (currentTaskId != taskId) return@collect
            if (state.taskId != taskId) return@collect
            if (state.status != com.timemacro.scheduler.core.macro.MacroPlaybackState.Status.RUNNING) return@collect

            val txt = "Running macro task… Step ${state.currentIndex}/${state.totalActions}"
            if (txt == lastNotifText) return@collect
            lastNotifText = txt

            val notification =
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("TimeMacro Scheduler")
                    .setContentText(txt)
                    .setSmallIcon(android.R.drawable.ic_popup_sync)
                    .setOngoing(true)
                    .build()

            nm.notify(1001, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimeMacro:TaskPlayback").apply {
                setReferenceCounted(false)
                acquire(20 * 60 * 1000L) // safety timeout
            }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_RUN_TASK = "com.timemacro.scheduler.action.RUN_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_SCHEDULED_AT = "extra_scheduled_at"
        const val EXTRA_TRIGGER = "extra_trigger"
        private const val CHANNEL_ID = "timemacro_tasks"
    }
}

