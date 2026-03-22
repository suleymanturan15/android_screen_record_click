package com.timemacro.scheduler.service.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.timemacro.scheduler.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Safety-net:
 * - Aktif task'lar için alarm var mı kontrol eder
 * - Gerekirse yeniden schedule eder
 *
 * Not: Exact alarm state'i sistemden direkt sorgulamak zor; MVP'de
 * - "alarm scheduled mi?" PendingIntent NO_CREATE ile kontrol edilir
 * - ayrıca overdue task'lar için reschedule yapılır
 */
class HealthCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.retry()
        val container = app.container

        val tasks = withContext(Dispatchers.IO) { container.taskRepository.listActive() }
        val now = System.currentTimeMillis()
        val graceMs = 2 * 60 * 1000L

        tasks.forEach { task ->
            val nextAt = task.nextScheduledAt?.toEpochMilli()
            val alarmMissing =
                (container.schedulerEngine as? AndroidSchedulerEngine)
                    ?.let { engine -> !engine.isAlarmScheduled(task.id) }
                    ?: false
            if (nextAt == null || alarmMissing || now > nextAt + graceMs) {
                container.schedulerEngine.schedule(task)
            }
        }

        return Result.success()
    }
}
