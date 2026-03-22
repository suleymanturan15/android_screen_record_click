package com.timemacro.scheduler.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val macroId: String,
    val startTimeHHmm: String,
    /**
     * Legacy column (v1..v4). Eski anlam: "gün içinde kaç saatlik pencere".
     * Yeni anlam (UI değişikliği sonrası): kullanılmıyor, sadece migrasyon/backward-compat için tutuluyor.
     */
    val dailyHoursToRun: Int,
    /**
     * Yeni alan: Gün içinde kaç kez çalışsın (1..24).
     */
    val runsPerDay: Int,
    val planType: String,
    val intervalMode: String,
    val fixedIntervalMinutes: Int?,
    val extraDelayMinutes: Int?,
    val initialDelayMinutes: Int,
    val monthlyDay: Int?,
    val yearlyMonth: Int?,
    val yearlyDay: Int?,
    val planEndsAt: Long?,
    val nextScheduledAt: Long?,
    val lastScheduledAt: Long?,
    val lastRunAt: Long?,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

