package com.timemacro.scheduler.core.recording

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.timemacro.scheduler.core.macro.MacroSessionManager
import com.timemacro.scheduler.service.recording.ScreenRecorderService
import com.timemacro.scheduler.service.overlay.OverlayStopService
import com.timemacro.scheduler.MainActivity
import com.timemacro.scheduler.App
import com.timemacro.scheduler.ui.Routes
import com.timemacro.scheduler.domain.model.Macro
import com.timemacro.scheduler.domain.model.MacroLog
import com.timemacro.scheduler.service.keepalive.KeepAliveService
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Unified stop pipeline used by:
 * - UI (Stop & Save)
 * - Notification STOP action
 * - Volume keys via AccessibilityService
 */
object RecordingController {
    fun startOverlayIfPermitted(context: Context, reason: String) {
        val hasOverlay = Settings.canDrawOverlays(context)
        Log.d("TimeMacro/Recording", "startOverlay permission=$hasOverlay reason=$reason recording=${MacroSessionManager.isRecording()}")
        if (!hasOverlay) return
        runCatching {
            val i = Intent(context, OverlayStopService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }.onFailure {
            Log.e("TimeMacro/Recording", "startOverlay failed", it)
        }
    }

    fun stopRecording(context: Context, trigger: String) {
        Log.d("TimeMacro/Recording", "stopOverlay trigger=$trigger")
        MacroSessionManager.onStopRequested(trigger)
        KeepAliveService.stop(context, token = "recording")

        // IMPORTANT UX: If STOP happens outside the app UI (overlay / volume keys / notification),
        // immediately bring TimeMacro to foreground (Android background-start restrictions are tighter later).
        if (trigger != "UI") {
            runCatching {
                val i =
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        )
                        putExtra(MainActivity.EXTRA_OPEN_ROUTE, Routes.Macros)
                    }
                context.startActivity(i)
                Log.i("TimeMacro/Recording", "STOP($trigger) -> bring app to Macros")
            }.onFailure {
                Log.e("TimeMacro/Recording", "STOP($trigger) -> failed to bring app to foreground", it)
            }
        }

        // Remove overlay bubble first (best-effort).
        context.stopService(Intent(context, OverlayStopService::class.java))

        // If this is a tap-only session (no MediaProjection service), stop + auto-save here too.
        val current = MacroSessionManager.state.value
        val isTapOnlyRecording =
            current is MacroSessionManager.RecordingState.Recording && current.videoPath == null
        if (isTapOnlyRecording) {
            val app = context.applicationContext as? App
            val container = app?.container
            if (container != null) {
                container.appScope.launch(Dispatchers.IO) {
                    MacroSessionManager.onStopped()
                    val stopped = MacroSessionManager.state.value as? MacroSessionManager.RecordingState.Stopped
                    val actionsCount = MacroSessionManager.actionsSnapshot().size
                    Log.i("TimeMacro/Recording", "tap-only stopped trigger=$trigger actions=$actionsCount durationMs=${stopped?.durationMs}")

                    // MIUI disconnect (or other runtime kill) must stop safely without saving.
                    if (trigger == "ACCESSIBILITY_DISCONNECTED") {
                        container.macroLogRepository.insert(
                            MacroLog(
                                id = UUID.randomUUID().toString(),
                                timestamp = Instant.now(),
                                macroId = null,
                                macroNameSnapshot = MacroSessionManager.consumePendingName() ?: "—",
                                source = "RECORD",
                                status = "FAILED",
                                message = "ACCESSIBILITY_DISCONNECTED",
                                durationMs = stopped?.durationMs ?: 0L,
                                steps = actionsCount,
                            ),
                        )
                        MacroSessionManager.reset()
                        bringToMacros(context, toast = "Recording stopped: Accessibility disconnected")
                        return@launch
                    }

                    if (actionsCount <= 0) {
                        container.macroLogRepository.insert(
                            MacroLog(
                                id = UUID.randomUUID().toString(),
                                timestamp = Instant.now(),
                                macroId = null,
                                macroNameSnapshot = MacroSessionManager.consumePendingName() ?: "—",
                                source = "RECORD",
                                status = "FAILED",
                                message = "No actions recorded",
                                durationMs = stopped?.durationMs ?: 0L,
                                steps = 0,
                            ),
                        )
                        MacroSessionManager.reset()
                        bringToMacros(context, toast = "No actions recorded")
                        return@launch
                    }

                    val macroId = UUID.randomUUID().toString()
                    val pending = MacroSessionManager.consumePendingName()?.trim().orEmpty()
                    val macroName =
                        if (pending.isNotBlank()) pending
                        else "Macro ${container.userPreferences.nextMacroAutoIndex()}"
                    val actionsJson = MacroSessionManager.buildActionsJson()
                    Log.d("TimeMacro/DB", "tap-only upsert macroId=$macroId name=$macroName actions=$actionsCount jsonLen=${actionsJson.length}")
                    container.macroRepository.upsert(
                        Macro(
                            id = macroId,
                            name = macroName,
                            createdAt = Instant.now(),
                            recordDurationMs = stopped?.durationMs ?: 0L,
                            actionsJson = actionsJson,
                        ),
                    )
                    container.macroLogRepository.insert(
                        MacroLog(
                            id = UUID.randomUUID().toString(),
                            timestamp = Instant.now(),
                            macroId = macroId,
                            macroNameSnapshot = macroName,
                            source = "RECORD",
                            status = "SUCCESS",
                            message = null,
                            durationMs = stopped?.durationMs ?: 0L,
                            steps = actionsCount,
                        ),
                    )
                    MacroSessionManager.reset()

                    // Required UX: return to Macros list after STOP.
                    bringToMacros(context, toast = "Saved: $macroName")
                }
            }
            return
        }

        val stopIntent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_STOP
            putExtra(ScreenRecorderService.EXTRA_STOP_TRIGGER, trigger)
        }
        context.startService(stopIntent)
    }

    private fun bringToMacros(context: Context, toast: String?) {
        runCatching {
            val i =
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
                    putExtra(MainActivity.EXTRA_OPEN_ROUTE, Routes.Macros)
                    if (!toast.isNullOrBlank()) putExtra(MainActivity.EXTRA_TOAST, toast)
                }
            context.startActivity(i)
        }.onFailure {
            Log.e("TimeMacro/Recording", "bringToMacros failed", it)
        }
    }
}

