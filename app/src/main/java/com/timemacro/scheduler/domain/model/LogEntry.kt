package com.timemacro.scheduler.domain.model

import java.time.Instant

data class LogEntry(
    val id: String,
    val taskId: String,
    val source: String?,
    val scheduledTime: Instant,
    val startTime: Instant?,
    val endTime: Instant?,
    val status: String,
    val errorMessage: String?,
)

