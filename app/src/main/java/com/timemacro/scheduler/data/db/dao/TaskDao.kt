package com.timemacro.scheduler.data.db.dao

import com.timemacro.scheduler.data.db.entities.TaskEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TaskEntity)

    @Update
    suspend fun update(entity: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    suspend fun listAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE active = 1 ORDER BY updatedAt DESC")
    suspend fun listActive(): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """
        UPDATE tasks
        SET nextScheduledAt = :nextScheduledAt,
            lastScheduledAt = :lastScheduledAt,
            lastRunAt = :lastRunAt,
            updatedAt = :updatedAt
        WHERE id = :taskId
        """,
    )
    suspend fun updateScheduleState(
        taskId: String,
        nextScheduledAt: Long?,
        lastScheduledAt: Long?,
        lastRunAt: Long?,
        updatedAt: Long,
    )
}

