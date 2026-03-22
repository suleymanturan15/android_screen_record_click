package com.timemacro.scheduler.domain.repository

import com.timemacro.scheduler.domain.model.MacroLog
import kotlinx.coroutines.flow.Flow

interface MacroLogRepository {
    fun observeRecent(limit: Int = 200): Flow<List<MacroLog>>
    suspend fun insert(log: MacroLog)
    suspend fun clearAll(): Int
}

