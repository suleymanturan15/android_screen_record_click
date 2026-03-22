package com.timemacro.scheduler.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "macro_logs")
data class MacroLogEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val macroId: String?,
    val macroNameSnapshot: String,
    val source: String,
    val status: String,
    val message: String?,
    val durationMs: Long?,
    val steps: Int?,
)

