package com.timemacro.scheduler.data.repository

import com.timemacro.scheduler.data.db.dao.MacroDao
import com.timemacro.scheduler.data.db.entities.MacroEntity
import com.timemacro.scheduler.domain.model.Macro
import com.timemacro.scheduler.domain.repository.MacroRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class MacroRepositoryImpl(
    private val macroDao: MacroDao,
) : MacroRepository {

    override suspend fun getById(id: String): Macro? {
        return macroDao.getById(id)?.toDomain()
    }

    override suspend fun listAll(): List<Macro> {
        return macroDao.listAll().map { it.toDomain() }
    }

    override fun observeAll(): Flow<List<Macro>> {
        return macroDao.listAllFlow().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun upsert(macro: Macro) {
        macroDao.insert(macro.toEntity())
    }

    override suspend fun delete(id: String) {
        macroDao.deleteById(id)
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        macroDao.setEnabled(id, enabled)
    }
}

private fun MacroEntity.toDomain(): Macro =
    Macro(
        id = id,
        name = name,
        createdAt = Instant.ofEpochMilli(createdAt),
        recordDurationMs = recordDurationMs,
        actionsJson = actionsJson,
        isEnabled = isEnabled,
    )

private fun Macro.toEntity(): MacroEntity =
    MacroEntity(
        id = id,
        name = name,
        createdAt = createdAt.toEpochMilli(),
        recordDurationMs = recordDurationMs,
        actionsJson = actionsJson,
        isEnabled = isEnabled,
    )

