package com.timemacro.scheduler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.timemacro.scheduler.service.scheduler.AndroidSchedulerEngine
import com.timemacro.scheduler.service.scheduler.SchedulerForegroundService

/**
 * AlarmManager tetiklerini yakalayan receiver.
 *
 * B2 fix: alarm tetiklendiğinde
 *  1) goAsync() ile receiver'ı kısa süre canlı tutuyoruz,
 *  2) PARTIAL_WAKE_LOCK alıyoruz ki Doze altında SchedulerForegroundService
 *     startForeground'a yetişebilsin. Android 8+ "FGS didn't call startForeground"
 *     exception'ı bu yüzden sessizce alarmı yutuyordu.
 *
 * WakeLock + receiver lifetime: FGS'nin startForeground'a yetişebilmesi için ~3 sn
 * pencere bırakıyoruz; sonra Handler ile wakelock'u salıyor ve receiver'ı bitiriyoruz.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AndroidSchedulerEngine.EXTRA_TASK_ID) ?: return
        val scheduledAt = intent.getLongExtra(AndroidSchedulerEngine.EXTRA_SCHEDULED_AT, System.currentTimeMillis())

        val pending = goAsync()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimeMacro:AlarmReceiver").apply {
            setReferenceCounted(false)
        }
        runCatching { wl.acquire(WAKELOCK_BUDGET_MS) }

        val started =
            runCatching {
                val serviceIntent =
                    Intent(context, SchedulerForegroundService::class.java).apply {
                        action = SchedulerForegroundService.ACTION_RUN_TASK
                        putExtra(SchedulerForegroundService.EXTRA_TASK_ID, taskId)
                        putExtra(SchedulerForegroundService.EXTRA_SCHEDULED_AT, scheduledAt)
                        putExtra(SchedulerForegroundService.EXTRA_TRIGGER, "ALARM")
                    }
                context.startForegroundService(serviceIntent)
                true
            }.onFailure { Log.e("TimeMacro/Alarm", "failed to start FGS for taskId=$taskId", it) }
                .getOrDefault(false)
        Log.i("TimeMacro/Alarm", "fired taskId=$taskId scheduledAt=$scheduledAt started=$started")

        // Keep WL & receiver alive long enough for FGS to call startForeground (~3 sn).
        // Do NOT release synchronously; the FGS coroutine runs after onReceive returns.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { if (wl.isHeld) wl.release() }
            runCatching { pending.finish() }
        }, FGS_GRACE_MS)
    }

    private companion object {
        // Android caps BroadcastReceiver "async" lifetime to ~10s. Stay well under that.
        const val WAKELOCK_BUDGET_MS = 8_000L
        const val FGS_GRACE_MS = 3_000L
    }
}
