package com.timemacro.scheduler.domain.scheduler

sealed class TaskRunResult {
    data class Success(val message: String? = null) : TaskRunResult()
    data class Failure(val message: String) : TaskRunResult()
}

interface TaskRunner {
    suspend fun runTaskNow(taskId: String, trigger: String): TaskRunResult
}

