package com.timemacro.scheduler.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val recordDurationMs: Long,
    val actionsJson: String,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
)

