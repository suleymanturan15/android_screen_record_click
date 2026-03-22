package com.timemacro.scheduler.domain.model

import java.time.Instant

data class Macro(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val recordDurationMs: Long,
    val actionsJson: String,
    val isEnabled: Boolean = true,
)

