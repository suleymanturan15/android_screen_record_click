package com.timemacro.scheduler

import android.content.Context
import com.timemacro.scheduler.data.db.TimeMacroDatabase
import com.timemacro.scheduler.data.prefs.UserPreferences
import com.timemacro.scheduler.data.repository.LogRepositoryImpl
import com.timemacro.scheduler.data.repository.MacroLogRepositoryImpl
import com.timemacro.scheduler.data.repository.MacroRepositoryImpl
import com.timemacro.scheduler.data.repository.TaskRepositoryImpl
import com.timemacro.scheduler.core.accessibility.AccessibilityStateRepository
import com.timemacro.scheduler.domain.macro.MacroRunner
import com.timemacro.scheduler.domain.repository.MacroLogRepository
import com.timemacro.scheduler.domain.repository.LogRepository
import com.timemacro.scheduler.domain.repository.MacroRepository
import com.timemacro.scheduler.domain.repository.TaskRepository
import com.timemacro.scheduler.domain.scheduler.SchedulerEngine
import com.timemacro.scheduler.domain.scheduler.TaskPlanner
import com.timemacro.scheduler.domain.scheduler.TaskRunner
import com.timemacro.scheduler.service.accessibility.AccessibilityMacroRunner
import com.timemacro.scheduler.service.scheduler.AndroidSchedulerEngine
import com.timemacro.scheduler.service.scheduler.AndroidTaskRunner
import com.timemacro.scheduler.domain.scheduler.DefaultTaskPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(appContext: Context) {
    private val context = appContext.applicationContext

    // Lazy: avoid any DB init on cold start until first use.
    val database: TimeMacroDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TimeMacroDatabase.build(context)
    }

    val userPreferences: UserPreferences = UserPreferences(context)

    /**
     * App-wide scope not tied to Activity/UI lifecycle.
     * Use for long-running operations that must survive backgrounding.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val accessibilityStateRepository: AccessibilityStateRepository by lazy {
        AccessibilityStateRepository(
            appContext = context,
            appScope = appScope,
        )
    }

    val macroRepository: MacroRepository by lazy { MacroRepositoryImpl(database.macroDao()) }
    val taskRepository: TaskRepository by lazy { TaskRepositoryImpl(database.taskDao()) }
    val logRepository: LogRepository by lazy { LogRepositoryImpl(database.logDao()) }
    val macroLogRepository: MacroLogRepository by lazy { MacroLogRepositoryImpl(database.macroLogDao()) }

    val macroRunner: MacroRunner by lazy {
        AccessibilityMacroRunner(
            macroRepository = macroRepository,
            userPreferences = userPreferences,
        )
    }

    val taskPlanner: TaskPlanner = DefaultTaskPlanner()

    val schedulerEngine: SchedulerEngine by lazy {
        AndroidSchedulerEngine(
            context = context,
            taskRepository = taskRepository,
            macroRepository = macroRepository,
            logRepository = logRepository,
            macroLogRepository = macroLogRepository,
            planner = taskPlanner,
        )
    }

    val taskRunner: TaskRunner by lazy {
        AndroidTaskRunner(
            appContext = context,
            taskRepository = taskRepository,
            macroRepository = macroRepository,
            logRepository = logRepository,
            macroLogRepository = macroLogRepository,
            macroRunner = macroRunner,
            userPreferences = userPreferences,
        )
    }
}

