package com.timemacro.scheduler.domain.model

import java.time.Instant
import java.time.LocalTime

data class Task(
    val id: String,
    val macroId: String,
    val startTime: LocalTime,
    /**
     * Gün içinde en fazla kaç kez çalışsın (1..24).
     *
     * Not: Eski sürümlerde "dailyHoursToRun" (saat penceresi) olarak kullanılıyordu.
     * Yeni sürümde bu alan "runsPerDay" olarak semantik değiştirildi.
     */
    val runsPerDay: Int,
    /**
     * "DAILY" | "MONTHLY" | "YEARLY"
     */
    val planType: String,
    /**
     * "FIXED_INTERVAL" | "MACRO_BASED"
     */
    val intervalMode: String,
    val fixedIntervalMinutes: Int?,
    val extraDelayMinutes: Int?,
    val initialDelayMinutes: Int,
    /**
     * MONTHLY için: Ayın kaçıncı günü (1..31)
     */
    val monthlyDay: Int? = null,
    /**
     * YEARLY için: Ay (1..12)
     */
    val yearlyMonth: Int? = null,
    /**
     * YEARLY için: Gün (1..31)
     */
    val yearlyDay: Int? = null,
    val planEndsAt: Instant?,
    val nextScheduledAt: Instant?,
    val lastScheduledAt: Instant?,
    val lastRunAt: Instant?,
    val active: Boolean,
)

