package com.timemacro.scheduler.domain.macro

/**
 * Macro playback sözleşmesi.
 *
 * Playback “icrası” AccessibilityService üzerinden gerçekleşir.
 */
interface MacroRunner {
    suspend fun runMacro(macroId: String, runContext: RunContext)
    suspend fun stopCurrentRun(reason: String? = null)
}

data class RunContext(
    val taskId: String?,
    val scheduledTimeEpochMs: Long?,
    val trigger: String? = null,
)

