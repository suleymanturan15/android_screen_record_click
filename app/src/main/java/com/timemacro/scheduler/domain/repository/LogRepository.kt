package com.timemacro.scheduler.domain.repository

import com.timemacro.scheduler.domain.model.LogEntry
import kotlinx.coroutines.flow.Flow

interface LogRepository {
    suspend fun getById(id: String): LogEntry?
    suspend fun listByTask(taskId: String, limit: Int = 200): List<LogEntry>
    suspend fun listRecent(limit: Int = 200): List<LogEntry>
    fun observeRecent(limit: Int = 200): Flow<List<LogEntry>>
    suspend fun lastForTask(taskId: String): LogEntry?
    suspend fun insert(entry: LogEntry)
    suspend fun deleteAllForTask(taskId: String)
    suspend fun deleteAll()
    suspend fun clearOld(beforeEpochMs: Long): Int
}

