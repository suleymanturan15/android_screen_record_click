package com.timemacro.scheduler.service.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.timemacro.scheduler.App

class BootRescheduleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.retry()
        app.container.schedulerEngine.rescheduleAllActiveTasks()
        return Result.success()
    }
}

