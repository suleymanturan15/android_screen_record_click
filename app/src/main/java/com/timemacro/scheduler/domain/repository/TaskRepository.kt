package com.timemacro.scheduler.domain.repository

import com.timemacro.scheduler.domain.model.Task

interface TaskRepository {
    suspend fun getById(id: String): Task?
    suspend fun listAll(): List<Task>
    suspend fun listActive(): List<Task>
    suspend fun upsert(task: Task)
    /**
     * Explicit update for "edit task" flow.
     *
     * Note: Domain `Task` doesn't carry createdAt/updatedAt; implementations may delegate to upsert.
     */
    suspend fun update(task: Task)
    suspend fun updateScheduleState(
        taskId: String,
        nextScheduledAtEpochMs: Long?,
        lastScheduledAtEpochMs: Long?,
        lastRunAtEpochMs: Long?,
    )
    suspend fun delete(id: String)
}

