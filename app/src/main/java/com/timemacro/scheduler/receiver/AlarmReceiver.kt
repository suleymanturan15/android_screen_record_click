package com.timemacro.scheduler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timemacro.scheduler.service.scheduler.SchedulerForegroundService
import com.timemacro.scheduler.service.scheduler.AndroidSchedulerEngine

/**
 * AlarmManager tetiklerini yakalayan receiver.
 *
 * - SchedulerForegroundService'i başlatır (FGS) ve koşuyu service içinde yürütür.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AndroidSchedulerEngine.EXTRA_TASK_ID) ?: return
        val scheduledAt = intent.getLongExtra(AndroidSchedulerEngine.EXTRA_SCHEDULED_AT, System.currentTimeMillis())

        val serviceIntent =
            Intent(context, SchedulerForegroundService::class.java).apply {
                action = SchedulerForegroundService.ACTION_RUN_TASK
                putExtra(SchedulerForegroundService.EXTRA_TASK_ID, taskId)
                putExtra(SchedulerForegroundService.EXTRA_SCHEDULED_AT, scheduledAt)
                putExtra(SchedulerForegroundService.EXTRA_TRIGGER, "ALARM")
            }

        context.startForegroundService(serviceIntent)
    }
}

