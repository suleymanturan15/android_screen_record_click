package com.timemacro.scheduler.data.repository

import com.timemacro.scheduler.data.db.dao.LogDao
import com.timemacro.scheduler.data.db.entities.LogEntity
import com.timemacro.scheduler.domain.model.LogEntry
import com.timemacro.scheduler.domain.repository.LogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class LogRepositoryImpl(
    private val logDao: LogDao,
) : LogRepository {

    override suspend fun getById(id: String): LogEntry? {
        return logDao.getById(id)?.toDomain()
    }

    override suspend fun listByTask(taskId: String, limit: Int): List<LogEntry> {
        return logDao.listByTask(taskId).take(limit).map { it.toDomain() }
    }

    override suspend fun listRecent(limit: Int): List<LogEntry> {
        return logDao.listRecent(limit).map { it.toDomain() }
    }

    override fun observeRecent(limit: Int): Flow<List<LogEntry>> {
        return logDao.listRecentFlow(limit).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun lastForTask(taskId: String): LogEntry? {
        return logDao.lastForTask(taskId)?.toDomain()
    }

    override suspend fun insert(entry: LogEntry) {
        logDao.insert(entry.toEntity())
    }

    override suspend fun deleteAllForTask(taskId: String) {
        logDao.deleteAllForTask(taskId)
    }

    override suspend fun deleteAll() {
        logDao.deleteAll()
    }

    override suspend fun clearOld(beforeEpochMs: Long): Int {
        return logDao.clearOld(beforeEpochMs)
    }
}

private fun LogEntity.toDomain(): LogEntry =
    LogEntry(
        id = id,
        taskId = taskId,
        source = source,
        scheduledTime = Instant.ofEpochMilli(scheduledTime),
        startTime = startTime?.let { Instant.ofEpochMilli(it) },
        endTime = endTime?.let { Instant.ofEpochMilli(it) },
        status = status,
        errorMessage = errorMessage,
    )

private fun LogEntry.toEntity(): LogEntity =
    LogEntity(
        id = id,
        taskId = taskId,
        source = source,
        scheduledTime = scheduledTime.toEpochMilli(),
        startTime = startTime?.toEpochMilli(),
        endTime = endTime?.toEpochMilli(),
        status = status,
        errorMessage = errorMessage,
    )

