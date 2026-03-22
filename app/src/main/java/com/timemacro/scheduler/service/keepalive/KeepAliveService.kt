package com.timemacro.scheduler.service.keepalive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.timemacro.scheduler.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service used to keep the app process alive on aggressive OEMs (MIUI)
 * during critical flows (recording / manual playback).
 *
 * NOTE: Scheduled tasks already run inside `SchedulerForegroundService`.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        when (action) {
            ACTION_START -> {
                if (token.isNotBlank()) tokens[token] = label.ifBlank { "Running…" }
                Log.d(TAG, "START token=$token label=$label size=${tokens.size}")
                updateNotification()
            }
            ACTION_STOP -> {
                if (token.isNotBlank()) tokens.remove(token)
                Log.d(TAG, "STOP token=$token size=${tokens.size}")
                if (tokens.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun updateNotification() {
        val text = tokens.values.firstOrNull() ?: "Running…"
        val n =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TimeMacro Scheduler")
                .setContentText("KEEP_ALIVE • $text")
                .setSmallIcon(android.R.drawable.presence_online)
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Keep alive",
                NotificationManager.IMPORTANCE_LOW,
            )
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TimeMacro/Task"
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIF_ID = 9001

        const val ACTION_START = "com.timemacro.scheduler.action.KEEP_ALIVE_START"
        const val ACTION_STOP = "com.timemacro.scheduler.action.KEEP_ALIVE_STOP"
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_LABEL = "extra_label"

        private val tokens: MutableMap<String, String> = ConcurrentHashMap()

        fun start(context: android.content.Context, token: String, label: String) {
            val i =
                Intent(context, KeepAliveService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TOKEN, token)
                    putExtra(EXTRA_LABEL, label)
                }
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }.onFailure {
                Log.e(TAG, "start keepAlive failed", it)
            }
        }

        fun stop(context: android.content.Context, token: String) {
            val i =
                Intent(context, KeepAliveService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_TOKEN, token)
                }
            runCatching { context.startService(i) }.onFailure {
                Log.e(TAG, "stop keepAlive failed", it)
            }
        }
    }
}

