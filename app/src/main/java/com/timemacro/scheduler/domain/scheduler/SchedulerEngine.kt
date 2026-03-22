package com.timemacro.scheduler.domain.scheduler

import com.timemacro.scheduler.domain.model.Task

/**
 * Task zamanlamayı kuran/bozan motor.
 *
 * AlarmManager/WorkManager/FGS kombinasyonunu bu soyutlama arkasında saklarız.
 */
interface SchedulerEngine {
    suspend fun schedule(task: Task)
    suspend fun cancel(taskId: String)
    suspend fun rescheduleAllActiveTasks()
}

