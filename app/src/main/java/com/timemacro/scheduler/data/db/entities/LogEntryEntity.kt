package com.timemacro.scheduler.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val source: String?,
    val scheduledTime: Long,
    val startTime: Long?,
    val endTime: Long?,
    val status: String,
    val errorMessage: String?,
)

