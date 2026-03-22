package com.timemacro.scheduler.service.scheduler

import android.content.Context
import android.content.Intent
import com.timemacro.scheduler.core.accessibility.AccessibilityConnection
import com.timemacro.scheduler.core.accessibility.isAccessibilityEnabledForThisService
import com.timemacro.scheduler.core.macro.MacroJsonCodec
import com.timemacro.scheduler.core.macro.MacroPayload
import com.timemacro.scheduler.data.prefs.UserPreferences
import com.timemacro.scheduler.domain.macro.MacroRunner
import com.timemacro.scheduler.domain.macro.RunContext
import com.timemacro.scheduler.domain.model.LogEntry
import com.timemacro.scheduler.domain.model.MacroLog
import com.timemacro.scheduler.domain.repository.MacroLogRepository
import com.timemacro.scheduler.domain.repository.LogRepository
import com.timemacro.scheduler.domain.repository.MacroRepository
import com.timemacro.scheduler.domain.repository.TaskRepository
import com.timemacro.scheduler.domain.scheduler.TaskRunResult
import com.timemacro.scheduler.domain.scheduler.TaskRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import java.time.Instant
import java.util.UUID

class AndroidTaskRunner(
    private val appContext: Context,
    private val taskRepository: TaskRepository,
    private val macroRepository: MacroRepository,
    private val logRepository: LogRepository,
    private val macroLogRepository: MacroLogRepository,
    private val macroRunner: MacroRunner,
    private val userPreferences: UserPreferences,
) : TaskRunner {

    override suspend fun runTaskNow(taskId: String, trigger: String): TaskRunResult {
        return runTaskInternal(taskId = taskId, trigger = trigger, scheduledTimeEpochMs = System.currentTimeMillis())
    }

    suspend fun runTaskInternal(taskId: String, trigger: String, scheduledTimeEpochMs: Long): TaskRunResult {
        val startMs = System.currentTimeMillis()

        val task = withContext(Dispatchers.IO) { taskRepository.getById(taskId) }
        if (task == null) {
            writeLog(
                taskId = taskId,
                source = trigger,
                scheduledTimeMs = scheduledTimeEpochMs,
                startMs = startMs,
                endMs = System.currentTimeMillis(),
                status = "FAILED",
                error = "Task not found",
            )
            return TaskRunResult.Failure("Task not found")
        }

        if (!task.active) {
            writeLog(
                taskId = task.id,
                source = trigger,
                scheduledTimeMs = scheduledTimeEpochMs,
                startMs = startMs,
                endMs = System.currentTimeMillis(),
                status = "SKIPPED",
                error = "Inactive",
            )
            return TaskRunResult.Success("Skipped (inactive)")
        }

        // Expiry check (monthly/yearly).
        val endsAt = task.planEndsAt
        if (endsAt != null && Instant.ofEpochMilli(scheduledTimeEpochMs).isAfter(endsAt)) {
            withContext(Dispatchers.IO) { taskRepository.upsert(task.copy(active = false)) }
            writeLog(
                taskId = task.id,
                source = trigger,
                scheduledTimeMs = scheduledTimeEpochMs,
                startMs = startMs,
                endMs = System.currentTimeMillis(),
                status = "SKIPPED",
                error = "Expired",
            )
            return TaskRunResult.Success("Skipped (expired)")
        }

        val enabledInSettings = runCatching { isAccessibilityEnabledForThisService(appContext) }.getOrDefault(false)
        val runtimeConnected = AccessibilityConnection.isRuntimeConnectedNow()
        if (!enabledInSettings) {
            writeLog(
                taskId = task.id,
                source = trigger,
                scheduledTimeMs = scheduledTimeEpochMs,
                startMs = startMs,
                endMs = System.currentTimeMillis(),
                status = "PERMISSION_ERROR",
                error = "Accessibility disabled (settings OFF)",
            )
            return TaskRunResult.Failure("Accessibility disabled (settings OFF)")
        }
        if (!runtimeConnected) {
            writeLog(
                taskId = task.id,
                source = trigger,
                scheduledTimeMs = scheduledTimeEpochMs,
                startMs = startMs,
                endMs = System.currentTimeMillis(),
                status = "PERMISSION_ERROR",
                error = "Accessibility runtime disconnected",
            )
            return TaskRunResult.Failure("Accessibility runtime disconnected")
        }

        val macro = withContext(Dispatchers.IO) { macroRepository.getById(task.macroId) }
        if (macro == null) {
            writeLog(
                taskId = task.id,
                source = trigger,
                scheduledTimeMs = scheduledTimeEpochMs,
                startMs = startMs,
                endMs = System.currentTimeMillis(),
                status = "FAILED",
                error = "Macro not found",
            )
            return TaskRunResult.Failure("Macro not found")
        }

        if (!macro.isEnabled) {
            addMacroLog(
                macroId = macro.id,
                macroName = macro.name,
                source = "TASK",
                status = "SKIPPED",
                message = "Macro disabled (taskId=${task.id})",
                durationMs = 0L,
                steps = null,
            )
            writeLog(
                taskId = task.id,
                source = trigger,
                scheduledTimeMs = scheduledTimeEpochMs,
                startMs = startMs,
                endMs = System.currentTimeMillis(),
                status = "SKIPPED",
                error = "Macro disabled",
            )
            return TaskRunResult.Success("Skipped (macro disabled)")
        }

        addMacroLog(
            macroId = macro.id,
            macroName = macro.name,
            source = "TASK",
            status = "STARTED",
            message = "taskId=${task.id}",
            durationMs = null,
            steps = null,
        )

        // Task preflight: bring recorded target app to foreground (best-effort) to avoid starting on wrong screen.
        val shouldLaunch = runCatching { userPreferences.taskLaunchTargetApp.first() }.getOrDefault(true)
        val launchDelayMs = runCatching { userPreferences.taskLaunchDelayMs.first() }.getOrDefault(1500).coerceIn(0, 10_000)
        if (shouldLaunch) {
            val pkg = runCatching {
                val payload = MacroJsonCodec.json.decodeFromString<MacroPayload>(macro.actionsJson)
                payload.targetPackageName
            }.getOrNull()
            if (!pkg.isNullOrBlank()) {
                runCatching {
                    val launch = appContext.packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        appContext.startActivity(launch)
                    }
                }
                if (launchDelayMs > 0) delay(launchDelayMs.toLong())
            }
        }

        val result = runCatching {
            withTimeout(RUN_TIMEOUT_MS) {
                macroRunner.runMacro(
                    macroId = macro.id,
                    runContext = RunContext(taskId = task.id, scheduledTimeEpochMs = scheduledTimeEpochMs, trigger = trigger),
                )
            }
        }.fold(
            onSuccess = { "SUCCESS" to null },
            onFailure = {
                if (it is CancellationException || (it.message?.contains("Cancelled", ignoreCase = true) == true)) {
                    "CANCELLED" to (it.message ?: "Cancelled")
                } else if (it.message?.contains("Accessibility service not connected", ignoreCase = true) == true) {
                    "PERMISSION_ERROR" to "Accessibility service not connected"
                } else if (it.message?.contains("ACCESSIBILITY_DISCONNECTED", ignoreCase = true) == true) {
                    "FAILED" to "ACCESSIBILITY_DISCONNECTED"
                } else {
                    "FAILED" to (it.message ?: it::class.java.simpleName)
                }
            },
        )

        val endMs = System.currentTimeMillis()
        // Deterministic state: mark lastRunAt and clear nextScheduledAt (next will be scheduled after this run).
        withContext(Dispatchers.IO) {
            taskRepository.updateScheduleState(
                taskId = task.id,
                nextScheduledAtEpochMs = null,
                lastScheduledAtEpochMs = scheduledTimeEpochMs,
                lastRunAtEpochMs = startMs,
            )
        }
        writeLog(
            taskId = task.id,
            source = trigger,
            scheduledTimeMs = scheduledTimeEpochMs,
            startMs = startMs,
            endMs = endMs,
            status = result.first,
            error = result.second,
        )

        addMacroLog(
            macroId = macro.id,
            macroName = macro.name,
            source = "TASK",
            status = result.first,
            message = result.second,
            durationMs = (endMs - startMs).coerceAtLeast(0L),
            steps = null,
        )

        return if (result.first == "SUCCESS") TaskRunResult.Success("OK") else TaskRunResult.Failure(result.second ?: "Failed")
    }

    private suspend fun addMacroLog(
        macroId: String?,
        macroName: String,
        source: String,
        status: String,
        message: String?,
        durationMs: Long?,
        steps: Int?,
    ) {
        withContext(Dispatchers.IO) {
            macroLogRepository.insert(
                MacroLog(
                    id = UUID.randomUUID().toString(),
                    timestamp = Instant.now(),
                    macroId = macroId,
                    macroNameSnapshot = macroName,
                    source = source,
                    status = status,
                    message = message,
                    durationMs = durationMs,
                    steps = steps,
                ),
            )
        }
    }

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
        withContext(Dispatchers.IO) { logRepository.insert(entry) }
    }

    companion object {
        private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L
    }
}

