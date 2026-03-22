package com.timemacro.scheduler.domain.repository

import com.timemacro.scheduler.domain.model.Macro
import kotlinx.coroutines.flow.Flow

interface MacroRepository {
    suspend fun getById(id: String): Macro?
    suspend fun listAll(): List<Macro>
    fun observeAll(): Flow<List<Macro>>
    suspend fun upsert(macro: Macro)
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
}

