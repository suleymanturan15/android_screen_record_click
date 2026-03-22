package com.timemacro.scheduler

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.timemacro.scheduler.service.scheduler.HealthCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Debug safety net: if anything crashes the process, print a clear stacktrace in Logcat.
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                Log.e("CRASH", "uncaught", e)
            }
        }

        // Safety-net periodic health check (every 15 min minimum).
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val healthReq =
                PeriodicWorkRequestBuilder<HealthCheckWorker>(15, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(this@App)
                .enqueueUniquePeriodicWork("health_check", ExistingPeriodicWorkPolicy.KEEP, healthReq)
        }
    }
}

