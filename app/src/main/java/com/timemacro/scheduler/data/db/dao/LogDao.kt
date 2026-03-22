package com.timemacro.scheduler.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.timemacro.scheduler.data.db.entities.LogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LogEntity)

    @Query("SELECT * FROM logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LogEntity?

    @Query("SELECT * FROM logs WHERE taskId = :taskId ORDER BY scheduledTime DESC")
    suspend fun listByTask(taskId: String): List<LogEntity>

    @Query("SELECT * FROM logs ORDER BY scheduledTime DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<LogEntity>

    @Query("SELECT * FROM logs ORDER BY scheduledTime DESC LIMIT :limit")
    fun listRecentFlow(limit: Int): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE taskId = :taskId ORDER BY scheduledTime DESC LIMIT 1")
    suspend fun lastForTask(taskId: String): LogEntity?

    @Query("DELETE FROM logs WHERE scheduledTime < :before")
    suspend fun clearOld(before: Long): Int

    @Query("DELETE FROM logs WHERE taskId = :taskId")
    suspend fun deleteAllForTask(taskId: String): Int

    @Query("DELETE FROM logs")
    suspend fun deleteAll(): Int
}

