package com.timemacro.scheduler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.timemacro.scheduler.service.scheduler.BootRescheduleWorker

/**
 * Reboot sonrası aktif task'ları yeniden schedule etmek için receiver.
 *
 * Not: Receiver içinde uzun iş yapmayıp WorkManager ile devam ediyoruz.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val req = OneTimeWorkRequestBuilder<BootRescheduleWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("boot_reschedule", ExistingWorkPolicy.REPLACE, req)
    }
}

