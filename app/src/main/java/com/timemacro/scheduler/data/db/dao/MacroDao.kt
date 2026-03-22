package com.timemacro.scheduler.data.db.dao

import com.timemacro.scheduler.data.db.entities.MacroEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MacroEntity)

    @Update
    suspend fun update(entity: MacroEntity)

    @Query("SELECT * FROM macros WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MacroEntity?

    @Query("SELECT * FROM macros ORDER BY createdAt DESC")
    suspend fun listAll(): List<MacroEntity>

    @Query("SELECT * FROM macros ORDER BY createdAt DESC")
    fun listAllFlow(): Flow<List<MacroEntity>>

    @Query("DELETE FROM macros WHERE id = :id")
    suspend fun delete(id: String)

    // Alias for clarity in call sites.
    @Query("DELETE FROM macros WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE macros SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

