package com.timemacro.scheduler.service.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.timemacro.scheduler.core.macro.MacroSessionManager
import com.timemacro.scheduler.core.recording.RecordingController

class OverlayStopService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private var widget: OverlayStopWidget? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "overlay_show(mode=record)")
        startAsForeground()
        showBubble()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "overlay_hide(mode=record)")
        widget?.hide()
        widget = null
    }

    private fun startAsForeground() {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(channelId, "Overlay", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(channel)
        }

        val notification =
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("TimeMacro Scheduler")
                .setContentText("Recording control active")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build()

        startForeground(1101, notification)
    }

    private fun showBubble() {
        if (widget != null) return
        val layoutType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        widget =
            OverlayStopWidget(
                context = this,
                windowType = layoutType,
                mode = "record",
                onStopClicked = {
                    RecordingController.stopRecording(applicationContext, trigger = "OVERLAY_STOP")
                },
                elapsedMsProvider = {
                    if (!MacroSessionManager.isRecording()) {
                        // Auto-close quickly if recording ended.
                        stopSelf()
                        return@OverlayStopWidget 0L
                    }
                    val startElapsed = MacroSessionManager.recordStartElapsedRealtime()
                    val now = SystemClock.elapsedRealtime()
                    if (startElapsed != null) (now - startElapsed).coerceAtLeast(0L) else 0L
                },
                showTimer = true,
            ).also { it.show() }
    }

    companion object {
        private const val TAG = "TimeMacro/Recording"
        private const val CHANNEL_ID = "timemacro_overlay"

        const val ACTION_START = "com.timemacro.scheduler.action.OVERLAY_START"
        const val ACTION_STOP = "com.timemacro.scheduler.action.OVERLAY_STOP"
    }
}

