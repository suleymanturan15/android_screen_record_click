package com.timemacro.scheduler.service.accessibility

import android.os.SystemClock
import android.util.Log
import com.timemacro.scheduler.core.macro.PlaybackRequest
import com.timemacro.scheduler.core.macro.MacroPlaybackBus
import com.timemacro.scheduler.core.macro.PlaybackStatus
import com.timemacro.scheduler.core.accessibility.AccessibilityConnection
import com.timemacro.scheduler.data.prefs.UserPreferences
import com.timemacro.scheduler.domain.macro.MacroRunner
import com.timemacro.scheduler.domain.macro.RunContext
import com.timemacro.scheduler.domain.repository.MacroRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * UI/Scheduler tarafından çağrılabilen MacroRunner.
 *
 * PART 4:
 * - Request/response ile playback tamamlanana kadar suspend eder.
 */
class AccessibilityMacroRunner(
    private val macroRepository: MacroRepository,
    private val userPreferences: UserPreferences,
) : MacroRunner {
    private companion object {
        const val TAG = "TimeMacro/Playback"
    }

    @Volatile private var lastRunId: String? = null
    private val runMutex = Mutex()
    @Volatile private var currentDeferred: CompletableDeferred<com.timemacro.scheduler.core.macro.PlaybackResult>? = null

    override suspend fun runMacro(macroId: String, runContext: RunContext) {
        runMutex.withLock {
        // B4 fix: handshake budget 2 sn → 6 sn. Right after a wake-from-Doze the bound service
        // may take longer than 2 sn to flip its StateFlow to true, which used to fail tasks.
        val connected =
            runCatching {
                withTimeout(6_000) { AccessibilityConnection.isConnected.first { it } }
            }.getOrNull() == true
        if (!connected || !AccessibilityConnection.isRuntimeConnectedNow()) {
            error("Accessibility runtime disconnected")
        }

        val macro = kotlinx.coroutines.withContext(Dispatchers.IO) { macroRepository.getById(macroId) }
            ?: error("Macro not found")
        val speedPercent = runCatching { userPreferences.playbackSpeedPercent.first() }.getOrDefault(100).coerceIn(25, 500)
        val speed = speedPercent.toDouble() / 100.0
        val recordedMs = macro.recordDurationMs.coerceAtLeast(0L)
        val expectedMs = (recordedMs.toDouble() / speed).toLong().coerceAtLeast(0L)
        val timeoutMs = (expectedMs.toDouble() * 1.35).toLong().coerceIn(30_000L, 20 * 60 * 1000L)

        // Single-run lock: cancel any existing run before starting a new one.
        val prev = currentDeferred
        val prevRunId = lastRunId
        if (prev != null && !prev.isCompleted && prevRunId != null) {
            MacroPlaybackBus.requestCancel(runId = prevRunId, reason = "Superseded by new run")
            // Best-effort: wait a moment for it to complete (avoids hanging callers).
            runCatching { withTimeout(2_000) { prev.await() } }
        }

        val runId = UUID.randomUUID().toString()
        lastRunId = runId
        val started = CompletableDeferred<Unit>()
        val deferred = CompletableDeferred<com.timemacro.scheduler.core.macro.PlaybackResult>()
        currentDeferred = deferred
        val ok =
            MacroPlaybackBus.requestPlayback(
                PlaybackRequest(
                    runId = runId,
                    macroId = macroId,
                    taskId = runContext.taskId,
                    scheduledTimeEpochMs = runContext.scheduledTimeEpochMs,
                    trigger = runContext.trigger ?: "TASK",
                    started = started,
                    result = deferred,
                ),
            )
        if (!ok) error("Could not enqueue playback request")

        // B4 fix: service-ack handshake 3 sn → 6 sn (same reason as the connection probe above).
        withTimeout(6_000) { started.await() }

        Log.d(TAG, "await result runId=$runId macroId=$macroId recordedMs=$recordedMs speedPct=$speedPercent timeoutMs=$timeoutMs")

        val res =
            withTimeout(timeoutMs) {
                // Wait for completion result; also abort if service disconnects mid-run (MIUI).
                while (true) {
                    if (deferred.isCompleted) break
                    if (!AccessibilityConnection.isRuntimeConnectedNow()) {
                        MacroPlaybackBus.requestCancel(runId = runId, reason = "ACCESSIBILITY_DISCONNECTED")
                        error("ACCESSIBILITY_DISCONNECTED")
                    }
                    delay(250)
                }
                deferred.await()
            }
        if (res.status == PlaybackStatus.CANCELLED) throw CancellationException(res.errorMessage ?: "Cancelled")
        if (res.status != PlaybackStatus.SUCCESS) error(res.errorMessage ?: "Playback failed")
        if (res.executedActions <= 0 && res.totalActions > 0) error("Playback finished without executing actions")
        }
    }

    override suspend fun stopCurrentRun(reason: String?) {
        val runId = lastRunId ?: return
        MacroPlaybackBus.requestCancel(runId = runId, reason = reason ?: "Cancelled by user")
    }
}

