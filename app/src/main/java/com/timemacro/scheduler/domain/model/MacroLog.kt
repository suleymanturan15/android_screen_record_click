package com.timemacro.scheduler.domain.model

import java.time.Instant

data class MacroLog(
    val id: String,
    val timestamp: Instant,
    val macroId: String?,
    val macroNameSnapshot: String,
    val source: String,
    val status: String,
    val message: String?,
    val durationMs: Long?,
    val steps: Int?,
)

