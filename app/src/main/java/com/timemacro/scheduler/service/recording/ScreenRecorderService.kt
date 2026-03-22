package com.timemacro.scheduler.service.recording

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.timemacro.scheduler.App
import com.timemacro.scheduler.MainActivity
import com.timemacro.scheduler.core.macro.MacroSessionManager
import com.timemacro.scheduler.domain.model.Macro
import com.timemacro.scheduler.domain.model.MacroLog
import com.timemacro.scheduler.service.overlay.OverlayStopService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * MediaProjection tabanlı screen recording için foreground service.
 */
class ScreenRecorderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // MUST: become a mediaProjection foreground service BEFORE getMediaProjection().
                startAsForeground(text = "Recording…", isRecording = true)
                scope.launch { startRecordingInternal(intent) }
            }
            ACTION_STOP -> {
                val trigger = intent.getStringExtra(EXTRA_STOP_TRIGGER) ?: "UNKNOWN"
                MacroSessionManager.onStopRequested(trigger)
                stopRecording()
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun startRecordingInternal(intent: Intent) {
        if (mediaProjection != null) return // already recording

        try {
            val resultData = intent.getParcelableExtraCompat(EXTRA_RESULT_DATA, Intent::class.java) ?: return
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            // SecurityException can happen if not started as correct FGS type.
            mediaProjection = pm.getMediaProjection(Activity.RESULT_OK, resultData)

            val (width, height, densityDpi) = getDisplayInfo()
            // Ensure MacroSessionManager has correct *real* display info for normalized coords.
            runCatching {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                val p = android.graphics.Point()
                @Suppress("DEPRECATION")
                display.getRealSize(p)
                val rotation = display.rotation
                MacroSessionManager.setRecordingDisplayInfo(
                    w = p.x,
                    h = p.y,
                    rotation = rotation,
                    densityDpi = densityDpi,
                )
            }

            val moviesDir = File(getExternalFilesDir(null), "Movies/TimeMacro").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            outputFile = File(moviesDir, "timemacro_$ts.mp4")

            val recorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(5_000_000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
            }
            mediaRecorder = recorder

            virtualDisplay =
                mediaProjection?.createVirtualDisplay(
                    "TimeMacroRecorder",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.surface,
                    null,
                    null,
                )

            recorder.start()
            startedAtMs = System.currentTimeMillis()
            MacroSessionManager.onVideoStarted(outputFile?.absolutePath, startedAtMs = startedAtMs)

            // Start overlay bubble ONLY after recording is successfully active.
            if (Settings.canDrawOverlays(this)) {
                val overlayIntent = Intent(this, OverlayStopService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(overlayIntent) else startService(overlayIntent)
            }
        } catch (se: SecurityException) {
            showToast("Recording failed: missing FGS mediaProjection")
            cleanupAfterFailure()
        } catch (t: Throwable) {
            showToast("Recording failed: ${t.message ?: t::class.java.simpleName}")
            cleanupAfterFailure()
        }
    }

    private fun stopRecording() {
        val stoppedAtMs = System.currentTimeMillis()
        val recorder = mediaRecorder
        val projection = mediaProjection
        val vd = virtualDisplay

        // Order matters.
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
            // If stop is called too quickly, MediaRecorder can throw; we still cleanup.
        }
        recorder?.reset()
        recorder?.release()
        mediaRecorder = null

        vd?.release()
        virtualDisplay = null

        projection?.stop()
        mediaProjection = null

        MacroSessionManager.onStopped(stoppedAtMs = stoppedAtMs)

        // Persist on the app scope so service shutdown does not cancel the save pipeline.
        (application as App).container.appScope.launch(Dispatchers.IO) {
            runCatching { persistMacroAfterStop() }
                .onFailure { Log.e("TimeMacro/DB", "persistMacroAfterStop failed", it) }
        }

        // Ensure overlay is removed even if stopped unexpectedly.
        runCatching { stopService(Intent(this, OverlayStopService::class.java)) }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { stopService(Intent(this, OverlayStopService::class.java)) }
        scope.cancel()
    }

    private fun startAsForeground(text: String, isRecording: Boolean) {
        val channelId = CHANNEL_RECORDING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "Recording",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }

        val stopIntent =
            Intent(this, ScreenRecorderService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_STOP_TRIGGER, "NOTIFICATION")
            }
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                2001,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("TimeMacro Scheduler")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(isRecording)
                .addAction(
                    NotificationCompat.Action(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "STOP",
                        stopPendingIntent,
                    ),
                )
                .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1002, notification)
        }
    }

    private fun getDisplayInfo(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        // Ensure even dimensions for encoder compatibility.
        val w = (metrics.widthPixels / 2) * 2
        val h = (metrics.heightPixels / 2) * 2
        return Triple(w, h, metrics.densityDpi)
    }

    private fun <T : Parcelable> Intent.getParcelableExtraCompat(key: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, clazz) else @Suppress("DEPRECATION") getParcelableExtra(key)
    }

    companion object {
        const val ACTION_START = "com.timemacro.scheduler.action.SCREEN_RECORD_START"
        const val ACTION_STOP = "com.timemacro.scheduler.action.SCREEN_RECORD_STOP"

        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_STOP_TRIGGER = "extra_stop_trigger"

        private const val CHANNEL_RECORDING = "recording"
    }

    private fun cleanupAfterFailure() {
        // Release resources without producing a "Stopped" session.
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        runCatching { stopService(Intent(this, OverlayStopService::class.java)) }
        MacroSessionManager.reset()

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun persistMacroAfterStop() {
        val s = MacroSessionManager.state.value
        val stopped = s as? MacroSessionManager.RecordingState.Stopped ?: return
        val trigger = MacroSessionManager.lastStopTrigger()

        val actionsJson = MacroSessionManager.buildActionsJson()
        val actionsCount = MacroSessionManager.actionsSnapshot().size
        val container = (application as App).container

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
                    durationMs = stopped.durationMs,
                    steps = actionsCount,
                ),
            )
            MacroSessionManager.reset()
            bringToMacros(toast = "Recording stopped: Accessibility disconnected")
            return
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
                    durationMs = stopped.durationMs,
                    steps = 0,
                ),
            )
            MacroSessionManager.reset()
            bringToMacros(toast = "No actions recorded")
            return
        }

        val macroId = UUID.randomUUID().toString()
        val pending = MacroSessionManager.consumePendingName()?.trim().orEmpty()
        val macroName =
            if (pending.isNotBlank()) pending
            else "Macro ${container.userPreferences.nextMacroAutoIndex()}"

        Log.d("TimeMacro/DB", "saving macroId=$macroId actions=$actionsCount jsonLen=${actionsJson.length}")
        val macro =
            Macro(
                id = macroId,
                name = macroName,
                createdAt = Instant.now(),
                recordDurationMs = stopped.durationMs,
                actionsJson = actionsJson,
            )

        container.macroRepository.upsert(macro)
        container.macroLogRepository.insert(
            MacroLog(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                macroId = macroId,
                macroNameSnapshot = macroName,
                source = "RECORD",
                status = "SUCCESS",
                message = null,
                durationMs = stopped.durationMs,
                steps = actionsCount,
            ),
        )

        // Reset session after saving.
        MacroSessionManager.reset()

        // Required UX: return to Macros list after STOP.
        bringToMacros(toast = "Saved: $macroName")
    }

    private fun bringToMacros(toast: String?) {
        val i =
            Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, com.timemacro.scheduler.ui.Routes.Macros)
                if (!toast.isNullOrBlank()) putExtra(MainActivity.EXTRA_TOAST, toast)
            }
        applicationContext.startActivity(i)
    }
}
