package com.timemacro.scheduler.data.repository

import com.timemacro.scheduler.data.db.dao.MacroLogDao
import com.timemacro.scheduler.data.db.entities.MacroLogEntity
import com.timemacro.scheduler.domain.model.MacroLog
import com.timemacro.scheduler.domain.repository.MacroLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class MacroLogRepositoryImpl(
    private val dao: MacroLogDao,
) : MacroLogRepository {
    override fun observeRecent(limit: Int): Flow<List<MacroLog>> {
        return dao.listRecentFlow(limit).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun insert(log: MacroLog) {
        dao.insert(log.toEntity())
    }

    override suspend fun clearAll(): Int = dao.deleteAll()
}

private fun MacroLogEntity.toDomain(): MacroLog =
    MacroLog(
        id = id,
        timestamp = Instant.ofEpochMilli(timestampMs),
        macroId = macroId,
        macroNameSnapshot = macroNameSnapshot,
        source = source,
        status = status,
        message = message,
        durationMs = durationMs,
        steps = steps,
    )

private fun MacroLog.toEntity(): MacroLogEntity =
    MacroLogEntity(
        id = id,
        timestampMs = timestamp.toEpochMilli(),
        macroId = macroId,
        macroNameSnapshot = macroNameSnapshot,
        source = source,
        status = status,
        message = message,
        durationMs = durationMs,
        steps = steps,
    )

