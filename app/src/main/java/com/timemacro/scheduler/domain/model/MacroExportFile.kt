package com.timemacro.scheduler.domain.model

import com.timemacro.scheduler.core.macro.MacroPayload
import kotlinx.serialization.Serializable

@Serializable
data class MacroExportFile(
    val schemaVersion: Int = 1,
    val exportedAt: String,
    val macro: ExportedMacro,
) {
    @Serializable
    data class ExportedMacro(
        // Original id (for reference/debug only). Import always assigns a new id.
        val id: String? = null,
        val name: String,
        val durationMs: Long,
        val isEnabled: Boolean = true,
        val payload: MacroPayload,
        val meta: Meta? = null,
    )

    @Serializable
    data class Meta(
        val screenWidth: Int? = null,
        val screenHeight: Int? = null,
        val rotation: Int? = null,
        val densityDpi: Int? = null,
        val deviceModel: String? = null,
        val appVersion: String? = null,
    )
}

