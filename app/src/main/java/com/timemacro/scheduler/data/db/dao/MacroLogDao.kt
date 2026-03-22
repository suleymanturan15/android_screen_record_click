package com.timemacro.scheduler.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.timemacro.scheduler.data.db.entities.MacroLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MacroLogEntity)

    @Query("SELECT * FROM macro_logs ORDER BY timestampMs DESC LIMIT :limit")
    fun listRecentFlow(limit: Int): Flow<List<MacroLogEntity>>

    @Query("DELETE FROM macro_logs")
    suspend fun deleteAll(): Int
}

