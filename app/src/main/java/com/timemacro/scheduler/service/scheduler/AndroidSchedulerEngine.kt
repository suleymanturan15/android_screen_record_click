package com.timemacro.scheduler.service.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.timemacro.scheduler.domain.model.LogEntry
import com.timemacro.scheduler.domain.model.MacroLog
import com.timemacro.scheduler.domain.model.Task
import com.timemacro.scheduler.domain.repository.MacroLogRepository
import com.timemacro.scheduler.domain.repository.LogRepository
import com.timemacro.scheduler.domain.repository.MacroRepository
import com.timemacro.scheduler.domain.repository.TaskRepository
import com.timemacro.scheduler.domain.scheduler.DefaultTaskPlanner
import com.timemacro.scheduler.domain.scheduler.NextRunResult
import com.timemacro.scheduler.domain.scheduler.SchedulerEngine
import com.timemacro.scheduler.domain.scheduler.TaskPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class AndroidSchedulerEngine(
    private val context: Context,
    private val taskRepository: TaskRepository,
    private val macroRepository: MacroRepository,
    private val logRepository: LogRepository,
    private val macroLogRepository: MacroLogRepository,
    private val planner: TaskPlanner = DefaultTaskPlanner(),
) : SchedulerEngine {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // B5 fix: serialize schedule/cancel operations across callers.
    // Previously HealthCheckWorker.doWork() and SchedulerForegroundService.onTaskComplete()
    // could both call schedule(task) concurrently → two PendingIntents racing on the same
    // request code, plus inconsistent nextScheduledAt writes.
    private val scheduleMutex = Mutex()

    override suspend fun schedule(task: Task) {
        scheduleMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!task.active) {
                cancelLocked(task.id)
                return@withContext
            }

            val now = System.currentTimeMillis()

            // Expiry enforcement: if expired, auto-disable and log.
            val endsAt = task.planEndsAt
            if (endsAt != null && Instant.ofEpochMilli(now).isAfter(endsAt)) {
                taskRepository.upsert(task.copy(active = false))
                cancelLocked(task.id)
                writeLog(
                    taskId = task.id,
                    source = "SCHEDULER",
                    scheduledTimeMs = now,
                    startMs = now,
                    endMs = now,
                    status = "SKIPPED",
                    error = "Expired",
                )
                addMacroLog(
                    macroId = task.macroId,
                    macroName = "—",
                    source = "SCHEDULER",
                    status = "SKIPPED",
                    message = "Expired taskId=${task.id}",
                )
                return@withContext
            }

            if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                // Can't schedule exact alarms: log and exit.
                writeLog(
                    taskId = task.id,
                    source = "SCHEDULER",
                    scheduledTimeMs = now,
                    startMs = now,
                    endMs = now,
                    status = "PERMISSION_ERROR",
                    error = "Exact alarm permission missing",
                )
                addMacroLog(
                    macroId = task.macroId,
                    macroName = "—",
                    source = "SCHEDULER",
                    status = "PERMISSION_ERROR",
                    message = "Exact alarm permission missing taskId=${task.id}",
                )
                return@withContext
            }

            val macro = macroRepository.getById(task.macroId)
            if (macro == null) {
                cancelLocked(task.id)
                writeLog(
                    taskId = task.id,
                    source = "SCHEDULER",
                    scheduledTimeMs = now,
                    startMs = now,
                    endMs = now,
                    status = "FAILED",
                    error = "Macro not found",
                )
                addMacroLog(
                    macroId = task.macroId,
                    macroName = "—",
                    source = "SCHEDULER",
                    status = "FAILED",
                    message = "Macro not found taskId=${task.id}",
                )
                return@withContext
            }
            val macroDurationMs = macro.recordDurationMs
            val lastRunMs = task.lastRunAt?.toEpochMilli()

            val next: NextRunResult =
                planner.computeNextRun(
                    nowEpochMs = now,
                    task = task,
                    lastScheduledOrLastRunEpochMs = lastRunMs,
                    macroDurationMs = macroDurationMs,
                )

            val nextAt = next.nextRunAtEpochMs
            if (nextAt == null) {
                if (next.reason == "expired") {
                    taskRepository.upsert(task.copy(active = false))
                    cancelLocked(task.id)
                    writeLog(
                        taskId = task.id,
                        source = "SCHEDULER",
                        scheduledTimeMs = now,
                        startMs = now,
                        endMs = now,
                        status = "SKIPPED",
                        error = "Expired",
                    )
                    addMacroLog(
                        macroId = macro.id,
                        macroName = macro.name,
                        source = "SCHEDULER",
                        status = "SKIPPED",
                        message = "Expired taskId=${task.id}",
                    )
                } else {
                    cancelLocked(task.id)
                }
                return@withContext
            }

            scheduleExact(taskId = task.id, triggerAtMs = nextAt)
            taskRepository.updateScheduleState(
                taskId = task.id,
                nextScheduledAtEpochMs = nextAt,
                lastScheduledAtEpochMs = nextAt,
                lastRunAtEpochMs = task.lastRunAt?.toEpochMilli(),
            )
        }
        }
    }

    override suspend fun cancel(taskId: String) {
        scheduleMutex.withLock { cancelLocked(taskId) }
    }

    /**
     * Internal cancel that does NOT take the mutex. Used from inside schedule() (which already
     * holds the lock) to avoid self-deadlock — kotlinx.coroutines Mutex is not reentrant.
     */
    private suspend fun cancelLocked(taskId: String) {
        withContext(Dispatchers.IO) {
            val pi = alarmPendingIntent(taskId, create = false)
            if (pi != null) {
                alarmManager.cancel(pi)
                pi.cancel()
            }
            val task = taskRepository.getById(taskId)
            taskRepository.updateScheduleState(
                taskId = taskId,
                nextScheduledAtEpochMs = null,
                lastScheduledAtEpochMs = task?.lastScheduledAt?.toEpochMilli(),
                lastRunAtEpochMs = task?.lastRunAt?.toEpochMilli(),
            )
        }
    }

    override suspend fun rescheduleAllActiveTasks() {
        val tasks = withContext(Dispatchers.IO) { taskRepository.listActive() }
        tasks.forEach { schedule(it) }
    }

    fun isAlarmScheduled(taskId: String): Boolean {
        return alarmPendingIntent(taskId, create = false) != null
    }

    private fun scheduleExact(taskId: String, triggerAtMs: Long) {
        val pi = alarmPendingIntentCreate(taskId, scheduledAtMs = triggerAtMs)
        if (Build.VERSION.SDK_INT >= 23) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            @Suppress("DEPRECATION")
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    private fun alarmPendingIntent(taskId: String, create: Boolean, scheduledAtMs: Long? = null): PendingIntent? {
        val intent =
            Intent(context, com.timemacro.scheduler.receiver.AlarmReceiver::class.java).apply {
                action = ACTION_ALARM
                putExtra(EXTRA_TASK_ID, taskId)
                if (scheduledAtMs != null) putExtra(EXTRA_SCHEDULED_AT, scheduledAtMs)
            }

        val flags =
            (PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) or
                if (!create) PendingIntent.FLAG_NO_CREATE else 0

        return PendingIntent.getBroadcast(context, requestCode(taskId), intent, flags)
    }

    private fun alarmPendingIntentCreate(taskId: String, scheduledAtMs: Long): PendingIntent {
        val intent =
            Intent(context, com.timemacro.scheduler.receiver.AlarmReceiver::class.java).apply {
                action = ACTION_ALARM
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_SCHEDULED_AT, scheduledAtMs)
            }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode(taskId), intent, flags)
    }

    private fun requestCode(taskId: String): Int = taskId.hashCode()

    private suspend fun writeLog(
        taskId: String,
        source: String,
        scheduledTimeMs: Long,
        startMs: Long,
        endMs: Long,
        status: String,
        error: String?,
    ) {
        val entry =
            LogEntry(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                source = source,
                scheduledTime = Instant.ofEpochMilli(scheduledTimeMs),
                startTime = Instant.ofEpochMilli(startMs),
                endTime = Instant.ofEpochMilli(endMs),
                status = status,
                errorMessage = error,
            )
        logRepository.insert(entry)
    }

    private suspend fun addMacroLog(
        macroId: String?,
        macroName: String,
        source: String,
        status: String,
        message: String?,
    ) {
        macroLogRepository.insert(
            MacroLog(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                macroId = macroId,
                macroNameSnapshot = macroName,
                source = source,
                status = status,
                message = message,
                durationMs = null,
                steps = null,
            ),
        )
    }

    companion object {
        const val ACTION_ALARM = "com.timemacro.scheduler.action.TASK_ALARM"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_SCHEDULED_AT = "extra_scheduled_at"
    }
}

